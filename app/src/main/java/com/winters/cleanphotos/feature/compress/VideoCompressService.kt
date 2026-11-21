package com.winters.cleanphotos.feature.compress

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import com.winters.cleanphotos.R
import com.winters.cleanphotos.core.design.ApiTier
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject

/** 压缩进行时保住进程。通知上有进度和取消。离开清理页不停止这次任务。 */
@AndroidEntryPoint
class VideoCompressService : Service() {
    @Inject lateinit var controller: ForegroundVideoCompressController

    private var shownPercent = -1

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_CANCEL) {
            controller.cancel()
            return START_NOT_STICKY
        }
        promote(0)
        controller.attach(this)
        return START_NOT_STICKY
    }

    fun showProgress(percent: Int) {
        if (percent == shownPercent) return
        shownPercent = percent
        val manager = getSystemService(NotificationManager::class.java)
        manager.notify(NOTIFICATION_ID, buildNotification(percent))
    }

    fun shutdown() {
        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    private fun promote(percent: Int) {
        ensureChannel()
        ServiceCompat.startForeground(
            this,
            NOTIFICATION_ID,
            buildNotification(percent),
            ApiTier.Current.compressForegroundServiceType,
        )
    }

    private fun ensureChannel() {
        val manager = getSystemService(NotificationManager::class.java)
        if (manager.getNotificationChannel(CHANNEL_ID) != null) return
        manager.createNotificationChannel(
            NotificationChannel(CHANNEL_ID, getString(R.string.compress_channel), NotificationManager.IMPORTANCE_LOW),
        )
    }

    private fun buildNotification(percent: Int): Notification {
        val cancel = PendingIntent.getService(
            this,
            0,
            Intent(this, VideoCompressService::class.java).setAction(ACTION_CANCEL),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setContentTitle(getString(R.string.compress_notif_title))
            .setContentText(getString(R.string.compress_progress, percent))
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setProgress(100, percent, false)
            .addAction(R.drawable.ic_launcher_foreground, getString(R.string.cancel), cancel)
            .build()
    }

    companion object {
        private const val CHANNEL_ID = "video_compress"
        private const val NOTIFICATION_ID = 41
        const val ACTION_CANCEL = "com.winters.cleanphotos.action.CANCEL_COMPRESS"
    }
}
