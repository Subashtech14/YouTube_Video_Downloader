import hmac
import os
import secrets
import socket
import sys
import threading
import webbrowser
from urllib.parse import urlparse

from flask import Flask, jsonify, render_template, request, send_file

import engine

LAN = "--lan" in sys.argv or os.environ.get("ANYSAVE_LAN") == "1"
HOST = os.environ.get("HOST", "0.0.0.0" if LAN else "127.0.0.1")
TOKEN = os.environ.get("ANYSAVE_TOKEN") or (secrets.token_hex(3).upper() if HOST != "127.0.0.1" else "")

app = Flask(__name__)
manager = engine.Manager()


def bad(msg, code=400):
    return jsonify(error=msg), code


@app.before_request
def require_code_from_other_devices():
    if not request.path.startswith("/api/") or not TOKEN:
        return None
    if request.remote_addr in ("127.0.0.1", "::1"):
        return None
    given = request.headers.get("X-AnySave-Token") or request.args.get("token") or ""
    if not hmac.compare_digest(given.strip().upper(), TOKEN.upper()):
        return bad("Wrong or missing access code. It's shown in the terminal where AnySave runs.", 401)
    return None


def free_port(start):
    for port in range(start, start + 20):
        with socket.socket(socket.AF_INET, socket.SOCK_STREAM) as s:
            try:
                s.bind((HOST, port))
                return port
            except OSError:
                print(f"Port {port} is in use by another program, trying {port + 1}")
    return start


def lan_ip():
    try:
        with socket.socket(socket.AF_INET, socket.SOCK_DGRAM) as s:
            s.connect(("10.255.255.255", 1))
            return s.getsockname()[0]
    except OSError:
        return "your-computer-ip"


def link_problem(url):
    if not url:
        return "Please paste a link."
    p = urlparse(url)
    if p.scheme not in ("http", "https") or not p.netloc:
        return "Please paste a full link that starts with http:// or https://"
    host = p.netloc.lower().split(":")[0]
    if host == "wa.me" or host.endswith((".whatsapp.com", ".wa.me")) or host == "whatsapp.com":
        return ("WhatsApp videos live inside your chats and have no public link, so they can't be "
                "downloaded from a URL. In WhatsApp, open the video and use Save or Share instead.")
    return None


@app.get("/")
def index():
    return render_template("index.html")


@app.get("/api/status")
def api_status():
    return jsonify(app="AnySave", api=1, ffmpeg=bool(engine.FFMPEG), download_dir=engine.DOWNLOAD_DIR,
                   max_active=engine.MAX_ACTIVE_JOBS)


@app.post("/api/info")
def api_info():
    body = request.json or {}
    url = (body.get("url") or "").strip()
    if link_problem(url):
        return bad(link_problem(url))
    try:
        return jsonify(engine.video_info(url, playlist=bool(body.get("playlist"))))
    except Exception as e:
        return bad(engine.clean_error(e))


@app.post("/api/download")
def api_download():
    d = request.json or {}
    url = (d.get("url") or "").strip()
    mode = d.get("mode", "both")
    if link_problem(url):
        return bad(link_problem(url))
    if mode not in ("both", "video", "audio"):
        return bad("Unknown mode.")
    try:
        height = int(d["height"]) if d.get("height") else None
        connections = max(1, min(32, int(d.get("connections", 16))))
    except (TypeError, ValueError):
        return bad("Bad quality or connection value.")
    audio_format = d.get("audio_format", "mp3")
    audio_bitrate = str(d.get("audio_bitrate", "192"))
    if audio_format not in engine.AUDIO_CODECS or audio_bitrate not in {"64", "128", "192", "256", "320"}:
        return bad("Bad audio options.")
    job = manager.submit({
        "url": url, "mode": mode, "height": height, "compat": bool(d.get("compat", True)),
        "audio_format": audio_format, "audio_bitrate": audio_bitrate,
        "connections": connections, "title": d.get("title"), "thumbnail": d.get("thumbnail"),
    })
    return jsonify(job.snapshot())


@app.get("/api/jobs")
def api_jobs():
    return jsonify([j.snapshot() for j in reversed(list(manager.jobs.values()))])


def _job_or_404(job_id):
    job = manager.get(job_id)
    if not job:
        return None, bad("Unknown download.", 404)
    return job, None


@app.post("/api/jobs/<job_id>/cancel")
def api_cancel(job_id):
    job, err = _job_or_404(job_id)
    if err:
        return err
    job.cancel.set()
    return jsonify(ok=True)


@app.delete("/api/jobs/<job_id>")
def api_remove(job_id):
    return jsonify(ok=manager.remove(job_id))


@app.post("/api/jobs/clear")
def api_clear():
    for jid in list(manager.jobs):
        manager.remove(jid)
    return jsonify(ok=True)


@app.post("/api/jobs/<job_id>/<action>")
def api_action(job_id, action):
    job, err = _job_or_404(job_id)
    if err:
        return err
    if job.status != "done" or not job.path or not os.path.exists(job.path):
        return bad("The file is not there any more.", 404)
    if request.remote_addr not in ("127.0.0.1", "::1"):
        return bad("Open and Show in Finder only work on the computer running AnySave.", 403)
    if action == "reveal":
        engine.reveal(job.path)
    elif action == "open":
        engine.open_file(job.path)
    else:
        return bad("Unknown action.", 404)
    return jsonify(ok=True)


@app.get("/api/jobs/<job_id>/file")
def api_file(job_id):
    job, err = _job_or_404(job_id)
    if err:
        return err
    if job.status != "done" or not job.path or not os.path.exists(job.path):
        return bad("File not ready.", 404)
    return send_file(job.path, as_attachment=True, download_name=job.filename)


if __name__ == "__main__":
    port = free_port(int(os.environ.get("PORT", 8750)))
    if not engine.FFMPEG:
        print("WARNING: ffmpeg not found. High quality video and audio conversion "
              "will not work. See README.md.", file=sys.stderr)
    print(f"Files are saved in: {engine.DOWNLOAD_DIR}")
    print(f"Open http://127.0.0.1:{port} in your browser")
    if HOST != "127.0.0.1":
        print()
        print("Phone apps / other devices on your Wi-Fi:")
        print(f"  Server address: http://{lan_ip()}:{port}")
        print(f"  Access code:    {TOKEN}")
        print(f"  Browser link:   http://{lan_ip()}:{port}/?token={TOKEN}")
        print()
    if os.environ.get("NO_BROWSER") != "1":
        threading.Timer(1.0, lambda: webbrowser.open(f"http://127.0.0.1:{port}")).start()
    app.run(host=HOST, port=port, debug=False, threaded=True)
