# AnySave for iPhone and iPad

SwiftUI app with the same features as the web app: a tab for each site (All sites, Instagram,
Facebook, TikTok, X, Bluesky, LinkedIn, Reddit, Pinterest, YouTube, WhatsApp), paste a link,
preview, choose quality / video only / audio format and bitrate, and a live downloads queue.

## How it works

iOS doesn't allow apps to run yt-dlp and ffmpeg, so the app uses your Mac as its download engine:

1. On your Mac, run the web app in LAN mode: `cd ../web && ./start.sh --lan`.
   It prints a **server address** (like `http://192.168.1.20:8750`) and an **access code**.
2. In the app, tap the gear, enter both, tap **Test connection**, then **Save**.
   The first time, iOS asks to allow local network access: tap Allow.
3. Download as usual. The Mac does the fast parallel download, and the finished file is copied
   to the iPhone, in the **Files** app under **On My iPhone › AnySave**. From a finished download
   you can also **Save to Photos** or share it.

The iPhone and Mac need to be on the same Wi-Fi.

## Extras on iOS

- **Paste** button (no clipboard permission prompt) puts the link in and fetches it.
- **Share sheet shortcut:** iOS apps can only appear in the share sheet with an extension, so the
  simple route is a Shortcut. In the Shortcuts app: New Shortcut → Show in Share Sheet, receive
  URLs → add **Open URLs** with `anysave://open?url=` followed by *Shortcut Input*. Then tap Share
  in Instagram, TikTok and so on, and pick that shortcut.
- Downloads started on the phone are saved to the phone automatically (turn this off in settings).

## Build and install

You need a Mac with Xcode 15 or newer.

```bash
brew install xcodegen
cd ios
xcodegen            # creates AnySave.xcodeproj from project.yml
open AnySave.xcodeproj
```

In Xcode: select the **AnySave** target → **Signing & Capabilities** → pick your Apple ID team
(a free account works for your own phone). Plug in your iPhone and press **Run**.
A free Apple ID signs the app for 7 days; just run it again from Xcode after that.

Requires iOS 16 or newer.

## Files

```
AnySave/
  AnySaveApp.swift   app entry, anysave:// links
  ContentView.swift  tabs, picker, downloads list
  SettingsView.swift server address, access code, options
  AppModel.swift     state, polling the queue, saving files
  API.swift          talks to the AnySave server
  Sites.swift        the site tabs and link matching
project.yml          XcodeGen project definition
```
