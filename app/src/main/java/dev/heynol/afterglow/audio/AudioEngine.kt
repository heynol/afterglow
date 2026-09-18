package dev.heynol.afterglow.audio

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.media.AudioManager
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import dev.heynol.afterglow.settings.AudioSourceMode

/**
 * Process-wide owner of the audio source and analyzer. Clients say what they need:
 * the service wants real audio during a session, preview screens are happy with the demo loop.
 * Real audio wins when both are active.
 *
 * In AUTO mode the Visualizer effect is preferred: it needs no MediaProjection, so the TV shows no
 * recording indicator. Playback capture is only used after the Visualizer effect stayed silent while
 * the system reported playback (tunnelled or passthrough outputs), and [needsProjection] tells the
 * service when to hold one. A watchdog switches sources when the active one only hears silence.
 *
 * Main thread only.
 */
class AudioEngine private constructor(private val context: Context) {

    enum class Kind { NONE, CAPTURE, VISUALIZER, DEMO }

    val analyzer = AudioAnalyzer()

    var kind = Kind.NONE
        private set

    var mode = AudioSourceMode.AUTO
        set(value) {
            if (field == value) return
            field = value
            reconcile(force = true)
        }

    private val handler = Handler(Looper.getMainLooper())
    private val audioManager = context.getSystemService(AudioManager::class.java)
    private val realClients = HashSet<Any>()
    private val demoClients = HashSet<Any>()
    private val listeners = LinkedHashSet<(Kind) -> Unit>()
    private var source: AudioSource? = null
    private var silentSince = 0L
    private var captureSuspectUntil = 0L
    private var visualizerDeafUntil = 0L
    private val demandListeners = LinkedHashSet<() -> Unit>()
    private val probe = AudioFeatures()
    private var debugTicks = 0

    private val projectionListener: () -> Unit = { handler.post { reconcile(force = false) } }

    private val watchdog = object : Runnable {
        override fun run() {
            checkSilence()
            handler.postDelayed(this, WATCHDOG_MS)
        }
    }

    init {
        Projection.addListener(projectionListener)
    }

    val hasPermission: Boolean
        get() = context.checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED

    fun acquire(client: Any, real: Boolean) {
        (if (real) realClients else demoClients).add(client)
        (if (real) demoClients else realClients).remove(client)
        reconcile(force = false)
    }

    fun release(client: Any) {
        realClients.remove(client)
        demoClients.remove(client)
        reconcile(force = false)
    }

    fun addListener(listener: (Kind) -> Unit) = listeners.add(listener)

    /** Called when [needsProjection] may have changed. */
    fun addDemandListener(listener: () -> Unit) = demandListeners.add(listener)

    fun removeDemandListener(listener: () -> Unit) = demandListeners.remove(listener)

    /** Whether playback capture is wanted: chosen explicitly, or the Visualizer effect can't hear anything. */
    val needsProjection: Boolean
        get() = hasPermission && when (mode) {
            AudioSourceMode.CAPTURE -> true
            AudioSourceMode.VISUALIZER -> false
            AudioSourceMode.AUTO -> SystemClock.elapsedRealtime() < visualizerDeafUntil
        }

    fun removeListener(listener: (Kind) -> Unit) = listeners.remove(listener)

    /** True if the system says some app is playing audio. */
    val systemPlaying: Boolean get() = audioManager.isMusicActive

    private fun desiredKind(): Kind = when {
        realClients.isNotEmpty() && hasPermission -> when (mode) {
            AudioSourceMode.CAPTURE -> if (Projection.current != null) Kind.CAPTURE else Kind.VISUALIZER
            AudioSourceMode.VISUALIZER -> Kind.VISUALIZER
            AudioSourceMode.AUTO -> {
                val now = SystemClock.elapsedRealtime()
                if (Projection.current != null && now < visualizerDeafUntil && now >= captureSuspectUntil) Kind.CAPTURE else Kind.VISUALIZER
            }
        }
        realClients.isNotEmpty() || demoClients.isNotEmpty() -> Kind.DEMO
        else -> Kind.NONE
    }

