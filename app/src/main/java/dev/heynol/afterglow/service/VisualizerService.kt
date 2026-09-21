package dev.heynol.afterglow.service

import android.accessibilityservice.AccessibilityButtonController
import android.accessibilityservice.AccessibilityService
import android.content.ComponentName
import android.content.Context
import android.content.SharedPreferences
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.provider.Settings
import android.util.Log
import android.view.accessibility.AccessibilityEvent
import dev.heynol.afterglow.audio.AudioEngine
import dev.heynol.afterglow.audio.AudioFeatures
import dev.heynol.afterglow.audio.MetadataHints
import dev.heynol.afterglow.audio.NowPlaying
import dev.heynol.afterglow.audio.Projection
import dev.heynol.afterglow.overlay.OverlayController
import dev.heynol.afterglow.overlay.OverlayMode
import dev.heynol.afterglow.settings.AppSettings
import dev.heynol.afterglow.settings.AudioSourceMode
import dev.heynol.afterglow.settings.Detection
import dev.heynol.afterglow.settings.VisualPrefs
import dev.heynol.afterglow.visuals.Catalog
import java.lang.ref.WeakReference

/**
 * The always-on part: notices when an enabled app (YouTube by default) plays music, captures and
 * analyses its audio, and drives the overlay.
 *
 * Flow: foreground app → [SessionPolicy] → audio capture while the session lasts → [MusicGate]
 * (classifier + metadata) → overlay preview → the viewer takes it from there.
 */
class VisualizerService : AccessibilityService() {

    private val handler = Handler(Looper.getMainLooper())
    private lateinit var settings: AppSettings
    private lateinit var visualPrefs: VisualPrefs
    private lateinit var engine: AudioEngine
    private lateinit var tracker: ForegroundAppTracker
    private lateinit var policy: SessionPolicy
    private lateinit var nowPlaying: NowPlayingWatcher
    private var overlay: OverlayController? = null
    private val gate = MusicGate()
    private val trackChanges = TrackChangeDetector()
    private val probe = AudioFeatures()
    private var passiveSince = 0L
    private var testMode = false
    private var lastProjectionAttempt = 0L

    private val settingsListener = SharedPreferences.OnSharedPreferenceChangeListener { _, key -> onSettingsChanged(key) }
    private val visualsListener = SharedPreferences.OnSharedPreferenceChangeListener { _, _ -> overlay?.applySettings() }

    private val tick = object : Runnable {
        override fun run() {
            evaluate()
            handler.postDelayed(this, TICK_MS)
        }
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        settings = AppSettings(this)
        val catalog = Catalog.get(this)
        visualPrefs = VisualPrefs(this, catalog)
        engine = AudioEngine.get(this)
        engine.mode = settings.audioSource
        gate.mode = if (settings.detection == Detection.ANY_PLAYBACK) MusicGate.Mode.ANY_PLAYBACK else MusicGate.Mode.AUTO
        policy = SessionPolicy(isEnabledApp = { settings.enabled && it in settings.enabledApps }, isTransient = { tracker.isTransient(it) })
        tracker = ForegroundAppTracker(this, ::onForeground)
        nowPlaying = NowPlayingWatcher(this, ::onNowPlaying)
        overlay = OverlayController(this, settings, catalog, visualPrefs, object : OverlayController.Listener {
            override fun onDismissed() {
                if (testMode) endTest() else policy.dismiss()
                Diagnostics.dismissed = policy.dismissed
            }

            override fun onModeChanged(mode: OverlayMode) {
                Diagnostics.overlayMode = mode
                passiveSince = if (mode == OverlayMode.MINI) System.nanoTime() else 0L
                if (mode == OverlayMode.HIDDEN && testMode) endTest()
            }
        })
        settings.registerListener(settingsListener)
        visualPrefs.registerListener(visualsListener)
        instance = WeakReference(this)
        Diagnostics.serviceRunning = true
        engine.addDemandListener(demandListener)
        accessibilityButtonController.registerAccessibilityButtonCallback(buttonCallback, handler)
        Projection.addListener(projectionListener)
        handler.post(::updateProjection)
        handler.postDelayed(::guessForeground, 1_000L)
        handler.postDelayed(::prewarmThumbnails, 45_000L)
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        event?.let { tracker.onAccessibilityEvent(it) }
    }

    override fun onInterrupt() = Unit

    override fun onDestroy() {
        instance = null
        Diagnostics.serviceRunning = false
        handler.removeCallbacksAndMessages(null)
        if (::settings.isInitialized) {
            settings.unregisterListener(settingsListener)
            visualPrefs.unregisterListener(visualsListener)
            nowPlaying.stop()
            engine.release(this)
            engine.release(TEST_CLIENT)
            engine.removeDemandListener(demandListener)
            accessibilityButtonController.unregisterAccessibilityButtonCallback(buttonCallback)
            Projection.removeListener(projectionListener)
        }
        overlay?.release()
        overlay = null
        super.onDestroy()
    }

