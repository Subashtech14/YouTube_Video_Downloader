package app.anysave

data class Quality(
    val height: Int,
    val label: String,
    val tag: String,
    val hdr: Boolean,
    val hasH264: Boolean,
    val sizes: Map<String, Long>,
)

data class VideoInfo(
    val url: String,
    val title: String,
    val site: String?,
    val uploader: String?,
    val duration: Double?,
    val thumbnail: String?,
    val qualities: List<Quality>,
    val entries: List<PlaylistEntry> = emptyList(),
    val playlistUrl: String? = null,
) {
    val isPlaylist get() = entries.isNotEmpty()
}

data class PlaylistEntry(
    val url: String,
    val title: String,
    val duration: Double?,
    val thumbnail: String?,
)

val GENERIC_QUALITIES = listOf(2160 to "4K", 1440 to "2K", 1080 to "Full HD", 720 to "HD", 480 to "", 360 to "").map { (h, tag) ->
    Quality(height = h, label = "${h}p", tag = tag, hdr = false, hasH264 = true, sizes = emptyMap())
}

data class DownloadOptions(
    val mode: String = "both",
    val height: Int? = null,
    val compat: Boolean = true,
    val audioFormat: String = "mp3",
    val audioBitrate: String = "192",
)

data class Job(
    val id: String,
    val url: String,
    val options: DownloadOptions,
    val title: String,
    val thumbnail: String? = null,
    val label: String = "",
    val status: String = "queued",
    val progress: Float? = null,
    val eta: Long? = null,
    val message: String = "Waiting",
    val error: String? = null,
    val fileName: String? = null,
    val savedUri: String? = null,
    val mime: String? = null,
) {
    val active get() = status in setOf("queued", "preparing", "downloading", "processing")
}

fun formatBytes(n: Double): String {
    if (n <= 0) return "0 B"
    val units = listOf("B", "KB", "MB", "GB", "TB")
    var v = n
    var i = 0
    while (v >= 1024 && i < units.size - 1) { v /= 1024; i++ }
    return if (i <= 1) "%.0f %s".format(v, units[i]) else "%.1f %s".format(v, units[i])
}

fun formatDuration(s: Double?): String {
    if (s == null || s <= 0) return ""
    val t = s.toLong()
    val h = t / 3600; val m = t % 3600 / 60; val x = t % 60
    return if (h > 0) "%d:%02d:%02d".format(h, m, x) else "%d:%02d".format(m, x)
}
