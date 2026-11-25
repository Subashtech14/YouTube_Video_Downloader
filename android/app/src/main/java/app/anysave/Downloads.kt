package app.anysave

import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.webkit.MimeTypeMap
import androidx.core.content.FileProvider
import com.yausername.youtubedl_android.YoutubeDL
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import java.io.File
import java.util.UUID

object Downloads {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val slots = Semaphore(2)
    private val _jobs = MutableStateFlow<List<Job>>(emptyList())
    val jobs: StateFlow<List<Job>> = _jobs
    private val cancelled = mutableSetOf<String>()

    private fun set(id: String, change: (Job) -> Job) =
        _jobs.update { list -> list.map { if (it.id == id) change(it) else it } }

    fun add(context: Context, url: String, options: DownloadOptions, title: String? = null, thumbnail: String? = null): Job {
        val app = context.applicationContext
        val job = Job(
            id = UUID.randomUUID().toString().take(10), url = url, options = options,
            title = title ?: url, thumbnail = thumbnail, label = Engine.label(options),
        )
        _jobs.update { listOf(job) + it }
        DownloadService.ensureRunning(app)
        scope.launch { slots.withPermit { run(app, job) } }
        return job
    }

    fun addAll(context: Context, entries: List<PlaylistEntry>, options: DownloadOptions) {
        if (entries.isEmpty()) return
        val app = context.applicationContext
        val label = Engine.label(options)
        val batch = entries.map {
            Job(id = UUID.randomUUID().toString().take(10), url = it.url, options = options,
                title = it.title, thumbnail = it.thumbnail, label = label)
        }
        _jobs.update { batch + it }
        DownloadService.ensureRunning(app)
        batch.forEach { job -> scope.launch { slots.withPermit { run(app, job) } } }
    }

    private suspend fun run(context: Context, job: Job) {
        if (job.id in cancelled) return
        val work = File(context.cacheDir, "dl/${job.id}")
        try {
            set(job.id) { it.copy(status = "preparing", message = "Finding the best streams") }
            if (job.title == job.url) runCatching { Engine.info(job.url) }.getOrNull()?.let { i ->
                if (i.isPlaylist) {
                    _jobs.update { list -> list.filterNot { it.id == job.id } }
                    addAll(context, i.entries, job.options)
                    return
                }
                set(job.id) { it.copy(title = i.title, thumbnail = i.thumbnail) }
            }
            if (job.id in cancelled) throw YoutubeDL.CanceledException()
            set(job.id) { it.copy(status = "downloading", message = "Downloading") }
            val prefs = context.getSharedPreferences("anysave", Context.MODE_PRIVATE)
            val file = Engine.download(job, work, prefs.getInt("connections", 8)) { p, eta, stage ->
                set(job.id) {
                    if (stage == "Downloading") it.copy(status = "downloading", progress = p, eta = eta, message = stage)
                    else it.copy(status = "processing", progress = null, eta = null, message = stage)
                }
            }
            set(job.id) { it.copy(status = "processing", progress = null, message = "Saving to your phone") }
            val (uri, mime) = saveToPublic(context, file, job.options.mode == "audio")
            set(job.id) { it.copy(status = "done", progress = 1f, message = "Saved", fileName = file.name, savedUri = uri.toString(), mime = mime) }
        } catch (e: YoutubeDL.CanceledException) {
            set(job.id) { it.copy(status = "cancelled", message = "Cancelled", progress = null) }
        } catch (e: Throwable) {
            if (job.id in cancelled) set(job.id) { it.copy(status = "cancelled", message = "Cancelled", progress = null) }
            else set(job.id) { it.copy(status = "error", message = "Failed", error = e.message ?: "Download failed", progress = null) }
        } finally {
            work.deleteRecursively()
            DownloadService.refresh(context)
        }
    }

    fun cancel(id: String) {
        cancelled += id
        Engine.cancel(id)
        set(id) { if (it.status == "queued") it.copy(status = "cancelled", message = "Cancelled") else it }
    }

    fun remove(id: String) = _jobs.update { list -> list.filterNot { it.id == id && !it.active } }
    fun clearFinished() = _jobs.update { list -> list.filter { it.active } }
    fun retry(context: Context, job: Job) {
        remove(job.id)
        add(context, job.url, job.options, job.title.takeIf { it != job.url }, job.thumbnail)
    }

    private fun saveToPublic(context: Context, file: File, audio: Boolean): Pair<Uri, String> {
        val known = mapOf(
            "mp4" to "video/mp4", "m4v" to "video/mp4", "webm" to "video/webm", "mkv" to "video/x-matroska",
            "mov" to "video/quicktime", "mp3" to "audio/mpeg", "m4a" to "audio/mp4", "opus" to "audio/ogg",
            "ogg" to "audio/ogg", "wav" to "audio/x-wav", "flac" to "audio/flac",
        )
        val ext = file.extension.lowercase()
        val mime = known[ext] ?: MimeTypeMap.getSingleton().getMimeTypeFromExtension(ext)
            ?: if (audio) "audio/mpeg" else "video/mp4"
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val collection = if (audio) MediaStore.Audio.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
                             else MediaStore.Video.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
            val values = ContentValues().apply {
                put(MediaStore.MediaColumns.DISPLAY_NAME, file.name)
                put(MediaStore.MediaColumns.MIME_TYPE, mime)
                put(MediaStore.MediaColumns.RELATIVE_PATH,
                    (if (audio) Environment.DIRECTORY_MUSIC else Environment.DIRECTORY_MOVIES) + "/AnySave")
                put(MediaStore.MediaColumns.IS_PENDING, 1)
            }
            val resolver = context.contentResolver
            val uri = resolver.insert(collection, values) ?: throw IllegalStateException("Couldn't create the file on your phone.")
            resolver.openOutputStream(uri)!!.use { out -> file.inputStream().use { it.copyTo(out) } }
            values.clear(); values.put(MediaStore.MediaColumns.IS_PENDING, 0)
            resolver.update(uri, values, null, null)
            return uri to mime
        }
        val dir = File(context.getExternalFilesDir(if (audio) Environment.DIRECTORY_MUSIC else Environment.DIRECTORY_MOVIES), "AnySave")
        dir.mkdirs()
        val dest = File(dir, file.name)
        file.copyTo(dest, overwrite = true)
        return FileProvider.getUriForFile(context, "${context.packageName}.files", dest) to mime
    }
}
