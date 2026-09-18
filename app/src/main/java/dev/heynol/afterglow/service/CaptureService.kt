package dev.heynol.afterglow.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.util.Log
import androidx.core.content.IntentCompat
import dev.heynol.afterglow.R
import dev.heynol.afterglow.audio.Projection

/**
 * Foreground service that owns the MediaProjection (Android requires a `mediaProjection` foreground
 * service while one is in use). TVs don't show its notification.
 */
class CaptureService : Service() {

    private var projection: MediaProjection? = null

    private val callback = object : MediaProjection.Callback() {
        override fun onStop() {
            Log.i(TAG, "projection stopped by the system")
            projection = null
            Projection.onStopped()
            stopSelf()
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val resultCode = intent?.getIntExtra(EXTRA_RESULT_CODE, 0) ?: 0
        val data = intent?.let { IntentCompat.getParcelableExtra(it, EXTRA_DATA, Intent::class.java) }
        if (data == null || projection != null) {
            if (projection == null) stopSelf()
            return START_NOT_STICKY
        }
        startForeground(NOTIFICATION_ID, notification(), ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION)
        val manager = getSystemService(MediaProjectionManager::class.java)
        val p = try {
            manager.getMediaProjection(resultCode, data)
        } catch (e: RuntimeException) {
            Log.w(TAG, "getMediaProjection failed", e)
            null
        }
        if (p == null) {
            stopSelf()
            return START_NOT_STICKY
        }
        p.registerCallback(callback, Handler(Looper.getMainLooper()))
        projection = p
        Projection.onStarted(p)
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        projection?.let {
            it.unregisterCallback(callback)
            it.stop()
            Projection.onStopped()
        }
        projection = null
        super.onDestroy()
    }

    private fun notification(): Notification {
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel(CHANNEL, getString(R.string.capture_channel), NotificationManager.IMPORTANCE_MIN))
        return Notification.Builder(this, CHANNEL)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(getString(R.string.capture_notification))
            .setOngoing(true)
            .build()
    }

    companion object {
        private const val TAG = "CaptureService"
        private const val CHANNEL = "capture"
        private const val NOTIFICATION_ID = 1
        const val EXTRA_RESULT_CODE = "result_code"
        const val EXTRA_DATA = "data"
    }
}
