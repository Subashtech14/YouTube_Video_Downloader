package app.anysave

import android.app.Application
import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class MainViewModel(app: Application) : AndroidViewModel(app) {
    private val prefs = app.getSharedPreferences("anysave", Context.MODE_PRIVATE)

    var shareRemember by mutableStateOf(prefs.getBoolean("shareRemember", false))
        private set
    var connections by mutableStateOf(prefs.getInt("connections", 8))
        private set
    var engineVersion by mutableStateOf("")
        private set
    var updating by mutableStateOf(false)
        private set

    var tab by mutableStateOf(siteById(prefs.getString("tab", "all") ?: "all"))
        private set
    var url by mutableStateOf("")
        private set
    var mismatch by mutableStateOf<Site?>(null)
        private set
    var loading by mutableStateOf(false)
        private set
    var error by mutableStateOf<String?>(null)
        private set
    var info by mutableStateOf<VideoInfo?>(null)
        private set
    var picked by mutableStateOf<Set<Int>>(emptySet())
    var singleUrl by mutableStateOf<String?>(null)
        private set
    var mode by mutableStateOf("both")
    var height by mutableStateOf<Int?>(null)
    var compat by mutableStateOf(true)
    var audioFormat by mutableStateOf("mp3")
    var audioBitrate by mutableStateOf("192")
    var toast by mutableStateOf<String?>(null)

    val jobs = Downloads.jobs

    fun saveSettings(connections: Int) {
        this.connections = connections
        prefs.edit().putInt("connections", connections).apply()
    }

    fun refreshShareSetting() { shareRemember = prefs.getBoolean("shareRemember", false) }
    fun askEveryTime() { ShareReceiverActivity.forget(getApplication()); shareRemember = false }
    fun rememberedLabel(): String = Engine.label(ShareReceiverActivity.remembered(getApplication()))

    fun loadVersion() = viewModelScope.launch {
        engineVersion = withContext(Dispatchers.IO) { runCatching { Engine.awaitReady(); Engine.version(getApplication()) }.getOrElse { "not ready" } }
    }

    fun updateEngine() {
        if (updating) return
        updating = true
        viewModelScope.launch {
            val result = withContext(Dispatchers.IO) {
                runCatching { Engine.awaitReady(); Engine.maybeUpdate(getApplication(), force = true) }.getOrElse { it.message ?: "Update failed" }
            }
            toast = result; updating = false; loadVersion()
        }
    }

    fun selectTab(site: Site, clear: Boolean) {
        tab = site
        prefs.edit().putString("tab", site.id).apply()
        mismatch = null
        if (clear) { url = ""; info = null; error = null; singleUrl = null }
    }

    fun onUrlChange(value: String) {
        url = value
        checkMatch()
    }

    private fun checkMatch(): Boolean {
        mismatch = null
        val found = siteForUrl(url) ?: return true
        if (found.id == tab.id) return true
        if (tab.id == "all") { selectTab(found, false); return true }
        mismatch = found
        return false
    }

    fun switchToMismatch() {
        mismatch?.let { selectTab(it, false); lookup() }
    }

    fun handleShared(text: String?) {
        val link = firstLink(text) ?: return
        selectTab(siteForUrl(link) ?: siteById("all"), true)
        url = link
        lookup()
    }

    fun lookup() { singleUrl = null; load(url.trim(), playlist = false) }

    fun openWholePlaylist() {
        val i = info ?: return
        val single = i.url.takeIf { i.playlistUrl != null } ?: return
        load(url.trim(), playlist = true)
        singleUrl = single
    }

    fun openSingleVideo() { singleUrl = null; load(url.trim(), playlist = false) }

    fun togglePick(index: Int) { picked = if (index in picked) picked - index else picked + index }
    fun pickAll(all: Boolean) { picked = if (all) info?.entries?.indices?.toSet() ?: emptySet() else emptySet() }

    private fun load(u: String, playlist: Boolean) {
        if (u.isEmpty() || loading) return
        if (!u.startsWith("http://") && !u.startsWith("https://")) { error = "That doesn't look like a link."; return }
        if (!checkMatch()) return
        if (siteForUrl(u)?.id == "whatsapp") { error = "WhatsApp videos can't be downloaded from a link."; return }
        loading = true; error = null; info = null
        viewModelScope.launch {
            try {
                val i = Engine.info(u, playlist)
                info = i
                picked = i.entries.indices.toSet()
                height = i.qualities.firstOrNull { it.height <= 1080 }?.height ?: i.qualities.firstOrNull()?.height
            } catch (e: Exception) {
                error = e.message
            } finally {
                loading = false
            }
        }
    }

    fun sizeFor(q: Quality): Long? {
        val key = (if (mode == "video") "video" else "both") + if (compat) "_compat" else ""
        return q.sizes[key]
    }

    fun startDownload() {
        val i = info ?: return
        val opts = DownloadOptions(mode, if (mode == "audio") null else height, compat, audioFormat, audioBitrate)
        if (i.isPlaylist) {
            val chosen = i.entries.filterIndexed { idx, _ -> idx in picked }
            if (chosen.isEmpty()) { toast = "Pick at least one video."; return }
            Downloads.addAll(getApplication(), chosen, opts)
            toast = "Queued ${chosen.size} video${if (chosen.size > 1) "s" else ""}. They keep going if you leave the app."
            return
        }
        Downloads.add(getApplication(), i.url, opts, i.title, i.thumbnail)
        toast = "Downloading. It keeps going if you leave the app."
    }

    fun cancel(job: Job) = Downloads.cancel(job.id)
    fun remove(job: Job) = Downloads.remove(job.id)
    fun retry(job: Job) = Downloads.retry(getApplication(), job)
    fun clearFinished() = Downloads.clearFinished()
}
