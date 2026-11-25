package app.anysave

import android.content.Context
import android.net.Uri
import com.yausername.ffmpeg.FFmpeg
import com.yausername.youtubedl_android.YoutubeDL
import com.yausername.youtubedl_android.YoutubeDLRequest
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

object Engine {
    private val ready = CompletableDeferred<Unit>()
    @Volatile var initError: String? = null
        private set

    fun start(context: Context) {
        Thread {
            try {
                YoutubeDL.getInstance().init(context)
                FFmpeg.getInstance().init(context)
                ready.complete(Unit)
            } catch (e: Throwable) {
                initError = e.message ?: e.toString()
                ready.completeExceptionally(e)
                return@Thread
            }
            maybeUpdate(context, force = false)
        }.start()
    }

    fun maybeUpdate(context: Context, force: Boolean): String {
        val prefs = context.getSharedPreferences("anysave", Context.MODE_PRIVATE)
        val last = prefs.getLong("ytdlpChecked", 0)
        if (!force && System.currentTimeMillis() - last < 24 * 3600_000L) return "Checked recently"
        return try {
            val status = YoutubeDL.getInstance().updateYoutubeDL(context, YoutubeDL.UpdateChannel.STABLE)
            prefs.edit().putLong("ytdlpChecked", System.currentTimeMillis()).apply()
            if (status == YoutubeDL.UpdateStatus.DONE) "Updated to ${version(context)}" else "Already up to date"
        } catch (e: Throwable) {
            "Update failed: ${e.message}"
        }
    }

    fun version(context: Context): String = YoutubeDL.getInstance().versionName(context) ?: "bundled"

    suspend fun awaitReady() {
        try {
            ready.await()
        } catch (e: Throwable) {
            throw IllegalStateException("The download engine couldn't start: ${initError ?: e.message}")
        }
    }

    private val MULTI_POST_SITES = listOf("instagram", "twitter", "facebook", "bluesky", "reddit", "tiktok", "pinterest", "linkedin")

    fun playlistUrlFor(url: String): String? {
        val u = runCatching { Uri.parse(url) }.getOrNull() ?: return null
        val host = u.host?.lowercase() ?: return null
        if (!host.endsWith("youtube.com") && !host.endsWith("youtu.be")) return null
        val list = runCatching { u.getQueryParameter("list") }.getOrNull()
        if (list.isNullOrBlank() || list.startsWith("RD") || (u.path ?: "").contains("/playlist")) return null
        return "https://www.youtube.com/playlist?list=$list"
    }

    private fun dumpJson(url: String, flat: Boolean, noPlaylist: Boolean): JSONObject {
        val req = YoutubeDLRequest(url).addOption("--dump-single-json").addOption("--no-warnings")
        if (flat) req.addOption("--flat-playlist")
        if (noPlaylist) req.addOption("--no-playlist")
        val out = try {
            YoutubeDL.getInstance().execute(req, null, null).out
        } catch (e: Throwable) {
            throw IllegalStateException(friendlyError(e))
        }
        return JSONObject(out)
    }

    suspend fun info(url: String, playlist: Boolean = false): VideoInfo = withContext(Dispatchers.IO) {
        awaitReady()
        val target = if (playlist) playlistUrlFor(url) ?: url else url
        var o = dumpJson(target, flat = true, noPlaylist = !playlist)
        if (o.optString("_type") == "playlist") {
            val key = o.optString("extractor_key").lowercase()
            if (MULTI_POST_SITES.none { key.contains(it) }) return@withContext parsePlaylist(target, o)
            val full = dumpJson(url, flat = false, noPlaylist = true)
            val entries = full.optJSONArray("entries")
            o = if (full.optString("_type") == "playlist")
                (0 until (entries?.length() ?: 0)).mapNotNull { entries!!.optJSONObject(it) }.firstOrNull()
                    ?: throw IllegalStateException("There's no video in this post.")
            else full
        }
        parseInfo(url, o).copy(playlistUrl = if (playlist) null else playlistUrlFor(url))
    }

    private fun str(o: JSONObject, key: String) = o.optString(key).takeIf { it.isNotBlank() && it != "null" }

    private fun thumb(o: JSONObject): String? = str(o, "thumbnail") ?: o.optJSONArray("thumbnails")?.let { a ->
        (a.length() - 1 downTo 0).mapNotNull { a.optJSONObject(it)?.let { t -> str(t, "url") } }.firstOrNull()
    }

