# AnySave

Download videos from Instagram, Facebook, TikTok, X, Bluesky, LinkedIn, Reddit, Pinterest,
YouTube and 1,000+ other sites, on your computer or your phone.

| Folder | What it is |
|---|---|
| [`web/`](web/README.md) | The main app: a local web app (Python, Flask, yt-dlp, ffmpeg) with fast multi-connection downloads. It is also the download server for the iPhone app |
| [`android/`](android/README.md) | Android app (Kotlin, Jetpack Compose). Downloads on the phone itself, Share › AnySave popup, WhatsApp Status saver |
| [`ios/`](ios/README.md) | iPhone and iPad app (SwiftUI) |

All three have the same features: a tab for each site, paste a link, preview, pick a quality or
audio format, and a live downloads queue. YouTube playlists list every video so you can pick
which ones to download (the web and Android apps). WhatsApp has a tab that explains why its videos can't
be downloaded from a link.

## Quick start

```bash
cd web
./start.sh          # computer only: opens http://127.0.0.1:8750
./start.sh --lan    # also lets the phone apps connect; prints the address and access code
```

On Windows, run `start.bat` (or `start.bat --lan`).

## How the phone apps work

- **Android** downloads on the phone itself (yt-dlp, Python and ffmpeg are bundled in the app). In
  any app, tap Share › **AnySave**: a popup asks Video or Audio and the quality, then it saves in the
  background. Its WhatsApp tab is a Status saver.
- **iPhone** apps can't run yt-dlp, so the iOS app sends the link to AnySave running on your
  computer (`./start.sh --lan`), which downloads it and copies the file to the phone. Both need to
  be on the same Wi-Fi.

Only download videos you own or have permission to download. Private posts, login-only content and
DRM-protected videos are not supported.
