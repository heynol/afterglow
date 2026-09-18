package dev.heynol.afterglow.audio

import android.app.AppOpsManager
import android.content.Context
import android.content.Intent
import android.media.projection.MediaProjection
import android.os.Process
import android.util.Log
import dev.heynol.afterglow.service.CaptureService
import dev.heynol.afterglow.ui.ProjectionRequestActivity
import java.util.concurrent.CopyOnWriteArraySet

/**
 * Holds the MediaProjection that playback capture needs.
 *
 * Android asks the user for consent on every request unless the app has the PROJECT_MEDIA app-op
 * (`adb shell appops set dev.heynol.afterglow PROJECT_MEDIA allow`); then the system grants it
 * without a dialog. The projection is kept alive by [CaptureService] until [stop] or the system ends it.
 */
object Projection {

    enum class Status { ACTIVE, READY, NEEDS_CONSENT, UNSUPPORTED }

    @Volatile
    var current: MediaProjection? = null
        private set

    /** Set when this TV has no consent activity at all. */
    @Volatile
    var unsupported = false
        internal set

    @Volatile
    private var requestPending = false

    private val listeners = CopyOnWriteArraySet<() -> Unit>()

    fun addListener(listener: () -> Unit) = listeners.add(listener)

    fun removeListener(listener: () -> Unit) = listeners.remove(listener)

    fun status(context: Context): Status = when {
        current != null -> Status.ACTIVE
        unsupported -> Status.UNSUPPORTED
        grantedWithoutPrompt(context) -> Status.READY
        else -> Status.NEEDS_CONSENT
    }

    fun grantedWithoutPrompt(context: Context): Boolean {
        val appOps = context.getSystemService(AppOpsManager::class.java) ?: return false
        return runCatching {
            appOps.unsafeCheckOpNoThrow("android:project_media", Process.myUid(), context.packageName) == AppOpsManager.MODE_ALLOWED
        }.getOrDefault(false)
    }

    /**
     * Starts the consent flow. With [onlyIfSilent], nothing happens unless the system will grant
     * without a dialog; use that from the background so no prompt pops up over playback.
     */
    fun request(context: Context, onlyIfSilent: Boolean) {
        if (current != null || requestPending || unsupported) return
        if (onlyIfSilent && !grantedWithoutPrompt(context)) return
        requestPending = true
        try {
            context.startActivity(
                Intent(context, ProjectionRequestActivity::class.java)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_NO_ANIMATION or Intent.FLAG_ACTIVITY_EXCLUDE_FROM_RECENTS),
            )
        } catch (e: RuntimeException) {
            Log.w("Projection", "cannot start consent flow", e)
            requestPending = false
        }
    }

    /** Ends the projection (and the recording indicator that comes with it). */
    fun stop(context: Context) {
        if (current == null) return
        context.stopService(Intent(context, CaptureService::class.java))
    }

    internal fun onRequestFinished() {
        requestPending = false
    }

    internal fun onStarted(projection: MediaProjection) {
        current = projection
        notifyListeners()
    }

    internal fun onStopped() {
        current = null
        notifyListeners()
    }

    internal fun notifyListeners() = listeners.forEach { it() }
}