    // --- Session ----------------------------------------------------------------------------------

    private fun onForeground(pkg: String) {
        Diagnostics.foreground = pkg
        if (testMode) endTest()
        if (!policy.onForeground(pkg)) return
        Log.i(TAG, "session: ${policy.sessionPackage}")
        onSessionChanged()
    }

    private val buttonCallback = object : AccessibilityButtonController.AccessibilityButtonCallback() {
        override fun onClicked(controller: AccessibilityButtonController) = onShortcut()
    }

    /** Accessibility shortcut (TV: hold Back + ▼): bring the visualizer back, even after X. */
    private fun onShortcut() {
        val o = overlay ?: return
        Log.i(TAG, "shortcut in ${o.mode}, session ${policy.sessionPackage}")
        if (testMode) return o.onShortcut()
        if (!policy.active) return
        policy.undismiss()
        Diagnostics.dismissed = false
        o.onShortcut()
    }

    /**
     * Window changes are the only foreground signal, and none arrives when the service starts while
     * an app is already open (after an update or reboot into YouTube). An enabled app playing media is
     * the best guess until then; the first remote press in the app confirms or corrects it.
     */
    private fun guessForeground() {
        if (tracker.foreground != null) return
        val playing = nowPlaying.playingPackages().firstOrNull { settings.enabled && it in settings.enabledApps }
        if (playing != null) {
            Log.i(TAG, "assuming $playing is in front")
            tracker.assume(playing)
        } else {
            // Apps like YouTube draw their own UI and send no focus events, so keep looking.
            handler.postDelayed(::guessForeground, GUESS_INTERVAL_MS)
        }
    }

    private fun onSessionChanged() {
        val active = policy.active
        Diagnostics.sessionPackage = policy.sessionPackage
        Diagnostics.dismissed = policy.dismissed
        gate.reset()
        trackChanges.reset()
        overlay?.hide()
        handler.removeCallbacks(tick)
        if (active) {
            engine.acquire(this, real = true)
            nowPlaying.follow(policy.sessionPackage)
            handler.post(tick)
        } else {
            engine.release(this)
            nowPlaying.follow(null)
        }
        updateProjection()
    }

    private fun onNowPlaying(np: NowPlaying?) {
        Diagnostics.nowPlaying = np
        overlay?.setNowPlaying(np)
        if (np != null && trackChanges.onMetadata(np.title, np.artist)) onTrackChanged(np)
    }

    private fun onTrackChanged(np: NowPlaying?) {
        Log.d(TAG, "track changed: ${np?.title}")
        val o = overlay ?: return
        if (o.mode == OverlayMode.HIDDEN && gate.detected && policy.mayShow) {
            // Passive preview was auto-hidden; bring it back for the new song.
            o.show(offerFocus = tracker.remoteIdleFor(REMOTE_IDLE_MS))
        } else {
            o.onTrackChanged(np, remoteIdle = tracker.remoteIdleFor(REMOTE_IDLE_MS))
        }
    }

    /** Runs every [TICK_MS] during a session. */
    private fun evaluate() {
        val o = overlay ?: return
        if (!policy.active) return
        val np = nowPlaying.current
        engine.analyzer.read(probe)
        val realAudio = engine.kind == AudioEngine.Kind.CAPTURE || engine.kind == AudioEngine.Kind.VISUALIZER
        val audible = realAudio && !probe.silent
        val playing = np?.playing ?: (engine.systemPlaying || audible)
        val classifier = engine.analyzer.classifier
        // Only trust the classifier while it hears something; a silent source says nothing.
        val score = if (realAudio && classifier.ready && (audible || !engine.systemPlaying)) classifier.score else null
        val prior = MetadataHints.prior(np)
        val changed = gate.update(playing, score, prior)

        Diagnostics.audioKind = engine.kind
        Diagnostics.classifierScore = if (classifier.ready) classifier.score else -1f
        Diagnostics.classifierDetails = classifier.details
        Diagnostics.probability = gate.probability
        Diagnostics.musicDetected = gate.detected
        Diagnostics.level = probe.level

        if (realAudio && trackChanges.onAudio(probe.silent, hasMetadata = np?.title != null)) onTrackChanged(np)

        if (changed) {
            if (gate.detected && policy.mayShow && o.mode == OverlayMode.HIDDEN) {
                o.show(offerFocus = tracker.remoteIdleFor(REMOTE_IDLE_MS))
            } else if (!gate.detected && o.mode == OverlayMode.MINI) {
                o.hide()
            }
        }

        val hideAfter = settings.passiveHideSeconds
        if (hideAfter > 0 && o.mode == OverlayMode.MINI && o.state.focus == dev.heynol.afterglow.overlay.MiniFocus.NONE && passiveSince != 0L &&
            System.nanoTime() - passiveSince > hideAfter * 1_000_000_000L
        ) {
            o.hide()
        }
        if (o.state.focus != dev.heynol.afterglow.overlay.MiniFocus.NONE) passiveSince = System.nanoTime()
    }

