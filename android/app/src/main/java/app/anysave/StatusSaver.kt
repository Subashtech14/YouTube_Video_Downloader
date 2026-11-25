package app.anysave

import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.DocumentsContract
import android.provider.MediaStore
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.documentfile.provider.DocumentFile
import coil.compose.AsyncImage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

data class StatusItem(val uri: Uri, val name: String, val isVideo: Boolean, val modified: Long)

object StatusSaver {
    private const val STORAGE = "com.android.externalstorage.documents"

    enum class App(val label: String, val pkg: String, val legacyDir: String) {
        WHATSAPP("WhatsApp", "com.whatsapp", "WhatsApp"),
        BUSINESS("WA Business", "com.whatsapp.w4b", "WhatsApp Business"),
    }

    private fun docPath(app: App): String =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) "Android/media/${app.pkg}/${app.legacyDir}/Media/.Statuses"
        else "${app.legacyDir}/Media/.Statuses"

    fun initialUri(app: App): Uri = DocumentsContract.buildDocumentUri(STORAGE, "primary:" + docPath(app))

    fun savedTree(context: Context, app: App): Uri? =
        context.contentResolver.persistedUriPermissions
            .firstOrNull { it.isReadPermission && Uri.decode(it.uri.toString()).endsWith(docPath(app)) }?.uri

    fun remember(context: Context, tree: Uri) {
        context.contentResolver.takePersistableUriPermission(tree, Intent.FLAG_GRANT_READ_URI_PERMISSION)
    }

    fun isStatusesFolder(tree: Uri) = Uri.decode(tree.toString()).endsWith("/.Statuses") || Uri.decode(tree.toString()).endsWith(":.Statuses")

    suspend fun list(context: Context, tree: Uri): List<StatusItem> = withContext(Dispatchers.IO) {
        val dir = DocumentFile.fromTreeUri(context, tree) ?: return@withContext emptyList()
        dir.listFiles().mapNotNull { f ->
            val name = f.name ?: return@mapNotNull null
            val ext = name.substringAfterLast('.', "").lowercase()
            when (ext) {
                "jpg", "jpeg", "png", "webp" -> StatusItem(f.uri, name, false, f.lastModified())
                "mp4", "3gp", "mkv", "webm" -> StatusItem(f.uri, name, true, f.lastModified())
                else -> null
            }
        }.sortedByDescending { it.modified }
    }

    suspend fun save(context: Context, item: StatusItem): Boolean = withContext(Dispatchers.IO) {
        runCatching {
            val resolver = context.contentResolver
            val ext = item.name.substringAfterLast('.', if (item.isVideo) "mp4" else "jpg").lowercase()
            val mime = resolver.getType(item.uri) ?: if (item.isVideo) "video/mp4" else "image/jpeg"
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                val collection = if (item.isVideo) MediaStore.Video.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
                                 else MediaStore.Images.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
                val values = ContentValues().apply {
                    put(MediaStore.MediaColumns.DISPLAY_NAME, "Status_${item.modified}.$ext")
                    put(MediaStore.MediaColumns.MIME_TYPE, mime)
                    put(MediaStore.MediaColumns.RELATIVE_PATH,
                        (if (item.isVideo) Environment.DIRECTORY_MOVIES else Environment.DIRECTORY_PICTURES) + "/AnySave/Status")
                }
                val out = resolver.insert(collection, values) ?: error("Couldn't create the file")
                resolver.openOutputStream(out)!!.use { o -> resolver.openInputStream(item.uri)!!.use { it.copyTo(o) } }
            } else {
                val dir = java.io.File(context.getExternalFilesDir(
                    if (item.isVideo) Environment.DIRECTORY_MOVIES else Environment.DIRECTORY_PICTURES), "Status")
                dir.mkdirs()
                java.io.File(dir, "Status_${item.modified}.$ext").outputStream().use { o ->
                    resolver.openInputStream(item.uri)!!.use { it.copyTo(o) }
                }
            }
        }.isSuccess
    }
}

