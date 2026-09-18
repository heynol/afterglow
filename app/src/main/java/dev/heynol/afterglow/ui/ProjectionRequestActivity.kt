package dev.heynol.afterglow.ui

import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.Intent
import android.media.projection.MediaProjectionManager
import android.os.Bundle
import android.util.Log
import dev.heynol.afterglow.audio.Projection
import dev.heynol.afterglow.service.CaptureService

/** Invisible trampoline for the system's screen-capture consent (granted silently with PROJECT_MEDIA). */
class ProjectionRequestActivity : Activity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (savedInstanceState != null) return
        val manager = getSystemService(MediaProjectionManager::class.java)
        try {
            @Suppress("DEPRECATION")
            startActivityForResult(manager.createScreenCaptureIntent(), REQUEST)
        } catch (e: ActivityNotFoundException) {
            Log.w("ProjectionRequest", "no consent activity on this device", e)
            Projection.unsupported = true
            Projection.notifyListeners()
            finishQuietly()
        }
    }

    @Deprecated("Deprecated in Java")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        @Suppress("DEPRECATION")
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == REQUEST && resultCode == RESULT_OK && data != null) {
            startForegroundService(
                Intent(this, CaptureService::class.java)
                    .putExtra(CaptureService.EXTRA_RESULT_CODE, resultCode)
                    .putExtra(CaptureService.EXTRA_DATA, data),
            )
        }
        finishQuietly()
    }

    private fun finishQuietly() {
        Projection.onRequestFinished()
        finish()
        @Suppress("DEPRECATION")
        overridePendingTransition(0, 0)
    }

    private companion object {
        const val REQUEST = 1
    }
}
