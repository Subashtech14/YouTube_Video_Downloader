package app.anysave

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import coil.compose.AsyncImage

class ShareReceiverActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val text = intent?.getStringExtra(Intent.EXTRA_TEXT) ?: intent?.dataString
        val link = firstLink(text)
        when {
            link == null -> { toast("AnySave: no link found in what you shared"); finish(); return }
            siteForUrl(link)?.id == "whatsapp" -> {
                toast("WhatsApp chat videos have no public link. Use the Status saver in AnySave for statuses.")
                finish(); return
            }
        }
        val prefs = getSharedPreferences("anysave", Context.MODE_PRIVATE)
        if (prefs.getBoolean("shareRemember", false)) {
            start(link!!, remembered(this), null)
            return
        }
        val site = siteForUrl(link!!) ?: siteById("all")
        setContent {
            AnySaveTheme(site.color) {
                SharePopup(link, onCancel = { finish() }, onDownload = { opts, remember, info, entries ->
                    if (remember) save(this, opts)
                    if (entries != null) startMany(entries, opts) else start(link, opts, info)
                })
            }
        }
    }

    private fun toast(msg: String) = Toast.makeText(this, msg, Toast.LENGTH_LONG).show()

    private fun start(link: String, opts: DownloadOptions, info: VideoInfo?) {
        Downloads.add(this, info?.url ?: link, opts, info?.title, info?.thumbnail)
        Toast.makeText(this, "AnySave: downloading ${Engine.label(opts)}. You'll get a notification when it's saved.", Toast.LENGTH_SHORT).show()
        finish()
    }

    private fun startMany(entries: List<PlaylistEntry>, opts: DownloadOptions) {
        Downloads.addAll(this, entries, opts)
        Toast.makeText(this, "AnySave: queued ${entries.size} videos (${Engine.label(opts)}). You'll get a notification as each one is saved.", Toast.LENGTH_SHORT).show()
        finish()
    }

    companion object {
        fun remembered(context: Context): DownloadOptions {
            val p = context.getSharedPreferences("anysave", Context.MODE_PRIVATE)
            return DownloadOptions(
                mode = p.getString("shareMode", "both") ?: "both",
                height = p.getInt("shareHeight", 1080).takeIf { it > 0 },
                compat = true,
                audioFormat = p.getString("shareAudio", "mp3") ?: "mp3",
                audioBitrate = p.getString("shareBitrate", "192") ?: "192",
            )
        }

        fun save(context: Context, o: DownloadOptions) {
            context.getSharedPreferences("anysave", Context.MODE_PRIVATE).edit()
                .putBoolean("shareRemember", true)
                .putString("shareMode", o.mode).putInt("shareHeight", o.height ?: 0)
                .putString("shareAudio", o.audioFormat).putString("shareBitrate", o.audioBitrate)
                .apply()
        }

        fun forget(context: Context) =
            context.getSharedPreferences("anysave", Context.MODE_PRIVATE).edit().putBoolean("shareRemember", false).apply()
    }
}

