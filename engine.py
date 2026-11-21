"""
Download engine.

Speed trick: instead of one long HTTP stream (which YouTube throttles per
connection), each file is split into pieces of 1-8 MB and fetched over many
connections at once with HTTP Range requests, the way a torrent client or
download manager does. Pieces are written straight into place in a
pre-allocated file, retried on failure, and shown live in the UI.

If a stream can't be fetched that way (HLS/DASH fragments, servers without
Range support, repeated errors) the job falls back to yt-dlp's own
downloader with concurrent fragment downloads.
"""
import os
import platform
import re
import shutil
import subprocess
import threading
import time
import uuid
from collections import OrderedDict, deque

import requests
import yt_dlp

FFMPEG = shutil.which("ffmpeg")
DOWNLOAD_DIR = os.path.expanduser(
    os.environ.get("DOWNLOAD_DIR", os.path.join("~", "Downloads", "YouTube Downloader")))
TMP_DIR = os.path.join(DOWNLOAD_DIR, ".partial")
MAX_ACTIVE_JOBS = int(os.environ.get("MAX_ACTIVE_JOBS", 2))
PIECE_MIN, PIECE_MAX = 1 << 20, 8 << 20
READ_SIZE = 256 * 1024

AUDIO_CODECS = {  # target -> (ffmpeg codec, extension, uses bitrate)
    "mp3": ("libmp3lame", "mp3", True),
    "m4a": ("aac", "m4a", True),
    "opus": ("libopus", "opus", True),
    "wav": ("pcm_s16le", "wav", False),
    "flac": ("flac", "flac", False),
}


class Cancelled(Exception):
    pass


def base_opts():
    return {"quiet": True, "no_warnings": True, "noplaylist": True}


def clean_error(e):
    msg = re.sub(r"\x1b\[[0-9;]*m", "", str(e)).replace("ERROR: ", "")
    return msg.split("; please report")[0][:400]


def fsize(f):
    return (f or {}).get("filesize") or (f or {}).get("filesize_approx")


# --------------------------------------------------------------------- info
def video_info(url):
    with yt_dlp.YoutubeDL(base_opts()) as ydl:
        info = ydl.extract_info(url, download=False)
    if info.get("_type") == "playlist":
        raise ValueError("That is a playlist link. Paste a single video link.")

    formats = info.get("formats") or []
    vids = [f for f in formats if f.get("vcodec") not in (None, "none") and f.get("height")]
    auds = [f for f in formats if f.get("vcodec") == "none" and f.get("acodec") not in (None, "none")]
    rank = lambda f: (f.get("fps") or 0, f.get("tbr") or 0)
    best_a = max(auds, key=lambda f: f.get("abr") or f.get("tbr") or 0, default=None)
    m4a_a = max([f for f in auds if f.get("ext") == "m4a"],
                key=lambda f: f.get("abr") or 0, default=best_a)

    qualities = []
    for h in sorted({f["height"] for f in vids}, reverse=True):
        at_h = [f for f in vids if f["height"] == h]
        best = max(at_h, key=rank)
        h264 = max([f for f in at_h if (f.get("vcodec") or "").startswith("avc")], key=rank, default=None)
        fps = int(best.get("fps") or 0)
        qualities.append({
            "height": h,
            "fps": fps,
            "label": f"{h}p" + (str(fps) if fps > 30 else ""),
            "tag": {4320: "8K", 2160: "4K", 1440: "2K", 1080: "Full HD", 720: "HD"}.get(h, ""),
            "hdr": any((f.get("dynamic_range") or "SDR") != "SDR" for f in at_h),
            "codec": (best.get("vcodec") or "").split(".")[0],
            "has_h264": bool(h264),
            "size": {
                "both": _sum(fsize(best), fsize(best_a)),
                "video": fsize(best),
                "both_compat": _sum(fsize(h264 or best), fsize(m4a_a)),
                "video_compat": fsize(h264 or best),
            },
        })
    return {
        "title": info.get("title"),
        "uploader": info.get("uploader") or info.get("channel"),
        "duration": info.get("duration"),
        "view_count": info.get("view_count"),
        "thumbnail": info.get("thumbnail"),
        "qualities": qualities,
        "audio_size": fsize(best_a),
        "ffmpeg": bool(FFMPEG),
    }


