package de.carstenkeller.videodownloader

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import okhttp3.OkHttpClient
import java.util.concurrent.atomic.AtomicInteger

/**
 * Runs a download batch as a foreground service instead of inside the bottom sheet's own
 * viewLifecycleOwner.lifecycleScope. That scope is cancelled the moment the sheet's view is
 * destroyed, which can happen simply from the app backgrounding long enough for the system to
 * tear the dialog down - cutting an in-progress download off mid-file, which is exactly the
 * reported bug. A foreground service has no lifetime tied to any UI, and its ongoing
 * notification is what lets Android keep it running (and keep its network access unrestricted)
 * while the app isn't in the foreground.
 */
class DownloadService : Service() {

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private lateinit var notificationManager: NotificationManager

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        notificationManager = getSystemService(NotificationManager::class.java)
        notificationManager.createNotificationChannel(
            NotificationChannel(
                CHANNEL_ID,
                getString(R.string.download_notification_channel),
                NotificationManager.IMPORTANCE_LOW
            )
        )
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val items = queuedItems
        if (items.isEmpty()) {
            stopSelf()
            return START_NOT_STICKY
        }

        // Must happen within moments of the service starting, before any suspending work below -
        // this is what grants the elevated, backgrounding-proof priority in the first place.
        startForeground(NOTIFICATION_ID, buildProgressNotification(0, items.size))

        val doneCount = AtomicInteger(0)
        val successCount = AtomicInteger(0)

        serviceScope.launch {
            val coordinator = DownloadCoordinator(applicationContext, OkHttpClient())
            coordinator.downloadAll(
                items = items,
                onProgress = { id, pct -> DownloadEvents.tryEmit(DownloadEvent.Progress(id, pct)) },
                onDone = { id, success, error ->
                    DownloadEvents.tryEmit(DownloadEvent.Done(id, success, error))
                    if (success) successCount.incrementAndGet()
                    val done = doneCount.incrementAndGet()
                    notificationManager.notify(NOTIFICATION_ID, buildProgressNotification(done, items.size))
                }
            )

            stopForeground(STOP_FOREGROUND_REMOVE)
            notificationManager.notify(
                DONE_NOTIFICATION_ID,
                buildDoneNotification(successCount.get(), items.size)
            )
            DownloadEvents.tryEmit(DownloadEvent.BatchFinished(successCount.get(), items.size))
            stopSelf()
        }

        return START_NOT_STICKY
    }

    override fun onDestroy() {
        super.onDestroy()
        serviceScope.cancel()
    }

    private fun buildProgressNotification(done: Int, total: Int): Notification =
        NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle(getString(R.string.download_notification_title))
            .setContentText(getString(R.string.download_notification_progress, done, total))
            .setSmallIcon(android.R.drawable.stat_sys_download)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setProgress(total, done, false)
            .build()

    private fun buildDoneNotification(successCount: Int, total: Int): Notification =
        NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle(getString(R.string.download_notification_title))
            .setContentText(getString(R.string.download_summary, successCount, total))
            .setSmallIcon(android.R.drawable.stat_sys_download_done)
            .setAutoCancel(true)
            .build()

    companion object {
        private const val CHANNEL_ID = "downloads"
        private const val NOTIFICATION_ID = 1001
        private const val DONE_NOTIFICATION_ID = 1002

        // Same-process handoff only (the service always runs in this app's own process), so a
        // plain static list is enough - no need to make MediaItem Parcelable (it carries a
        // Bitmap thumbnail that has no business crossing a Binder/Intent boundary anyway) just
        // to pass it through an Intent extra.
        @Volatile
        private var queuedItems: List<MediaItem> = emptyList()

        fun start(context: Context, items: List<MediaItem>) {
            queuedItems = items
            ContextCompat.startForegroundService(context, Intent(context, DownloadService::class.java))
        }
    }
}