    private fun parsePlaylist(url: String, o: JSONObject): VideoInfo {
        val arr = o.optJSONArray("entries") ?: JSONArray()
        val entries = (0 until arr.length()).mapNotNull { arr.optJSONObject(it) }.mapNotNull { e ->
            var link = str(e, "url") ?: str(e, "webpage_url") ?: return@mapNotNull null
            if (!link.startsWith("http") && (str(e, "ie_key") ?: "").lowercase().startsWith("youtube"))
                link = "https://www.youtube.com/watch?v=${str(e, "id") ?: link}"
            if (!link.startsWith("http")) return@mapNotNull null
            val title = str(e, "title") ?: link
            if (title == "[Private video]" || title == "[Deleted video]") return@mapNotNull null
            PlaylistEntry(link, title, e.optDouble("duration").takeIf { !it.isNaN() && it > 0 }, thumb(e))
        }
        if (entries.isEmpty()) throw IllegalStateException("This playlist has no videos that can be downloaded.")
        return VideoInfo(
            url = url,
            title = str(o, "title") ?: "Playlist",
            site = str(o, "extractor_key"),
            uploader = str(o, "uploader") ?: str(o, "channel"),
            duration = null,
            thumbnail = thumb(o) ?: entries.first().thumbnail,
            qualities = GENERIC_QUALITIES,
            entries = entries,
        )
    }

    private fun size(f: JSONObject?): Long? =
        f?.let { (it.optLong("filesize").takeIf { s -> s > 0 } ?: it.optLong("filesize_approx").takeIf { s -> s > 0 }) }

    private fun sum(a: Long?, b: Long?): Long? = ((a ?: 0) + (b ?: 0)).takeIf { it > 0 }

    private fun parseInfo(url: String, o: JSONObject): VideoInfo {
        val fa = o.optJSONArray("formats") ?: JSONArray()
        val formats = (0 until fa.length()).mapNotNull { fa.optJSONObject(it) }
        fun vc(f: JSONObject) = f.optString("vcodec", "none").ifBlank { "none" }
        fun ac(f: JSONObject) = f.optString("acodec", "none").ifBlank { "none" }
        val vids = formats.filter { vc(it) != "none" && it.optInt("height") > 0 }
        val auds = formats.filter { vc(it) == "none" && ac(it) != "none" }
        val bestA = auds.maxByOrNull { it.optDouble("abr", it.optDouble("tbr", 0.0)).let { v -> if (v.isNaN()) 0.0 else v } }
        val m4aA = auds.filter { it.optString("ext") == "m4a" }.maxByOrNull { it.optDouble("abr", 0.0).let { v -> if (v.isNaN()) 0.0 else v } } ?: bestA
        fun rank(f: JSONObject) = f.optDouble("fps", 0.0).let { if (it.isNaN()) 0.0 else it } * 1e6 + f.optDouble("tbr", 0.0).let { if (it.isNaN()) 0.0 else it }
        val tags = mapOf(4320 to "8K", 2160 to "4K", 1440 to "2K", 1080 to "Full HD", 720 to "HD")

        val qualities = vids.map { it.optInt("height") }.distinct().sortedDescending().map { h ->
            val atH = vids.filter { it.optInt("height") == h }
            val best = atH.maxBy { rank(it) }
            val h264 = atH.filter { vc(it).startsWith("avc") }.maxByOrNull { rank(it) }
            val fps = best.optDouble("fps", 0.0).let { if (it.isNaN()) 0 else it.toInt() }
            Quality(
                height = h,
                label = "${h}p" + if (fps > 30) "$fps" else "",
                tag = tags[h] ?: "",
                hdr = atH.any { it.optString("dynamic_range", "SDR").let { d -> d.isNotBlank() && d != "SDR" && d != "null" } },
                hasH264 = h264 != null,
                sizes = buildMap {
                    sum(size(best), size(bestA))?.let { put("both", it) }
                    size(best)?.let { put("video", it) }
                    sum(size(h264 ?: best), size(m4aA))?.let { put("both_compat", it) }
                    size(h264 ?: best)?.let { put("video_compat", it) }
                },
            )
        }
        return VideoInfo(
            url = o.optString("webpage_url").ifBlank { url },
            title = o.optString("title").ifBlank { "Untitled" },
            site = o.optString("extractor_key").ifBlank { null },
            uploader = (o.optString("uploader").ifBlank { o.optString("channel") }).ifBlank { null }?.takeIf { it != "null" },
            duration = o.optDouble("duration").takeIf { !it.isNaN() && it > 0 },
            thumbnail = o.optString("thumbnail").ifBlank { null }?.takeIf { it != "null" },
            qualities = qualities,
        )
    }

