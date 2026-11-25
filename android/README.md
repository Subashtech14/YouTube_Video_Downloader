# AnySave for Android

A standalone Android app that downloads videos **on the phone itself**. It doesn't need a computer
or server. Same features as the web app: a tab for each site (All sites, Instagram, Facebook,
TikTok, X, Bluesky, LinkedIn, Reddit, Pinterest, YouTube, WhatsApp), paste a link, preview,
choose quality / video only / audio format and bitrate, and a live downloads queue.

## Share sheet

In Instagram, TikTok, YouTube, X, Facebook, Reddit and other apps, tap **Share › AnySave**. A popup
opens over the app you're in. It shows the video, asks **Video or Audio only**, then lists the
qualities the video really has (or audio format and bitrate). Tap **Download** and it runs in the
background with a notification ("Saved · tap to play").

Tick **Remember my choice** and later shares download straight away without the popup. To get
the popup back, go to Settings › Ask me every time again.

## Playlists

Share or paste a YouTube playlist link and AnySave lists its videos, all ticked. Untick the ones
you don't want (or use All / None), pick Video or Audio and a quality once, and each video is
queued as its own download. Because videos in a playlist differ, the quality is a cap: "1080p"
means 1080p, or the best below it for videos that don't have 1080p.

A link to one video opened inside a playlist (`watch?v=…&list=…`) downloads just that video,
with a **Whole playlist** button to switch. Private and deleted videos are skipped, and YouTube
"Mix" lists are treated as a single video because they never end. With **Remember my choice** on,
a shared playlist link queues every video straight away.

## WhatsApp Status saver

The WhatsApp tab saves statuses you've already viewed in WhatsApp or WhatsApp Business
(they disappear after 24 hours):

1. Open the statuses in WhatsApp first.
2. In AnySave's WhatsApp tab, tap **Give access**. Android opens the hidden `.Statuses` folder;
   tap **Use this folder**, then **Allow**. This happens only once.
3. Pick statuses (Videos / Photos filters, Select all) and tap **Save**. They go to
   Movies/AnySave/Status and Pictures/AnySave/Status, so they show in your Gallery.

Videos sent in chats have no public link, so save those from the chat (⋮ › Save).

## Where files go

- Videos: **Movies/AnySave**. They show up in your Gallery / Google Photos.
- Audio: **Music/AnySave**.

Each finished download has **Open** and **Share** buttons.

## How it works

The app bundles [yt-dlp](https://github.com/yt-dlp/yt-dlp) with its own Python and ffmpeg through
[youtubedl-android](https://github.com/JunkFood02/youtubedl-android). It picks streams the same
way the web app does, joins video and audio, and converts audio. Downloads keep running in the
background through a foreground service. yt-dlp updates itself once a day because sites change
often. You can also tap **Settings › Update now**.

The first launch takes a few seconds while the engine unpacks.

## Build and install

1. Install [Android Studio](https://developer.android.com/studio). The build runs on Gradle JDK 17
   or newer, including Java 25.
2. **File > Open** and pick this `android` folder. Let Gradle sync. The first sync downloads Gradle 9.9.2.
3. Plug in your phone with USB debugging on and press **Run**.

The app is large (about 150 MB for all phone types) because it carries Python and ffmpeg. Android
Studio's Run button only installs the part for your phone.

Requires Android 8.0 (API 26) or newer.

## Files

```
app/src/main/java/app/anysave/
  MainActivity.kt           screens (tabs, picker, downloads, settings)
  MainViewModel.kt          screen state
  Engine.kt                 yt-dlp on the phone: video info, stream choice, download
  Downloads.kt              the queue, saving to Movies/Music
  DownloadService.kt        keeps downloads going in the background, notifications
  ShareReceiverActivity.kt  Share › AnySave popup (Video/Audio, quality, remember my choice)
  StatusSaver.kt            WhatsApp Status saver
  Playlist.kt               playlist video list (select all / none) and the playlist banner
  Sites.kt                  the site tabs and link matching
```

Only download videos you own or have permission to download. Private posts, login-only content and
DRM-protected videos are not supported.