    // --- Settings & projection ----------------------------------------------------------------------

    private fun onSettingsChanged(key: String?) {
        when (key) {
            "enabled", "apps" -> {
                val before = policy.sessionPackage
                policy.onAppsChanged(tracker.foreground)
                if (before != policy.sessionPackage || !settings.enabled) onSessionChanged()
            }
            "audio_source" -> {
                engine.mode = settings.audioSource
                updateProjection()
            }
            "detection" -> gate.mode = if (settings.detection == Detection.ANY_PLAYBACK) MusicGate.Mode.ANY_PLAYBACK else MusicGate.Mode.AUTO
            "current_visual", "hints_shown" -> return
        }
        overlay?.applySettings()
    }

    private val demandListener: () -> Unit = { updateProjection() }

    /** Projection listeners run on any thread. */
    private val projectionListener: () -> Unit = { handler.post(::updateProjection) }

    private val releaseProjection = Runnable { Projection.stop(this) }

    /**
     * Holds a MediaProjection only while a session needs playback capture: Android shows a recording
     * icon for as long as one exists. Released a little after the session ends, so a quick detour
     * (settings, another app) doesn't cost a new request.
     */
    private fun updateProjection() {
        val wanted = engine.needsProjection && policy.active
        if (wanted) {
            handler.removeCallbacks(releaseProjection)
            if (Projection.current != null) return
            val now = SystemClock.elapsedRealtime()
            if (lastProjectionAttempt != 0L && now - lastProjectionAttempt < 60_000L) return
            lastProjectionAttempt = now
            // Only if the system grants it without a dialog; otherwise the settings screen asks.
            Projection.request(this, onlyIfSilent = true)
        } else if (Projection.current != null && !handler.hasCallbacks(releaseProjection)) {
            handler.postDelayed(releaseProjection, PROJECTION_LINGER_MS)
        }
    }

    /** Renders carousel thumbnails ahead of time while nothing is on screen. */
    private fun prewarmThumbnails() {
        val o = overlay ?: return
        if (o.mode != OverlayMode.HIDDEN) {
            handler.postDelayed(::prewarmThumbnails, 60_000L)
            return
        }
        val cache = dev.heynol.afterglow.render.ThumbnailCache.get(this)
        visualPrefs.rotation().forEach { cache.load(it, visualPrefs.style(it)) {} }
    }

    // --- Test from the settings screen --------------------------------------------------------------

    private fun startTest() {
        val o = overlay ?: return
        testMode = true
        engine.acquire(TEST_CLIENT, real = false)
        o.hide()
        handler.postDelayed({ o.show(offerFocus = true) }, 300)
    }

    private fun endTest() {
        if (!testMode) return
        testMode = false
        engine.release(TEST_CLIENT)
        overlay?.takeIf { it.mode != OverlayMode.HIDDEN }?.hide()
    }

    companion object {
        private const val TAG = "VisualizerService"
        private const val TICK_MS = 250L
        private const val REMOTE_IDLE_MS = 4000L
        private const val PROJECTION_LINGER_MS = 30_000L
        private const val GUESS_INTERVAL_MS = 3_000L
        private const val SUMMON_DELAY_MS = 300L
        private val TEST_CLIENT = Any()

        @Volatile
        private var instance: WeakReference<VisualizerService>? = null

        val isRunning: Boolean get() = instance?.get() != null

        /**
         * The launcher entry was opened (e.g. with the ★ key): inside an enabled app this acts like the
         * accessibility shortcut. False when there is no session, so the caller opens the settings.
         */
        fun summon(): Boolean {
            val service = instance?.get() ?: return false
            if (!service.policy.active) return false
            // Once the launching activity is gone and the app has the focus again.
            service.handler.postDelayed({ service.onShortcut() }, SUMMON_DELAY_MS)
            return true
        }

        /** Shows the overlay with the demo loop over whatever is on screen. False if the service is off. */
        fun test(): Boolean {
            val service = instance?.get() ?: return false
            service.handler.post { service.startTest() }
            return true
        }

        fun isEnabledInSettings(context: Context): Boolean {
            val expected = ComponentName(context, VisualizerService::class.java)
            val enabled = Settings.Secure.getString(context.contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES) ?: return false
            return enabled.split(':').any { ComponentName.unflattenFromString(it) == expected }
        }
    }
}

/** Live state for the diagnostics card in the settings screen (same process). */
object Diagnostics {
    @Volatile var serviceRunning = false
    @Volatile var foreground: String? = null
    @Volatile var sessionPackage: String? = null
    @Volatile var dismissed = false
    @Volatile var audioKind = AudioEngine.Kind.NONE
    @Volatile var classifierScore = -1f
    @Volatile var classifierDetails = FloatArray(5)
    @Volatile var probability = 0f
    @Volatile var musicDetected = false
    @Volatile var level = 0f
    @Volatile var nowPlaying: NowPlaying? = null
    @Volatile var overlayMode = OverlayMode.HIDDEN
}