def _sum(a, b):
    return (a or 0) + (b or 0) or None


def format_spec(mode, height, compat, audio_format):
    h = f"[height<={height}]" if height else ""
    if mode == "audio":
        return "bestaudio[ext=m4a]/bestaudio/best" if audio_format == "m4a" else "bestaudio/best"
    if mode == "video":
        avc = f"bestvideo[vcodec^=avc]{h}/" if compat else ""
        return f"{avc}bestvideo{h}/bestvideo/best{h}/best"
    if not FFMPEG:  # can't merge: take a pre-merged stream
        return f"best{h}[acodec!=none][vcodec!=none]/best"
    if compat:
        return (f"bestvideo[vcodec^=avc]{h}+bestaudio[ext=m4a]/"
                f"bestvideo{h}+bestaudio[ext=m4a]/bestvideo{h}+bestaudio/best{h}/best")
    return f"bestvideo{h}+bestaudio/best{h}/best"


# --------------------------------------------------------------------- jobs
class Part:
    def __init__(self, kind, fmt, path):
        self.kind, self.fmt, self.path = kind, fmt, path
        self.size = 0
        self.done = 0
        self.pieces = []  # [start, end, state]  state: 0 waiting, 1 active, 2 done

    def snapshot(self):
        return {"kind": self.kind, "size": self.size, "done": self.done,
                "map": "".join(str(p[2]) for p in self.pieces)}


class Job:
    def __init__(self, req):
        self.id = uuid.uuid4().hex[:10]
        self.url = req["url"]
        self.mode = req["mode"]
        self.height = req.get("height")
        self.compat = req.get("compat", True)
        self.audio_format = req.get("audio_format", "mp3")
        self.audio_bitrate = req.get("audio_bitrate", "192")
        self.connections = req.get("connections", 16)
        self.title = req.get("title") or self.url
        self.thumbnail = req.get("thumbnail")
        self.label = ""
        self.status = "queued"  # queued preparing downloading processing done error cancelled
        self.message = "Waiting for a free slot"
        self.engine = "parallel"
        self.parts = []
        self.post_progress = None
        self.path = self.filename = self.error = None
        self.created = time.time()
        self.finished_at = None
        self.cancel = threading.Event()
        self.failed = threading.Event()
        self.lock = threading.Lock()
        self._samples = deque()
        self._speed = 0.0

    # live numbers for the UI
    def snapshot(self):
        with self.lock:
            done = sum(p.done for p in self.parts)
            total = sum(p.size for p in self.parts)
            now = time.time()
            if self.status == "downloading":
                if not self._samples or now - self._samples[-1][0] >= 0.2:
                    self._samples.append((now, done))
                while len(self._samples) > 2 and now - self._samples[0][0] > 4:
                    self._samples.popleft()
                t0, d0 = self._samples[0]
                self._speed = (done - d0) / (now - t0) if now - t0 > 0.3 else self._speed
            else:
                self._speed = 0.0
            eta = (total - done) / self._speed if self._speed > 1 and total else None
            return {
                "id": self.id, "title": self.title, "thumbnail": self.thumbnail,
                "mode": self.mode, "label": self.label, "status": self.status,
                "message": self.message, "engine": self.engine,
                "done": done, "total": total, "speed": self._speed, "eta": eta,
                "connections": self.connections,
                "active": sum(1 for p in self.parts for x in p.pieces if x[2] == 1),
                "post_progress": self.post_progress,
                "parts": [p.snapshot() for p in self.parts],
                "filename": self.filename, "error": self.error,
                "created": self.created, "finished_at": self.finished_at,
            }

    def set(self, **kw):
        with self.lock:
            for k, v in kw.items():
                setattr(self, k, v)

    def check(self):
        if self.cancel.is_set():
            raise Cancelled()


