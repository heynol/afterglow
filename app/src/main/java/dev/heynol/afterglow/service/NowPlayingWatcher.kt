package dev.heynol.afterglow.service

import android.content.ComponentName
import android.content.Context
import android.media.MediaMetadata
import android.media.session.MediaController
import android.media.session.MediaSessionManager
import android.media.session.PlaybackState
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.service.notification.NotificationListenerService
import android.util.Log
import dev.heynol.afterglow.audio.NowPlaying

/** Exists only so the app can read media sessions; notifications themselves are ignored. */
class MediaListenerService : NotificationListenerService()

/**
 * Reads title, artist and play state of the foreground app's media session. Needs notification
 * listener access for [MediaListenerService]; TVs hide that screen, so it's granted over adb:
 * `adb shell cmd notification allow_listener dev.heynol.afterglow/dev.heynol.afterglow.service.MediaListenerService`.
 *
 * Main thread only.
 */
class NowPlayingWatcher(private val context: Context, private val onChanged: (NowPlaying?) -> Unit) {

    private val manager = context.getSystemService(MediaSessionManager::class.java)
    private val component = ComponentName(context, MediaListenerService::class.java)
    private val handler = Handler(Looper.getMainLooper())
    private var controller: MediaController? = null
    private var targetPackage: String? = null
    private var started = false

    var current: NowPlaying? = null
        private set

    private val sessionsListener = MediaSessionManager.OnActiveSessionsChangedListener { pick(it.orEmpty()) }

    private val callback = object : MediaController.Callback() {
        override fun onMetadataChanged(metadata: MediaMetadata?) = publish()
        override fun onPlaybackStateChanged(state: PlaybackState?) = publish()
        override fun onSessionDestroyed() {
            detachController()
            publish()
        }
    }

    val hasAccess: Boolean get() = hasAccess(context)

    fun start() {
        if (started || !hasAccess) return
        try {
            manager.addOnActiveSessionsChangedListener(sessionsListener, component, handler)
            started = true
            pick(manager.getActiveSessions(component))
        } catch (e: SecurityException) {
            Log.i(TAG, "no media session access")
        }
    }

    fun stop() {
        if (started) runCatching { manager.removeOnActiveSessionsChangedListener(sessionsListener) }
        started = false
        detachController()
        current = null
    }

    /** Packages whose media session is playing right now (empty without access). */
    fun playingPackages(): List<String> {
        if (!hasAccess) return emptyList()
        return runCatching {
            manager.getActiveSessions(component).filter { it.playbackState?.state == PlaybackState.STATE_PLAYING }.map { it.packageName }
        }.getOrDefault(emptyList())
    }

    /** Follow the session of this package (the enabled app in front), or none. */
    fun follow(pkg: String?) {
        targetPackage = pkg
        if (!started) start()
        if (started) runCatching { pick(manager.getActiveSessions(component)) }
    }

    private fun pick(sessions: List<MediaController>) {
        val pkg = targetPackage
        val next = sessions.firstOrNull { it.packageName == pkg }
        if (next?.sessionToken == controller?.sessionToken) return publish()
        detachController()
        controller = next?.also { it.registerCallback(callback, handler) }
        publish()
    }

    private fun detachController() {
        controller?.unregisterCallback(callback)
        controller = null
    }

    private fun publish() {
        val c = controller
        val next = c?.let {
            val md = it.metadata
            NowPlaying(
                packageName = it.packageName,
                title = md?.getString(MediaMetadata.METADATA_KEY_TITLE) ?: md?.getString(MediaMetadata.METADATA_KEY_DISPLAY_TITLE),
                artist = md?.getString(MediaMetadata.METADATA_KEY_ARTIST) ?: md?.getString(MediaMetadata.METADATA_KEY_DISPLAY_SUBTITLE),
                album = md?.getString(MediaMetadata.METADATA_KEY_ALBUM),
                durationMs = md?.getLong(MediaMetadata.METADATA_KEY_DURATION)?.takeIf { d -> d > 0 },
                playing = it.playbackState?.state == PlaybackState.STATE_PLAYING,
            )
        }
        if (next != current) {
            current = next
            onChanged(next)
        }
    }

    companion object {
        private const val TAG = "NowPlayingWatcher"

        fun hasAccess(context: Context): Boolean {
            val flat = Settings.Secure.getString(context.contentResolver, "enabled_notification_listeners") ?: return false
            val expected = ComponentName(context, MediaListenerService::class.java)
            return flat.split(':').any { ComponentName.unflattenFromString(it) == expected }
        }
    }
}