    private fun reconcile(force: Boolean) {
        val want = desiredKind()
        if (want == kind && !force && !(want == Kind.CAPTURE && sourceProjectionChanged())) return
        switchTo(want)
    }

    private var sourceProjection: Any? = null

    private fun sourceProjectionChanged() = sourceProjection !== Projection.current

    private fun switchTo(want: Kind) {
        source?.stop()
        source = null
        sourceProjection = null
        analyzer.reset()
        var started = want
        val next: AudioSource? = when (want) {
            Kind.NONE -> null
            Kind.DEMO -> DemoSource()
            Kind.VISUALIZER -> VisualizerSource()
            Kind.CAPTURE -> Projection.current?.let { PlaybackCaptureSource(it) }
        }
        if (next != null && !next.start(analyzer) { handler.post { onSourceError() } }) {
            Log.w(TAG, "$want failed to start")
            if (want == Kind.VISUALIZER) markVisualizerDeaf()
            started = if (want == Kind.CAPTURE) {
                captureSuspectUntil = SystemClock.elapsedRealtime() + RETRY_CAPTURE_MS
                if (VisualizerSource().let { v -> v.start(analyzer) { handler.post { onSourceError() } }.also { ok -> if (ok) source = v } }) Kind.VISUALIZER else Kind.NONE
            } else {
                Kind.NONE
            }
        } else {
            source = next
            if (want == Kind.CAPTURE) sourceProjection = Projection.current
        }
        silentSince = 0L
        handler.removeCallbacks(watchdog)
        if (started == Kind.CAPTURE || started == Kind.VISUALIZER) handler.postDelayed(watchdog, WATCHDOG_MS)
        if (started != kind) {
            Log.i(TAG, "source $kind -> $started")
            kind = started
            listeners.toList().forEach { it(started) }
        }
    }

    private fun markVisualizerDeaf() {
        if (mode != AudioSourceMode.AUTO) return
        visualizerDeafUntil = SystemClock.elapsedRealtime() + VISUALIZER_DEAF_MS
        demandListeners.toList().forEach { it() }
        handler.post { reconcile(force = false) }
    }

    private fun onSourceError() {
        if (kind == Kind.CAPTURE) captureSuspectUntil = SystemClock.elapsedRealtime() + RETRY_CAPTURE_MS
        switchTo(desiredKind())
    }

    /** Silence while the system reports playback means this source can't hear the app: try the other one. */
    private fun checkSilence() {
        analyzer.read(probe)
        if (Log.isLoggable(TAG, Log.DEBUG) && ++debugTicks % 5 == 0) {
            Log.d(TAG, "%s level=%.3f bass=%.2f mid=%.2f treble=%.2f silent=%b playing=%b beats=%d".format(kind, probe.level, probe.bass, probe.mid, probe.treble, probe.silent, systemPlaying, probe.beatCount))
        }
        val silent = probe.silent
        val now = SystemClock.elapsedRealtime()
        if (!silent || !systemPlaying) {
            silentSince = 0L
            return
        }
        if (silentSince == 0L) silentSince = now
        if (now - silentSince < SILENT_SWITCH_MS || mode != AudioSourceMode.AUTO) return
        silentSince = 0L
        when (kind) {
            Kind.CAPTURE -> {
                Log.i(TAG, "capture silent during playback, trying Visualizer")
                captureSuspectUntil = now + RETRY_CAPTURE_MS
                switchTo(desiredKind())
            }
            Kind.VISUALIZER -> {
                Log.i(TAG, "Visualizer effect silent during playback, capture needed")
                markVisualizerDeaf()
                if (Projection.current != null && now >= captureSuspectUntil) switchTo(Kind.CAPTURE)
            }
            else -> Unit
        }
    }

    companion object {
        private const val TAG = "AudioEngine"
        private const val WATCHDOG_MS = 1000L
        private const val SILENT_SWITCH_MS = 4000L
        private const val RETRY_CAPTURE_MS = 60_000L
        private const val VISUALIZER_DEAF_MS = 30 * 60_000L

        @Volatile
        private var instance: AudioEngine? = null

        fun get(context: Context): AudioEngine = instance ?: synchronized(this) {
            instance ?: AudioEngine(context.applicationContext).also { instance = it }
        }
    }
}