@OptIn(ExperimentalLayoutApi::class, ExperimentalMaterial3Api::class)
@Composable
fun SharePopup(
    link: String,
    onCancel: () -> Unit,
    onDownload: (DownloadOptions, Boolean, VideoInfo?, List<PlaylistEntry>?) -> Unit,
) {
    var info by remember { mutableStateOf<VideoInfo?>(null) }
    var whole by remember { mutableStateOf(false) }
    var picked by remember { mutableStateOf<Set<Int>>(emptySet()) }
    var error by remember { mutableStateOf<String?>(null) }
    var kind by remember { mutableStateOf("video") }
    var height by remember { mutableStateOf<Int?>(1080) }
    var audioFormat by remember { mutableStateOf("mp3") }
    var bitrate by remember { mutableStateOf("192") }
    var rememberChoice by remember { mutableStateOf(false) }

    LaunchedEffect(link, whole) {
        info = null; error = null
        runCatching { Engine.info(link, playlist = whole) }
            .onSuccess { i ->
                info = i
                picked = i.entries.indices.toSet()
                height = i.qualities.firstOrNull { it.height <= 1080 }?.height ?: i.qualities.firstOrNull()?.height
            }
            .onFailure { error = it.message }
    }

    Dialog(onDismissRequest = onCancel) {
        Surface(shape = RoundedCornerShape(24.dp), tonalElevation = 6.dp) {
            Column(Modifier.padding(20.dp).verticalScroll(rememberScrollState())) {
                Text("Download with AnySave", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold)
                Spacer(Modifier.height(12.dp))

                Row(verticalAlignment = Alignment.CenterVertically) {
                    val i = info
                    if (i?.thumbnail != null) {
                        AsyncImage(i.thumbnail, null, Modifier.width(96.dp).aspectRatio(16f / 9f).clip(RoundedCornerShape(8.dp)),
                            contentScale = ContentScale.Crop)
                        Spacer(Modifier.width(10.dp))
                    }
                    Column(Modifier.weight(1f)) {
                        when {
                            i != null -> {
                                Text(i.title, maxLines = 2, overflow = TextOverflow.Ellipsis, fontWeight = FontWeight.Medium)
                                Text(listOfNotNull(i.site, if (i.isPlaylist) "Playlist · ${i.entries.size} videos" else null,
                                    formatDuration(i.duration).ifBlank { null }).joinToString(" · "),
                                    fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                            error != null -> Text(error!!, color = MaterialTheme.colorScheme.error, fontSize = 14.sp)
                            else -> {
                                Text(link, maxLines = 1, overflow = TextOverflow.Ellipsis, fontSize = 13.sp)
                                Spacer(Modifier.height(6.dp))
                                LinearProgressIndicator(Modifier.fillMaxWidth())
                                Text("Checking available qualities…", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                        }
                    }
                }
                info?.let { i ->
                    if (!i.isPlaylist && i.playlistUrl != null) {
                        Spacer(Modifier.height(12.dp))
                        PlaylistBanner("This video is part of a playlist.", "Whole playlist") { whole = true }
                    }
                    if (whole) {
                        Spacer(Modifier.height(12.dp))
                        PlaylistBanner("Showing the whole playlist.", "Just this video") { whole = false }
                    }
                    if (i.isPlaylist) {
                        Spacer(Modifier.height(12.dp))
                        PlaylistPicker(i.entries, picked, maxHeight = 220.dp,
                            onToggle = { idx -> picked = if (idx in picked) picked - idx else picked + idx },
                            onAll = { all -> picked = if (all) i.entries.indices.toSet() else emptySet() })
                    }
                }
                Spacer(Modifier.height(16.dp))

                SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
                    listOf("video" to "Video", "audio" to "Audio only").forEachIndexed { idx, (id, label) ->
                        SegmentedButton(selected = kind == id, onClick = { kind = id },
                            shape = SegmentedButtonDefaults.itemShape(idx, 2)) { Text(label) }
                    }
                }
                Spacer(Modifier.height(12.dp))

                if (kind == "video") {
                    Text("Quality", style = MaterialTheme.typography.labelLarge)
                    val options: List<Pair<Int?, String>> = info?.qualities?.takeIf { it.isNotEmpty() }
                        ?.map { q ->
                            val size = (q.sizes["both_compat"] ?: q.sizes["both"])?.let { " · ~" + formatBytes(it.toDouble()) } ?: ""
                            q.height as Int? to (q.label + (if (q.tag.isNotBlank()) " ${q.tag}" else "") + size)
                        }
                        ?: listOf(null to "Best", 1080 to "1080p", 720 to "720p", 480 to "480p", 360 to "360p")
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        options.forEach { (h, label) ->
                            FilterChip(selected = height == h, onClick = { height = h }, label = { Text(label) })
                        }
                    }
                } else {
                    Text("Format", style = MaterialTheme.typography.labelLarge)
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        listOf("mp3", "m4a", "opus", "flac").forEach { f ->
                            FilterChip(selected = audioFormat == f, onClick = { audioFormat = f }, label = { Text(f.uppercase()) })
                        }
                    }
                    if (audioFormat != "flac") {
                        Text("Bitrate (kbps)", style = MaterialTheme.typography.labelLarge)
                        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            listOf("320", "256", "192", "128").forEach { b ->
                                FilterChip(selected = bitrate == b, onClick = { bitrate = b }, label = { Text(b) })
                            }
                        }
                    }
                }

                Spacer(Modifier.height(8.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(rememberChoice, { rememberChoice = it })
                    Column {
                        Text("Remember my choice", fontSize = 14.sp)
                        Text("Next shares download straight away. Change it in AnySave settings.",
                            fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
                Spacer(Modifier.height(12.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(onClick = onCancel) { Text("Cancel") }
                    val playlist = info?.takeIf { it.isPlaylist }
                    Button(modifier = Modifier.weight(1f), enabled = error == null && (playlist == null || picked.isNotEmpty()), onClick = {
                        val opts = if (kind == "audio") DownloadOptions(mode = "audio", audioFormat = audioFormat, audioBitrate = bitrate)
                                   else DownloadOptions(mode = "both", height = height, compat = true)
                        onDownload(opts, rememberChoice, info, playlist?.entries?.filterIndexed { idx, _ -> idx in picked })
                    }) { Text(if (playlist != null) "Download ${picked.size}" else "Download") }
                }
            }
        }
    }
}
