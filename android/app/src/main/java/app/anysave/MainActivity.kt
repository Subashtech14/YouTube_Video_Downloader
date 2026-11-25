package app.anysave

import android.Manifest
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.core.content.ContextCompat
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage

class MainActivity : ComponentActivity() {
    private val vm: MainViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        if (savedInstanceState == null) handleIntent(intent)
        if (Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            registerForActivityResult(ActivityResultContracts.RequestPermission()) {}.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
        setContent { AnySaveTheme(vm.tab.color) { AnySaveApp(vm) } }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleIntent(intent)
    }

    private fun handleIntent(intent: Intent?) {
        if (intent?.action == Intent.ACTION_SEND) vm.handleShared(intent.getStringExtra(Intent.EXTRA_TEXT))
    }
}

@Composable
fun AnySaveTheme(accent: Color, content: @Composable () -> Unit) {
    val dark = androidx.compose.foundation.isSystemInDarkTheme()
    val base = if (dark) darkColorScheme() else lightColorScheme()
    val scheme = base.copy(
        primary = accent, onPrimary = Color.White,
        secondaryContainer = accent.copy(alpha = 0.18f), onSecondaryContainer = if (dark) Color.White else Color(0xFF14161A),
    )
    MaterialTheme(colorScheme = scheme, content = content)
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AnySaveApp(vm: MainViewModel) {
    var showSettings by remember { mutableStateOf(false) }
    val snackbar = remember { SnackbarHostState() }
    val jobs by vm.jobs.collectAsState()

    LaunchedEffect(vm.toast) { vm.toast?.let { snackbar.showSnackbar(it); vm.toast = null } }

    if (showSettings) {
        androidx.activity.compose.BackHandler { showSettings = false }
        SettingsScreen(vm, onClose = { showSettings = false })
        return
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("AnySave", fontWeight = FontWeight.Bold) },
                actions = {
                    IconButton(onClick = { showSettings = true }) { Icon(Icons.Default.Settings, "Settings") }
                },
            )
        },
        snackbarHost = { SnackbarHost(snackbar) },
    ) { pad ->
        LazyColumn(
            Modifier.fillMaxSize().padding(pad),
            contentPadding = PaddingValues(bottom = 32.dp),
        ) {
            item { SiteTabs(vm) }
            item { PickerSection(vm) }
            vm.info?.let { info -> item { InfoCard(vm, info) } }
            item {
                Row(Modifier.fillMaxWidth().padding(start = 16.dp, end = 8.dp, top = 24.dp),
                    verticalAlignment = Alignment.CenterVertically) {
                    Text("Downloads", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                    Spacer(Modifier.width(8.dp))
                    Text("${jobs.size}", color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Spacer(Modifier.weight(1f))
                    if (jobs.any { !it.active }) TextButton(onClick = { vm.clearFinished() }) { Text("Clear finished") }
                }
            }
            if (jobs.isEmpty()) item {
                Text("Your downloads show up here with live progress. Videos are saved to your Gallery (Movies/AnySave) and audio to Music/AnySave. Tip: in Instagram, TikTok or YouTube, tap Share › AnySave to pick Video or Audio and the quality without leaving the app.",
                    Modifier.padding(16.dp), color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 14.sp)
            }
            items(jobs, key = { it.id }) { JobCard(vm, it) }
            item {
                Text("Only download videos you own or have permission to download. Private posts, login-only content and DRM-protected videos are not supported.",
                    Modifier.padding(16.dp), fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

@Composable
fun SiteTabs(vm: MainViewModel) {
    val index = SITES.indexOfFirst { it.id == vm.tab.id }.coerceAtLeast(0)
    ScrollableTabRow(selectedTabIndex = index, edgePadding = 12.dp, divider = {}) {
        SITES.forEachIndexed { i, s ->
            Tab(selected = i == index, onClick = { vm.selectTab(s, true) },
                selectedContentColor = s.color, unselectedContentColor = MaterialTheme.colorScheme.onSurfaceVariant,
                text = {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Box(Modifier.size(8.dp).clip(CircleShape).background(s.color))
                        Spacer(Modifier.width(6.dp))
                        Text(s.name, fontWeight = if (i == index) FontWeight.SemiBold else FontWeight.Normal)
                    }
                })
        }
    }
}

@Composable
fun PickerSection(vm: MainViewModel) {
    val site = vm.tab
    val context = androidx.compose.ui.platform.LocalContext.current
    Column(Modifier.padding(horizontal = 16.dp, vertical = 12.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(40.dp).clip(RoundedCornerShape(10.dp)).background(site.color), contentAlignment = Alignment.Center) {
                Text(site.name.take(2).let { if (site.id == "all") "★" else it }, color = Color.White, fontWeight = FontWeight.Bold)
            }
            Spacer(Modifier.width(12.dp))
            Column {
                Text(site.name, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold)
                Text(site.subtitle, color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 14.sp)
            }
        }
        Spacer(Modifier.height(14.dp))

        if (site.id == "whatsapp") {
            StatusSaverSection(onToast = { vm.toast = it })
            return@Column
        }

        OutlinedTextField(
            value = vm.url,
            onValueChange = vm::onUrlChange,
            modifier = Modifier.fillMaxWidth(),
            singleLine = true,
            placeholder = { Text(site.placeholder, maxLines = 1, overflow = TextOverflow.Ellipsis) },
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri, imeAction = ImeAction.Go),
            keyboardActions = KeyboardActions(onGo = { vm.lookup() }),
            trailingIcon = {
                if (vm.url.isNotEmpty()) IconButton(onClick = { vm.onUrlChange("") }) { Icon(Icons.Default.Close, "Clear") }
            },
            shape = RoundedCornerShape(12.dp),
        )
        Spacer(Modifier.height(10.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(onClick = {
                val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                val text = cm.primaryClip?.takeIf { it.itemCount > 0 }?.getItemAt(0)?.coerceToText(context)?.toString()
                val link = firstLink(text)
                if (link != null) { vm.onUrlChange(link); vm.lookup() } else vm.toast = "No link on the clipboard"
            }) { Text("Paste") }
            Button(onClick = { vm.lookup() }, enabled = !vm.loading && vm.url.isNotBlank(), modifier = Modifier.weight(1f)) {
                if (vm.loading) {
                    CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp, color = Color.White)
                    Spacer(Modifier.width(8.dp)); Text("Looking up…")
                } else Text("Get video")
            }
        }

        vm.mismatch?.let { other ->
            Spacer(Modifier.height(10.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("This looks like a ${other.name} link.", Modifier.weight(1f), fontSize = 14.sp)
                TextButton(onClick = { vm.switchToMismatch() }) { Text("Switch to ${other.name}") }
            }
        }
        vm.error?.let {
            Spacer(Modifier.height(10.dp))
            Text(it, color = MaterialTheme.colorScheme.error, fontSize = 14.sp)
        }
        Spacer(Modifier.height(10.dp))
        site.tips.forEach { tip ->
            Text("•  $tip", fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(vertical = 1.dp))
        }
    }
}

@OptIn(ExperimentalLayoutApi::class, ExperimentalMaterial3Api::class)
@Composable
fun InfoCard(vm: MainViewModel, info: VideoInfo) {
    ElevatedCard(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp)) {
        Column(Modifier.padding(16.dp)) {
            Row {
                if (info.thumbnail != null) {
                    AsyncImage(info.thumbnail, null, Modifier.width(120.dp).aspectRatio(16f / 9f).clip(RoundedCornerShape(10.dp)),
                        contentScale = ContentScale.Crop)
                    Spacer(Modifier.width(12.dp))
                }
                Column(Modifier.weight(1f)) {
                    Text(info.title, fontWeight = FontWeight.SemiBold, maxLines = 3, overflow = TextOverflow.Ellipsis)
                    Text(listOfNotNull(info.site, if (info.isPlaylist) "Playlist · ${info.entries.size} videos" else null,
                        info.uploader, formatDuration(info.duration).ifBlank { null }).joinToString(" · "),
                        fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            if (!info.isPlaylist && info.playlistUrl != null) {
                Spacer(Modifier.height(12.dp))
                PlaylistBanner("This video is part of a playlist.", "Whole playlist") { vm.openWholePlaylist() }
            }
            if (info.isPlaylist && vm.singleUrl != null) {
                Spacer(Modifier.height(12.dp))
                PlaylistBanner("Showing the whole playlist.", "Just this video") { vm.openSingleVideo() }
            }
            if (info.isPlaylist) {
                Spacer(Modifier.height(12.dp))
                PlaylistPicker(info.entries, vm.picked, onToggle = vm::togglePick, onAll = vm::pickAll)
            }
            Spacer(Modifier.height(14.dp))
            val modes = listOf("both" to "Video + Audio", "video" to "Video only", "audio" to "Audio only")
            SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
                modes.forEachIndexed { i, (id, label) ->
                    SegmentedButton(selected = vm.mode == id, onClick = { vm.mode = id },
                        shape = SegmentedButtonDefaults.itemShape(i, modes.size)) { Text(label, fontSize = 13.sp, maxLines = 1) }
                }
            }
            Spacer(Modifier.height(12.dp))
            if (vm.mode != "audio") {
                if (info.qualities.isEmpty()) {
                    Text("Only one quality is available for this video.", fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                } else {
                    Text(if (info.isPlaylist) "Quality (or the best below it, per video)" else "Quality", style = MaterialTheme.typography.labelLarge)
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        info.qualities.forEach { q ->
                            val size = if (info.isPlaylist) null else vm.sizeFor(q)
                            FilterChip(selected = vm.height == q.height, onClick = { vm.height = q.height },
                                label = {
                                    Text(buildString {
                                        append(q.label); if (q.hdr) append(" HDR")
                                        if (q.tag.isNotBlank()) append(" · ${q.tag}")
                                        if (size != null && size > 0) append(" · ~${formatBytes(size.toDouble())}")
                                    })
                                })
                        }
                    }
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("Most compatible (H.264 MP4)", fontSize = 14.sp)
                        Text("Plays everywhere. Some sites only offer it up to 1080p.", fontSize = 12.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    Switch(checked = vm.compat, onCheckedChange = { vm.compat = it })
                }
            } else {
                Text("Format", style = MaterialTheme.typography.labelLarge)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    listOf("mp3", "m4a", "opus", "wav", "flac").forEach { f ->
                        FilterChip(selected = vm.audioFormat == f, onClick = { vm.audioFormat = f }, label = { Text(f.uppercase()) })
                    }
                }
                if (vm.audioFormat !in setOf("wav", "flac")) {
                    Text("Bitrate (kbps)", style = MaterialTheme.typography.labelLarge)
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        listOf("320", "256", "192", "128", "64").forEach { b ->
                            FilterChip(selected = vm.audioBitrate == b, onClick = { vm.audioBitrate = b }, label = { Text(b) })
                        }
                    }
                }
            }
            Spacer(Modifier.height(12.dp))
            val n = vm.picked.size
            Button(onClick = { vm.startDownload() }, Modifier.fillMaxWidth().height(48.dp), enabled = !info.isPlaylist || n > 0) {
                Text(if (info.isPlaylist) "Download $n video${if (n == 1) "" else "s"}" else "Download", fontSize = 16.sp)
            }
        }
    }
}

@Composable
fun JobCard(vm: MainViewModel, job: Job) {
    val context = androidx.compose.ui.platform.LocalContext.current
    OutlinedCard(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 5.dp)) {
        Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
            if (job.thumbnail != null) {
                AsyncImage(job.thumbnail, null, Modifier.width(72.dp).aspectRatio(16f / 9f).clip(RoundedCornerShape(8.dp)),
                    contentScale = ContentScale.Crop)
                Spacer(Modifier.width(10.dp))
            }
            Column(Modifier.weight(1f)) {
                Text(job.title, maxLines = 1, overflow = TextOverflow.Ellipsis, fontWeight = FontWeight.Medium)
                val statusText = when (job.status) {
                    "queued" -> "Waiting · ${job.label}"
                    "downloading" -> buildString {
                        append(job.progress?.let { "${(it * 100).toInt()}%" } ?: "Downloading")
                        job.eta?.takeIf { it > 0 }?.let { append(" · ${formatDuration(it.toDouble())} left") }
                        append(" · ${job.label}")
                    }
                    "done" -> "Saved · ${job.label}"
                    "error" -> job.error ?: "Failed"
                    else -> job.message
                }
                Text(statusText, fontSize = 12.sp, maxLines = 2, overflow = TextOverflow.Ellipsis,
                    color = if (job.status == "error") MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant)
                if (job.active) {
                    Spacer(Modifier.height(6.dp))
                    val p = job.progress
                    if (p != null) LinearProgressIndicator(progress = { p }, modifier = Modifier.fillMaxWidth())
                    else LinearProgressIndicator(Modifier.fillMaxWidth())
                }
            }
            when {
                job.active -> IconButton(onClick = { vm.cancel(job) }) { Icon(Icons.Default.Close, "Cancel") }
                job.status == "done" && job.savedUri != null -> {
                    TextButton(onClick = {
                        val i = Intent(Intent.ACTION_VIEW).setDataAndType(Uri.parse(job.savedUri), job.mime)
                            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                        runCatching { context.startActivity(i) }.onFailure { vm.toast = "No app can open this file" }
                    }) { Text("Open") }
                    TextButton(onClick = {
                        val i = Intent(Intent.ACTION_SEND).setType(job.mime).putExtra(Intent.EXTRA_STREAM, Uri.parse(job.savedUri))
                            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                        context.startActivity(Intent.createChooser(i, "Share"))
                    }) { Text("Share") }
                }
                else -> {
                    if (job.status == "error" || job.status == "cancelled") TextButton(onClick = { vm.retry(job) }) { Text("Retry") }
                    IconButton(onClick = { vm.remove(job) }) { Icon(Icons.Default.Delete, "Remove") }
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun SettingsScreen(vm: MainViewModel, onClose: () -> Unit) {
    var conn by remember { mutableFloatStateOf(vm.connections.toFloat()) }
    LaunchedEffect(Unit) { vm.loadVersion(); vm.refreshShareSetting() }

    Scaffold(topBar = {
        TopAppBar(title = { Text("Settings") }, navigationIcon = {
            IconButton(onClick = { vm.saveSettings(conn.toInt()); onClose() }) { Icon(Icons.Default.ArrowBack, "Back") }
        })
    }) { pad ->
        Column(Modifier.padding(pad).padding(16.dp).fillMaxWidth()) {
            Text("Sharing from other apps", style = MaterialTheme.typography.titleMedium)
            Text("In Instagram, TikTok, YouTube, X and other apps, tap Share › AnySave. A popup asks Video or Audio and the quality, then the download runs in the background.",
                fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(Modifier.height(8.dp))
            if (vm.shareRemember) {
                Text("Remembered choice: ${vm.rememberedLabel()}. Shares download straight away without the popup.", fontSize = 14.sp)
                TextButton(onClick = { vm.askEveryTime() }) { Text("Ask me every time again") }
            } else {
                Text("The popup asks every time. Tick “Remember my choice” in it to skip it.", fontSize = 14.sp)
            }
            Spacer(Modifier.height(20.dp))
            Text("Parallel connections: ${conn.toInt()}", style = MaterialTheme.typography.titleMedium)
            Text("Downloads pieces of a video at the same time. 8 works well on mobile data and Wi-Fi.",
                fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Slider(conn, { conn = it }, valueRange = 1f..16f, steps = 14)
            Spacer(Modifier.height(20.dp))
            Text("Download engine", style = MaterialTheme.typography.titleMedium)
            Text("yt-dlp ${vm.engineVersion}. Sites change often, so AnySave checks for a new version once a day. If a site stops working, update now.",
                fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(Modifier.height(8.dp))
            OutlinedButton(onClick = { vm.updateEngine() }, enabled = !vm.updating) {
                Text(if (vm.updating) "Updating…" else "Update now")
            }
            Spacer(Modifier.height(24.dp))
            Button(onClick = { vm.saveSettings(conn.toInt()); onClose() }, Modifier.fillMaxWidth()) { Text("Save") }
        }
    }
}
