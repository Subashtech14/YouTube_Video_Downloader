# AnySave

A small web app that runs on your own computer for downloading videos from any site. Pick a site tab
(or use **All sites**), paste a link, pick a quality, and choose:

- **Video + Audio**: one MP4 file at the quality you pick (up to 4K/8K if the video has it)
- **Video only**: MP4 with no sound track
- **Audio only**: MP3, M4A, Opus, WAV or FLAC, with a bitrate choice

## Supported sites

Each site has its own tab with copy-link tips. Pasting a link into **All sites** jumps to the
matching tab, and pasting a link into the wrong tab offers to switch.

| Site | Notes |
|---|---|
| Instagram | Reels and video posts from public accounts. Carousels download the first video. Stories and private accounts need a login and won't work |
| Facebook | Public videos, Reels and Watch, including fb.watch links |
| TikTok | Videos, including vm.tiktok.com short links |
| X / Twitter | Videos and GIFs in public posts |
| Bluesky | Videos in posts |
| LinkedIn | Videos in public posts (not LinkedIn Learning) |
| Reddit | Video posts; sound is stored separately and joined back with ffmpeg |
| Pinterest | Video pins and Idea pins, including pin.it links |
| YouTube | Videos and Shorts, up to 4K/8K |
| 1,000+ more | Anything [yt-dlp supports](https://github.com/yt-dlp/yt-dlp/blob/master/supportedsites.md) via All sites |
| WhatsApp | **Not possible.** WhatsApp videos are encrypted inside your chats and have no public link. The WhatsApp tab explains how to save them from the app |

Private posts, login-only content and DRM-protected streams (Netflix, Prime Video, Spotify and similar) are not supported.

## Why it's fast

Many sites (YouTube especially) slow down each single connection. This app splits every file into small pieces (1 to 8 MB) and downloads many pieces at the same time over separate connections, the way a torrent client or download manager does. Each piece is written straight into place, failed pieces are retried, and the Downloads list shows a live map of every piece (green done, orange downloading, grey waiting).

You can set the number of connections (1 to 32, default 16) from the settings button in the top right. If a stream can't be split this way, the app falls back to yt-dlp's own downloader automatically.

It is built on [yt-dlp](https://github.com/yt-dlp/yt-dlp) (does the downloading) and [ffmpeg](https://ffmpeg.org) (merges video and audio, converts audio).

> **Please only download content you own or have the rights to download.** Downloading videos may be against a site's Terms of Service and copyright law where you live. You are responsible for how you use this tool.

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

Your browser opens at http://127.0.0.1:8750. Or double-click `start.bat` (Windows) / run `./start.sh` (macOS, Linux).

Finished files are saved to `~/Downloads/AnySave`. Each finished download has buttons to open the file or show it in Finder / Explorer. You can queue several downloads; two run at a time.

## Use it from your phone or another device

```
./start.sh --lan
```

This lets the AnySave [Android](../android/README.md) and [iOS](../ios/README.md) apps, or a browser
on another device on your Wi-Fi, use this computer. It prints:

- **Server address**, like `http://192.168.1.20:8750`, to enter in the phone app
- **Access code**, a 6-character code other devices must send (your own computer never needs it)
- **Browser link** with the code included, to open AnySave in a phone's browser

From another device, finished downloads get a **Save to this device** button instead of
Open / Show in Finder. Set your own fixed code with `ANYSAVE_TOKEN=yourcode ./start.sh --lan`.
macOS may ask whether Python can accept incoming connections: click Allow.

## Playlists

Paste a YouTube playlist link and AnySave lists its videos with checkboxes and Select all /
Select none. Pick Video or Audio and a quality once and click **Download N**: each video becomes
its own download in the queue. The quality is a cap ("1080p or the best below it"), because
videos in one playlist can differ. A link to one video inside a playlist (`watch?v=…&list=…`)
shows that video with a **Download the whole playlist** button. Private and deleted videos are
skipped.

## Tips

- **"403 Forbidden" in the browser:** another program is using that port (on a Mac, port 5000 is
  AirPlay Receiver). AnySave uses 8750 and moves to the next free port by itself; open the address
  it prints in the terminal.

- **A site suddenly stops working (or "Sign in to confirm you're not a bot"):** sites change often. Update yt-dlp with `pip install -U yt-dlp`.
- **Playlist links:** only the single video in the link is downloaded.
- **Without ffmpeg**, Video + Audio is limited to low quality (usually 360p) and audio can only be saved as M4A.
- **Video won't play in QuickTime?** Leave "Most compatible (H.264 MP4)" switched on. YouTube only offers H.264 up to 1080p; 1440p and 4K use VP9 or AV1, which play in VLC, IINA, Chrome and most modern players.
- Settings via environment variables: `PORT` (default 8750), `HOST`, `ANYSAVE_TOKEN`, `DOWNLOAD_DIR` (default `~/Downloads/AnySave`), `MAX_ACTIVE_JOBS` (default 2), `NO_BROWSER=1` to not open a browser.
- The app only listens on your own computer (127.0.0.1), not your network.
