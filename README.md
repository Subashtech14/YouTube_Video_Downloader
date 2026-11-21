# YouTube Downloader

A small web app that runs on your own computer. Paste a YouTube link, pick a quality, and choose:

- **Video + Audio**: one MP4 file at the quality you pick (up to 4K/8K if the video has it)
- **Video only**: MP4 with no sound track
- **Audio only**: MP3, M4A, Opus, WAV or FLAC, with a bitrate choice

## Why it's fast

YouTube slows down each single connection. This app splits every file into small pieces (1 to 8 MB) and downloads many pieces at the same time over separate connections, the way a torrent client or download manager does. Each piece is written straight into place, failed pieces are retried, and the Downloads list shows a live map of every piece (green done, orange downloading, grey waiting).

You can set the number of connections (1 to 32, default 16) from the settings button in the top right. If a stream can't be split this way, the app falls back to yt-dlp's own downloader automatically.

It is built on [yt-dlp](https://github.com/yt-dlp/yt-dlp) (does the downloading) and [ffmpeg](https://ffmpeg.org) (merges video and audio, converts audio).

> **Please only download content you own or have the rights to download.** Downloading videos may be against YouTube's Terms of Service and copyright law where you live. You are responsible for how you use this tool.

## Setup (one time)

1. **Install Python 3.9 or newer** from https://www.python.org/downloads/ (on Windows, tick "Add Python to PATH").
2. **Install ffmpeg**
   - Windows: `winget install Gyan.FFmpeg` (then open a new terminal)
   - macOS: `brew install ffmpeg`
   - Linux: `sudo apt install ffmpeg`
3. **Install the Python packages** in this folder:
   ```
   pip install -r requirements.txt
   ```

## Run it

```
python app.py
```

Your browser opens at http://127.0.0.1:5000. Or double-click `start.bat` (Windows) / run `./start.sh` (macOS, Linux).

Finished files are saved to `~/Downloads/YouTube Downloader`. Each finished download has buttons to open the file or show it in Finder / Explorer. You can queue several downloads; two run at a time.

## Tips

- **"Sign in to confirm you're not a bot" or a download suddenly stops working:** YouTube changes often. Update yt-dlp with `pip install -U yt-dlp`.
- **Playlist links:** only the single video in the link is downloaded.
- **Without ffmpeg**, Video + Audio is limited to low quality (usually 360p) and audio can only be saved as M4A.
- **Video won't play in QuickTime?** Leave "Most compatible (H.264 MP4)" switched on. YouTube only offers H.264 up to 1080p; 1440p and 4K use VP9 or AV1, which play in VLC, IINA, Chrome and most modern players.
- Settings via environment variables: `PORT` (default 5000), `DOWNLOAD_DIR` (default `~/Downloads/YouTube Downloader`), `MAX_ACTIVE_JOBS` (default 2), `NO_BROWSER=1` to not open a browser.
- The app only listens on your own computer (127.0.0.1), not your network.
