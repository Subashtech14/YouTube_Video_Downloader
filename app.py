"""
YouTube Downloader - local web app built on yt-dlp + ffmpeg.

Run:  python app.py      then open http://127.0.0.1:5000
Only download content you own or have permission to download.
"""
import os
import sys
import threading
import webbrowser

from flask import Flask, jsonify, render_template, request, send_file

import engine

app = Flask(__name__)
manager = engine.Manager()


def bad(msg, code=400):
    return jsonify(error=msg), code


@app.get("/")
def index():
    return render_template("index.html")


@app.get("/api/status")
def api_status():
    return jsonify(ffmpeg=bool(engine.FFMPEG), download_dir=engine.DOWNLOAD_DIR,
                   max_active=engine.MAX_ACTIVE_JOBS)


@app.post("/api/info")
def api_info():
    url = (request.json or {}).get("url", "").strip()
    if not url:
        return bad("Please paste a link.")
    try:
        return jsonify(engine.video_info(url))
    except Exception as e:
        return bad(engine.clean_error(e))


@app.post("/api/download")
def api_download():
    d = request.json or {}
    url = (d.get("url") or "").strip()
    mode = d.get("mode", "both")
    if not url:
        return bad("Please paste a link.")
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
    port = int(os.environ.get("PORT", 5000))
    if not engine.FFMPEG:
        print("WARNING: ffmpeg not found. High quality video and audio conversion "
              "will not work. See README.md.", file=sys.stderr)
    print(f"Files are saved in: {engine.DOWNLOAD_DIR}")
    print(f"Open http://127.0.0.1:{port} in your browser")
    if os.environ.get("NO_BROWSER") != "1":
        threading.Timer(1.0, lambda: webbrowser.open(f"http://127.0.0.1:{port}")).start()
    app.run(host="127.0.0.1", port=port, debug=False, threaded=True)