    fun formatSpec(o: DownloadOptions): String {
        val h = o.height?.let { "[height<=$it]" } ?: ""
        return when (o.mode) {
            "audio" -> if (o.audioFormat == "m4a") "ba[ext=m4a]/ba/b" else "ba/b"
            "video" -> (if (o.compat) "bv[vcodec^=avc]$h/" else "") + "bv$h/bv/b$h/b"
            else -> if (o.compat) "bv*[vcodec^=avc]$h+ba[ext=m4a]/bv*$h+ba[ext=m4a]/bv*$h+ba/b$h/b"
                    else "bv*$h+ba/b$h/b"
        }
    }

    fun label(o: DownloadOptions): String = when (o.mode) {
        "audio" -> o.audioFormat.uppercase() + if (o.audioFormat in setOf("wav", "flac")) "" else " ${o.audioBitrate}k"
        else -> (o.height?.let { "${it}p" } ?: "Best") + if (o.mode == "video") " · no sound" else ""
    }

    suspend fun download(
        job: Job,
        workDir: File,
        fragments: Int,
        onProgress: (Float?, Long?, String) -> Unit,
    ): File = withContext(Dispatchers.IO) {
        awaitReady()
        workDir.mkdirs()
        val o = job.options
        val tag = label(o).substringBefore(" ·")
        val req = YoutubeDLRequest(job.url)
            .addOption("--no-playlist")
            .addOption("--no-warnings")
            .addOption("--newline")
            .addOption("-f", formatSpec(o))
            .addOption("-o", File(workDir, "%(title).120B [$tag].%(ext)s").absolutePath)
            .addOption("--windows-filenames")
            .addOption("--concurrent-fragments", fragments.coerceIn(1, 16))
        if (o.mode == "audio") {
            req.addOption("-x").addOption("--audio-format", o.audioFormat)
            if (o.audioFormat !in setOf("wav", "flac")) req.addOption("--audio-quality", "${o.audioBitrate}K")
        } else {
            req.addOption("--merge-output-format", "mp4").addOption("--remux-video", "mp4")
        }
        try {
            YoutubeDL.getInstance().execute(req, job.id) { progress, eta, line ->
                val stage = when {
                    line.contains("[Merger]") || line.contains("[VideoRemuxer]") -> "Joining video and audio"
                    line.contains("[ExtractAudio]") -> "Converting to ${o.audioFormat.uppercase()}"
                    else -> "Downloading"
                }
                onProgress(if (progress >= 0) progress / 100f else null, eta.takeIf { it >= 0 }, stage)
            }
        } catch (e: YoutubeDL.CanceledException) {
            throw e
        } catch (e: Throwable) {
            throw IllegalStateException(friendlyError(e))
        }
        workDir.listFiles()?.filter { it.isFile && !it.name.endsWith(".part") && !it.name.endsWith(".ytdl") }
            ?.maxByOrNull { it.length() }
            ?: throw IllegalStateException("The download finished but no file was produced.")
    }

    fun cancel(jobId: String) {
        runCatching { YoutubeDL.getInstance().destroyProcessById(jobId) }
    }

    fun friendlyError(e: Throwable): String {
        val msg = (e.message ?: e.toString()).lines().lastOrNull { it.contains("ERROR") } ?: (e.message ?: e.toString())
        val low = msg.lowercase()
        return when {
            "unsupported url" in low -> "This site isn't supported, or the link doesn't point to a video."
            listOf("login", "log in", "sign in", "private", "cookies", "rate-limit").any { it in low } ->
                "This video is private or needs a login, so it can't be downloaded here."
            "drm" in low -> "This video is DRM-protected and can't be downloaded."
            "no video" in low -> "There's no video in this post."
            "unable to download" in low || "network" in low || "resolve" in low -> "Network problem. Check your connection and try again."
            else -> msg.replace("ERROR: ", "").take(300)
        }
    }
}