class Manager:
    def __init__(self):
        self.jobs = OrderedDict()
        self.slots = threading.Semaphore(MAX_ACTIVE_JOBS)
        os.makedirs(TMP_DIR, exist_ok=True)

    def submit(self, req):
        job = Job(req)
        self.jobs[job.id] = job
        threading.Thread(target=self._run, args=(job,), daemon=True).start()
        return job

    def _run(self, job):
        with self.slots:
            if job.cancel.is_set():
                job.set(status="cancelled", message="Cancelled", finished_at=time.time())
                return
            run_job(job)

    def get(self, job_id):
        return self.jobs.get(job_id)

    def remove(self, job_id):
        job = self.jobs.get(job_id)
        if job and job.status in ("done", "error", "cancelled"):
            del self.jobs[job_id]
            return True
        return False


# --------------------------------------------------------------------- pipeline
def run_job(job):
    tmp = os.path.join(TMP_DIR, job.id)
    os.makedirs(tmp, exist_ok=True)
    try:
        job.set(status="preparing", message="Finding the best streams")
        opts = base_opts() | {"format": format_spec(job.mode, job.height, job.compat, job.audio_format)}
        with yt_dlp.YoutubeDL(opts) as ydl:
            info = ydl.extract_info(job.url, download=False)
        job.check()
        fmts = info.get("requested_formats") or [info]
        parts = []
        for f in fmts:
            has_v = f.get("vcodec") not in (None, "none")
            kind = ("video" if has_v else "audio") if len(fmts) > 1 else \
                   ("audio" if job.mode == "audio" else "video")
            parts.append(Part(kind, f, os.path.join(tmp, f"{kind}.{f.get('ext') or 'bin'}")))
        job.set(title=info.get("title") or job.title, thumbnail=info.get("thumbnail") or job.thumbnail,
                label=_label(job, fmts), parts=parts, status="downloading", message="Downloading")

        if all(f.get("protocol") in ("http", "https") for f in fmts):
            try:
                parallel_download(job, parts)
            except Cancelled:
                raise
            except Exception as e:  # fall back to yt-dlp's downloader
                print(f"[{job.id}] parallel download failed ({clean_error(e)}), using yt-dlp")
                ytdlp_download(job, info, parts)
        else:
            ytdlp_download(job, info, parts)

        job.check()
        final = postprocess(job, parts, info)
        job.set(status="done", message="Saved", path=final, filename=os.path.basename(final),
                post_progress=None, finished_at=time.time())
    except Cancelled:
        _drop_output(job)
        job.set(status="cancelled", message="Cancelled", finished_at=time.time())
    except Exception as e:
        _drop_output(job)
        job.set(status="error", error=clean_error(e), message="Failed", finished_at=time.time())
    finally:
        shutil.rmtree(tmp, ignore_errors=True)


def _drop_output(job):
    if job.path and os.path.exists(job.path):
        try:
            os.remove(job.path)
        except OSError:
            pass
    job.path = None


def _label(job, fmts):
    if job.mode == "audio":
        fmt = job.audio_format if FFMPEG else (fmts[0].get("ext") or "audio")
        br = f" {job.audio_bitrate}k" if FFMPEG and AUDIO_CODECS.get(job.audio_format, (0, 0, 0))[2] else ""
        return f"{fmt.upper()}{br}"
    v = next((f for f in fmts if f.get("vcodec") not in (None, "none")), fmts[0])
    q = f"{v.get('height')}p" if v.get("height") else "Video"
    if (v.get("fps") or 0) > 30:
        q += str(int(v["fps"]))
    raw = (v.get("vcodec") or "").split(".")[0].lower()
    codec = {"avc1": "H.264", "avc3": "H.264", "vp09": "VP9", "vp9": "VP9", "av01": "AV1"}.get(raw, raw.upper())
    return f"{q} · {codec or 'MP4'}" + (" · no audio" if job.mode == "video" else "")


