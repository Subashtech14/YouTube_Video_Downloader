package app.anysave

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.Uri
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

class DownloadService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private val notified = mutableSetOf<String>()

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        createChannels(this)
        notified += Downloads.jobs.value.filter { !it.active }.map { it.id }
        ServiceCompat.startForeground(
            this, ONGOING_ID, progressNotification(Downloads.jobs.value),
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC else 0,
        )
        scope.launch {
            Downloads.jobs.collectLatest { jobs ->
                val nm = getSystemService(NotificationManager::class.java)
                jobs.filter { !it.active && it.id !in notified && it.status in setOf("done", "error") }.forEach { j ->
                    notified += j.id
                    nm.notify(j.id.hashCode(), finishedNotification(j))
                }
                if (jobs.none { it.active }) {
                    ServiceCompat.stopForeground(this@DownloadService, ServiceCompat.STOP_FOREGROUND_REMOVE)
                    stopSelf()
                } else {
                    nm.notify(ONGOING_ID, progressNotification(jobs))
                }
            }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int) = START_NOT_STICKY

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }

    private fun openApp(): PendingIntent = PendingIntent.getActivity(
        this, 0, Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
    )

    private fun progressNotification(jobs: List<Job>): Notification {
        val active = jobs.filter { it.active }
        val first = active.firstOrNull()
        val p = first?.progress
        return NotificationCompat.Builder(this, CHANNEL_PROGRESS)
            .setSmallIcon(android.R.drawable.stat_sys_download)
            .setContentTitle(if (active.size > 1) "Downloading ${active.size} videos" else first?.title ?: "AnySave")
            .setContentText(first?.message ?: "Starting")
            .setProgress(100, ((p ?: 0f) * 100).toInt(), p == null)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setContentIntent(openApp())
            .build()
    }

    private fun finishedNotification(j: Job): Notification {
        val b = NotificationCompat.Builder(this, CHANNEL_DONE)
            .setAutoCancel(true)
            .setContentTitle(j.title)
        return if (j.status == "done" && j.savedUri != null) {
            val view = Intent(Intent.ACTION_VIEW).setDataAndType(Uri.parse(j.savedUri), j.mime)
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
            b.setSmallIcon(android.R.drawable.stat_sys_download_done)
                .setContentText("Saved · tap to play")
                .setContentIntent(PendingIntent.getActivity(this, j.id.hashCode(), view, PendingIntent.FLAG_IMMUTABLE))
                .build()
        } else {
            b.setSmallIcon(android.R.drawable.stat_notify_error)
                .setContentText(j.error ?: "Download failed")
                .setStyle(NotificationCompat.BigTextStyle().bigText(j.error ?: "Download failed"))
                .setContentIntent(openApp())
                .build()
        }
    }

    companion object {
        private const val CHANNEL_PROGRESS = "progress"
        private const val CHANNEL_DONE = "done"
        private const val ONGOING_ID = 1

        fun createChannels(context: Context) {
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
            val nm = context.getSystemService(NotificationManager::class.java)
            nm.createNotificationChannel(NotificationChannel(CHANNEL_PROGRESS, "Download progress", NotificationManager.IMPORTANCE_LOW))
            nm.createNotificationChannel(NotificationChannel(CHANNEL_DONE, "Finished downloads", NotificationManager.IMPORTANCE_DEFAULT))
        }

        fun ensureRunning(context: Context) {
            ContextCompat.startForegroundService(context, Intent(context, DownloadService::class.java))
        }

        fun refresh(context: Context) {}
    }
}