@Composable
fun StatusSaverSection(onToast: (String) -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var app by remember { mutableStateOf(StatusSaver.App.WHATSAPP) }
    var tree by remember(app) { mutableStateOf(StatusSaver.savedTree(context, app)) }
    var items by remember { mutableStateOf<List<StatusItem>>(emptyList()) }
    var loading by remember { mutableStateOf(false) }
    var filter by remember { mutableStateOf("all") }
    val selected = remember { mutableStateListOf<Uri>() }

    fun reload() {
        val t = tree ?: run { items = emptyList(); return }
        loading = true
        scope.launch {
            items = runCatching { StatusSaver.list(context, t) }.getOrElse { emptyList() }
            selected.clear(); loading = false
        }
    }
    LaunchedEffect(tree) { reload() }

    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        if (!StatusSaver.isStatusesFolder(uri)) {
            onToast("That isn't the .Statuses folder. Tap “Use this folder” on the folder that opens first.")
            return@rememberLauncherForActivityResult
        }
        runCatching { StatusSaver.remember(context, uri) }
        tree = uri
    }

    Column(Modifier.fillMaxWidth()) {
        Text("Status saver", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
        Text("Save WhatsApp statuses you've already viewed (they disappear after 24 hours). Open the status in WhatsApp first, then come back here.",
            fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.height(10.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            StatusSaver.App.entries.forEach { a ->
                FilterChip(selected = app == a, onClick = { app = a }, label = { Text(a.label) })
            }
        }
        Spacer(Modifier.height(10.dp))

        if (tree == null) {
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp)) {
                    Text("Allow AnySave to see ${app.label} statuses", fontWeight = FontWeight.SemiBold)
                    Spacer(Modifier.height(6.dp))
                    Text("Android asks once. The folder picker opens on the hidden “.Statuses” folder: just tap “Use this folder”, then “Allow”.",
                        fontSize = 14.sp)
                    Spacer(Modifier.height(12.dp))
                    Button(onClick = { picker.launch(StatusSaver.initialUri(app)) }) { Text("Give access") }
                }
            }
            return@Column
        }

        Row(verticalAlignment = Alignment.CenterVertically) {
            listOf("all" to "All", "video" to "Videos", "photo" to "Photos").forEach { (id, label) ->
                FilterChip(selected = filter == id, onClick = { filter = id }, label = { Text(label) },
                    modifier = Modifier.padding(end = 8.dp))
            }
            Spacer(Modifier.weight(1f))
            TextButton(onClick = { reload() }) { Text("Refresh") }
        }
        val shown = items.filter { filter == "all" || (filter == "video") == it.isVideo }
        when {
            loading -> LinearProgressIndicator(Modifier.fillMaxWidth().padding(vertical = 12.dp))
            shown.isEmpty() -> Text("No statuses yet. View some statuses in ${app.label}, then tap Refresh.",
                Modifier.padding(vertical = 12.dp), color = MaterialTheme.colorScheme.onSurfaceVariant)
            else -> {
                shown.chunked(3).forEach { row ->
                    Row(Modifier.fillMaxWidth().padding(vertical = 3.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        row.forEach { item ->
                            val on = item.uri in selected
                            Box(Modifier.weight(1f).aspectRatio(9f / 16f).clip(RoundedCornerShape(10.dp))
                                .border(if (on) BorderStroke(3.dp, MaterialTheme.colorScheme.primary) else BorderStroke(0.dp, Color.Transparent), RoundedCornerShape(10.dp))
                                .clickable { if (on) selected.remove(item.uri) else selected.add(item.uri) }) {
                                AsyncImage(item.uri, null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
                                if (item.isVideo) Text("▶", color = Color.White, fontSize = 18.sp,
                                    modifier = Modifier.align(Alignment.Center).background(Color(0x66000000), CircleShape).padding(horizontal = 10.dp, vertical = 4.dp))
                                if (on) Text("✓", color = Color.White, fontWeight = FontWeight.Bold,
                                    modifier = Modifier.align(Alignment.TopEnd).padding(6.dp).background(MaterialTheme.colorScheme.primary, CircleShape).padding(horizontal = 7.dp, vertical = 2.dp))
                            }
                        }
                        repeat(3 - row.size) { Spacer(Modifier.weight(1f)) }
                    }
                }
                Spacer(Modifier.height(10.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(onClick = {
                        if (selected.size == shown.size) selected.clear() else { selected.clear(); selected.addAll(shown.map { it.uri }) }
                    }) { Text(if (selected.size == shown.size) "Select none" else "Select all") }
                    Button(enabled = selected.isNotEmpty(), modifier = Modifier.weight(1f), onClick = {
                        val toSave = items.filter { it.uri in selected }
                        scope.launch {
                            val ok = toSave.count { StatusSaver.save(context, it) }
                            onToast(if (ok == toSave.size) "Saved $ok to your Gallery (AnySave/Status)" else "Saved $ok of ${toSave.size}")
                            selected.clear()
                        }
                    }) { Text(if (selected.isEmpty()) "Save" else "Save ${selected.size}") }
                }
            }
        }
        Spacer(Modifier.height(12.dp))
        Text("Videos sent in chats have no public link, so they can't be downloaded here. In the chat, tap the video, then ⋮ › Save.",
            fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}
