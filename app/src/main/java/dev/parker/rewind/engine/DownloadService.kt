package dev.parker.rewind.engine

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.IBinder
import android.text.format.Formatter
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import dev.parker.rewind.MainActivity
import dev.parker.rewind.R
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.launch

/** Keeps the process alive while anything is downloading or being streamed. */
class DownloadService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL, getString(R.string.channel_downloads), NotificationManager.IMPORTANCE_LOW)
        )
        ServiceCompat.startForeground(
            this, NOTIFICATION_ID, build(Downloads.jobs.value),
            ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC,
        )
        scope.launch {
            @OptIn(kotlinx.coroutines.FlowPreview::class)
            Downloads.jobs.debounce(250).collect { jobs ->
                if (jobs.none { it.active || it.streaming }) {
                    stopSelf()
                } else {
                    nm.notify(NOTIFICATION_ID, build(jobs))
                }
            }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            Downloads.stopAll()
            stopSelf()
        }
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }

    private fun build(jobs: List<DownloadJob>): Notification {
        val active = jobs.filter { it.active }
        val streaming = jobs.count { it.streaming }
        val rate = active.sumOf { it.rateBytes.toLong() }
        val total = active.sumOf { it.size }
        val done = active.sumOf { it.downloaded }

        val title = when {
            active.size == 1 -> active[0].fileName
            active.isNotEmpty() -> getString(R.string.notif_downloading_n, active.size)
            else -> getString(R.string.notif_streaming_n, streaming)
        }
        val text = if (active.isNotEmpty()) {
            "${Formatter.formatShortFileSize(this, done)} / ${Formatter.formatShortFileSize(this, total)} · " +
                "${Formatter.formatShortFileSize(this, rate)}/s"
        } else {
            getString(R.string.notif_stream_ready)
        }

        val open = PendingIntent.getActivity(
            this, 0,
            Intent(this, MainActivity::class.java).setAction(MainActivity.ACTION_SHOW_DOWNLOADS)
                .addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val stop = PendingIntent.getService(
            this, 1, Intent(this, DownloadService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_IMMUTABLE,
        )

        return NotificationCompat.Builder(this, CHANNEL)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(title)
            .setContentText(text)
            .setContentIntent(open)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setSilent(true)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
            .apply {
                if (active.isNotEmpty() && total > 0) {
                    setProgress(1000, (done * 1000 / total).toInt(), active.any { it.state == DownloadJob.State.Starting })
                }
            }
            .addAction(0, getString(R.string.action_stop_all), stop)
            .build()
    }

    companion object {
        private const val CHANNEL = "downloads"
        private const val NOTIFICATION_ID = 1
        private const val ACTION_STOP = "dev.parker.rewind.STOP"

        fun start(context: Context) {
            context.startForegroundService(Intent(context, DownloadService::class.java))
        }
    }
}