# ---- parallel piece downloader
def _probe_size(sess, f):
    r = sess.get(f["url"], headers={**(f.get("http_headers") or {}), "Range": "bytes=0-0"},
                 stream=True, timeout=(10, 20))
    r.close()
    cr = r.headers.get("Content-Range", "")
    if r.status_code == 206 and "/" in cr and cr.rsplit("/", 1)[1].isdigit():
        return int(cr.rsplit("/", 1)[1])
    raise RuntimeError("server does not support ranged downloads")


def parallel_download(job, parts):
    probe = requests.Session()
    for p in parts:
        size = p.fmt.get("filesize") or _probe_size(probe, p.fmt)
        piece = max(PIECE_MIN, min(PIECE_MAX, size // max(1, job.connections * 4)))
        with job.lock:
            p.size, p.done = size, 0
            p.pieces = [[s, min(s + piece, size) - 1, 0] for s in range(0, size, piece)]
        with open(p.path, "wb") as fh:
            fh.truncate(size)

    queue = deque((p, i) for p in parts for i in range(len(p.pieces)))
    errors = []

    def worker():
        sess = requests.Session()
        handles = {}
        try:
            while not (job.cancel.is_set() or job.failed.is_set()):
                with job.lock:
                    if not queue:
                        return
                    p, i = queue.popleft()
                    p.pieces[i][2] = 1
                try:
                    _fetch_piece(job, sess, handles, p, i)
                except Cancelled:
                    return
                except Exception as e:
                    errors.append(e)
                    job.failed.set()
                    return
        finally:
            for fh in handles.values():
                fh.close()
            sess.close()

    n = max(1, min(job.connections, len(queue)))
    threads = [threading.Thread(target=worker, daemon=True) for _ in range(n)]
    for t in threads:
        t.start()
    for t in threads:
        t.join()
    job.check()
    if errors:
        job.failed.clear()
        raise errors[0]


def _fetch_piece(job, sess, handles, p, i):
    start, end, _ = p.pieces[i]
    headers = {**(p.fmt.get("http_headers") or {}), "Range": f"bytes={start}-{end}"}
    for attempt in range(6):
        got = 0
        try:
            with sess.get(p.fmt["url"], headers=headers, stream=True, timeout=(10, 30)) as r:
                if r.status_code != 206:
                    raise RuntimeError(f"HTTP {r.status_code}")
                fh = handles.get(p.path)
                if fh is None:
                    fh = handles[p.path] = open(p.path, "r+b")
                fh.seek(start)
                for data in r.iter_content(READ_SIZE):
                    if job.cancel.is_set():
                        raise Cancelled()
                    fh.write(data)
                    got += len(data)
                    with job.lock:
                        p.done += len(data)
            if got != end - start + 1:
                raise IOError(f"piece {i} short read ({got} of {end - start + 1} bytes)")
            with job.lock:
                p.pieces[i][2] = 2
            return
        except Cancelled:
            raise
        except Exception:
            with job.lock:
                p.done -= got
            if attempt == 5 or job.failed.is_set():
                raise
            time.sleep(0.5 * 2 ** attempt)


# ---- fallback: yt-dlp downloader (HLS/DASH, odd servers)
def ytdlp_download(job, info, parts):
    job.set(engine="yt-dlp")
    for p in parts:
        with job.lock:
            p.size, p.done, p.pieces = fsize(p.fmt) or 0, 0, []

        def hook(d, p=p):
            if job.cancel.is_set():
                raise Cancelled()
            if d["status"] != "downloading":
                return
            with job.lock:
                p.size = d.get("total_bytes") or d.get("total_bytes_estimate") or p.size
                p.done = d.get("downloaded_bytes") or 0
                n, idx = d.get("fragment_count"), d.get("fragment_index")
                if n and idx:
                    p.pieces = [[0, 0, 2 if k < idx else (1 if k == idx else 0)] for k in range(n)]

        opts = base_opts() | {
            "progress_hooks": [hook],
            "concurrent_fragment_downloads": job.connections,
            "http_chunk_size": 10 << 20,
        }
        with yt_dlp.YoutubeDL(opts) as ydl:
            fmt = dict(p.fmt)
            fmt.setdefault("http_headers", info.get("http_headers") or {})
            ok, _ = ydl.dl(p.path, fmt)
            if not ok:
                raise RuntimeError("download failed")
        with job.lock:
            p.done = p.size = os.path.getsize(p.path)
            p.pieces = [[0, 0, 2]] * max(1, len(p.pieces))


# ---- ffmpeg finishing step
def _ffmpeg(job, args, duration, message):
    job.set(status="processing", message=message, post_progress=0)
    cmd = [FFMPEG, "-y", "-hide_banner", "-loglevel", "error", "-nostats",
           "-progress", "pipe:1", *args]
    proc = subprocess.Popen(cmd, stdout=subprocess.PIPE, stderr=subprocess.PIPE, text=True)
    for line in proc.stdout:
        if job.cancel.is_set():
            proc.kill()
            raise Cancelled()
        if line.startswith("out_time_us=") and duration:
            try:
                job.set(post_progress=min(100.0, int(line.split("=")[1]) / 1e4 / duration))
            except ValueError:
                pass
    err = proc.stderr.read()
    if proc.wait() != 0:
        raise RuntimeError(f"ffmpeg failed: {err.strip()[-300:]}")


def postprocess(job, parts, info):
    duration = info.get("duration") or 0
    name = re.sub(r'[\\/:*?"<>|\x00-\x1f]', "", info.get("title") or "video").strip()[:150] or "video"
    tag = job.label.split(" · ")[0]
    os.makedirs(DOWNLOAD_DIR, exist_ok=True)

    def dest(ext):
        # reserve a unique name right away so two jobs finishing together can't collide
        base = os.path.join(DOWNLOAD_DIR, f"{name} [{tag}]")
        path, n = f"{base}.{ext}", 1
        while True:
            try:
                open(path, "x").close()
                job.set(path=path)
                return path
            except FileExistsError:
                n += 1
                path = f"{base} ({n}).{ext}"

    src = parts[0].path
    if not FFMPEG:
        out = dest(os.path.splitext(src)[1].lstrip(".") or "bin")
        shutil.move(src, out)
        return out

    if job.mode == "audio":
        codec, ext, uses_br = AUDIO_CODECS[job.audio_format]
        out = dest(ext)
        src_codec = (parts[0].fmt.get("acodec") or "")
        if job.audio_format == "m4a" and src_codec.startswith("mp4a"):
            args = ["-i", src, "-vn", "-c:a", "copy", out]
        else:
            args = ["-i", src, "-vn", "-c:a", codec] + \
                   (["-b:a", f"{job.audio_bitrate}k"] if uses_br else []) + [out]
        _ffmpeg(job, args, duration, f"Converting to {job.audio_format.upper()}")
    elif job.mode == "video":
        out = dest("mp4")
        _ffmpeg(job, ["-i", src, "-map", "0:v:0", "-c", "copy", "-an", out], duration,
                "Removing the audio track")
    elif len(parts) == 2:
        v = next(p for p in parts if p.kind == "video").path
        a = next(p for p in parts if p.kind == "audio").path
        out = dest("mp4")
        _ffmpeg(job, ["-i", v, "-i", a, "-map", "0:v:0", "-map", "1:a:0", "-c", "copy", out],
                duration, "Joining video and audio")
    else:
        out = dest("mp4")
        _ffmpeg(job, ["-i", src, "-c", "copy", out], duration, "Finishing")
    return out


# --------------------------------------------------------------------- desktop helpers
def reveal(path):
    system = platform.system()
    if system == "Darwin":
        subprocess.Popen(["open", "-R", path])
    elif system == "Windows":
        subprocess.Popen(["explorer", "/select,", path])
    else:
        subprocess.Popen(["xdg-open", os.path.dirname(path)])


def open_file(path):
    system = platform.system()
    if system == "Darwin":
        subprocess.Popen(["open", path])
    elif system == "Windows":
        os.startfile(path)  # noqa
    else:
        subprocess.Popen(["xdg-open", path])
