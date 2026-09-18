package dev.heynol.afterglow.audio

import android.Manifest
import android.annotation.SuppressLint
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioPlaybackCaptureConfiguration
import android.media.AudioRecord
import android.media.audiofx.Visualizer
import android.media.projection.MediaProjection
import android.os.Process
import android.util.Log
import androidx.annotation.RequiresPermission
import java.util.Random
import kotlin.math.PI
import kotlin.math.exp
import kotlin.math.sin

/** Delivers audio into an [AudioAnalyzer] on its own thread. */
interface AudioSource {
    /** Starts capturing. Returns false if the source can't run here. [onError] fires if it dies later. */
    fun start(analyzer: AudioAnalyzer, onError: () -> Unit): Boolean
    fun stop()
}

/**
 * System audio through MediaProjection playback capture: full-quality stereo PCM of every app that
 * allows capture (YouTube does). Needs RECORD_AUDIO and a live [MediaProjection].
 */
class PlaybackCaptureSource(private val projection: MediaProjection) : AudioSource {

    @Volatile
    private var running = false
    private var thread: Thread? = null
    private var record: AudioRecord? = null

    @SuppressLint("MissingPermission")
    override fun start(analyzer: AudioAnalyzer, onError: () -> Unit): Boolean {
        val rec = try {
            build()
        } catch (e: Exception) {
            Log.w(TAG, "playback capture unavailable", e)
            return false
        }
        try {
            rec.startRecording()
        } catch (e: IllegalStateException) {
            Log.w(TAG, "startRecording failed", e)
            rec.release()
            return false
        }
        if (rec.recordingState != AudioRecord.RECORDSTATE_RECORDING) {
            rec.release()
            return false
        }
        record = rec
        running = true
        thread = Thread({
            Process.setThreadPriority(Process.THREAD_PRIORITY_URGENT_AUDIO)
            val buf = FloatArray(FRAMES * 2)
            while (running) {
                val n = rec.read(buf, 0, buf.size, AudioRecord.READ_BLOCKING)
                if (n < 0) {
                    Log.w(TAG, "read error $n")
                    if (running) onError()
                    break
                }
                if (n > 0) analyzer.processStream(buf, n / 2, 2, RATE)
            }
        }, "PlaybackCapture").also { it.start() }
        return true
    }

    @RequiresPermission(Manifest.permission.RECORD_AUDIO)
    private fun build(): AudioRecord {
        val config = AudioPlaybackCaptureConfiguration.Builder(projection)
            .addMatchingUsage(AudioAttributes.USAGE_MEDIA)
            .addMatchingUsage(AudioAttributes.USAGE_GAME)
            .addMatchingUsage(AudioAttributes.USAGE_UNKNOWN)
            .build()
        val format = AudioFormat.Builder()
            .setEncoding(AudioFormat.ENCODING_PCM_FLOAT)
            .setSampleRate(RATE)
            .setChannelMask(AudioFormat.CHANNEL_IN_STEREO)
            .build()
        val min = AudioRecord.getMinBufferSize(RATE, AudioFormat.CHANNEL_IN_STEREO, AudioFormat.ENCODING_PCM_FLOAT)
        return AudioRecord.Builder()
            .setAudioFormat(format)
            .setBufferSizeInBytes(maxOf(min, FRAMES * 2 * 4 * 4))
            .setAudioPlaybackCaptureConfig(config)
            .build()
    }

    override fun stop() {
        running = false
        record?.let { runCatching { it.stop() } }
        thread?.join(500)
        record?.release()
        record = null
        thread = null
    }

    private companion object {
        const val TAG = "PlaybackCapture"
        const val RATE = 48000
        const val FRAMES = 480
    }
}

/**
 * The output mix through the Visualizer effect on session 0. Works without MediaProjection, but only
 * yields 8-bit mono snapshots, and stays silent on outputs that bypass the mixer (tunnelled or
 * passthrough playback on some TVs). Needs RECORD_AUDIO and MODIFY_AUDIO_SETTINGS.
 */
class VisualizerSource : AudioSource {

    @Volatile
    private var running = false
    private var thread: Thread? = null
    private var visualizer: Visualizer? = null

    override fun start(analyzer: AudioAnalyzer, onError: () -> Unit): Boolean {
        val v = try {
            Visualizer(0).apply {
                enabled = false
                captureSize = Visualizer.getCaptureSizeRange()[1].coerceAtMost(1024)
                scalingMode = Visualizer.SCALING_MODE_AS_PLAYED
                enabled = true
            }
        } catch (e: Exception) {
            Log.w(TAG, "Visualizer unavailable", e)
            return false
        }
        visualizer = v
        val size = v.captureSize
        val rate = v.samplingRate / 1000 // milliHertz
        running = true
        thread = Thread({
            Process.setThreadPriority(Process.THREAD_PRIORITY_AUDIO)
            val bytes = ByteArray(size)
            val mono = FloatArray(size)
            while (running) {
                val status = try {
                    v.getWaveForm(bytes)
                } catch (e: IllegalStateException) {
                    Visualizer.ERROR
                }
                if (status == Visualizer.SUCCESS) {
                    for (i in 0 until size) mono[i] = ((bytes[i].toInt() and 0xFF) - 128) / 128f
                    analyzer.processWindow(mono, size, rate)
                } else if (status == Visualizer.ERROR_DEAD_OBJECT) {
                    if (running) onError()
                    break
                }
                try {
                    Thread.sleep(POLL_MS)
                } catch (_: InterruptedException) {
                    break
                }
            }
        }, "VisualizerSource").also { it.start() }
        return true
    }

    override fun stop() {
        running = false
        thread?.interrupt()
        thread?.join(500)
        visualizer?.let { runCatching { it.enabled = false }; it.release() }
        visualizer = null
        thread = null
    }

    private companion object {
        const val TAG = "VisualizerSource"
        const val POLL_MS = 14L
    }
}

/**
 * A procedurally generated house loop for previews in the settings screen: kick, snare, hats,
 * bassline, detuned chord pads and an arpeggio, with drops and breakdowns every 16 bars.
 */
class DemoSource : AudioSource {

    @Volatile
    private var running = false
    private var thread: Thread? = null

    override fun start(analyzer: AudioAnalyzer, onError: () -> Unit): Boolean {
        running = true
        thread = Thread({
            val synth = DemoSynth()
            val buf = FloatArray(BLOCK * 2)
            var next = System.nanoTime()
            while (running) {
                synth.render(buf, BLOCK)
                analyzer.processStream(buf, BLOCK, 2, DemoSynth.RATE)
                next += BLOCK * 1_000_000_000L / DemoSynth.RATE
                val sleep = (next - System.nanoTime()) / 1_000_000L
                if (sleep > 0) {
                    try {
                        Thread.sleep(sleep)
                    } catch (_: InterruptedException) {
                        break
                    }
                } else if (sleep < -200) {
                    next = System.nanoTime() // fell behind (e.g. device asleep): don't burst
                }
            }
        }, "DemoSource").also { it.start() }
        return true
    }

    override fun stop() {
        running = false
        thread?.interrupt()
        thread?.join(500)
        thread = null
    }

    private companion object {
        const val BLOCK = 480
    }
}

/** Sample-by-sample synthesiser behind [DemoSource]. */
class DemoSynth(seed: Long = 7) {
    private val rnd = Random(seed)
    private var n = 0L
    private var kickPhase = 0.0
    private val padPhases = DoubleArray(6)
    private var bassPhase = 0.0
    private var arpPhase = 0.0
    private var lp = 0.0

    fun render(out: FloatArray, frames: Int) {
        for (i in 0 until frames) {
            val t = n.toDouble() / RATE
            val beats = t * BPM / 60.0
            val beatPhase = beats % 1.0
            val bar = (beats / 4).toInt()
            val section = (bar / 16) % 4 // 0 groove, 1 drop, 2 breakdown, 3 build
            val drums = section != 2
            val chord = CHORDS[bar % 4]

            val tb = beatPhase * 60.0 / BPM
            kickPhase += 2 * PI * (48.0 + 120.0 * exp(-tb * 35.0)) / RATE
            val kick = if (drums) sin(kickPhase) * exp(-tb * 8.0) else 0.0

            val beatIndex = beats.toInt()
            val snare = if (drums && beatIndex % 2 == 1) (rnd.nextDouble() - 0.5) * exp(-tb * 18.0) * 0.6 + sin(2 * PI * 190 * t) * exp(-tb * 25.0) * 0.3 else 0.0
            val hatPhase = (beats * 2) % 1.0
            val hat = if (drums) (rnd.nextDouble() - 0.5) * exp(-hatPhase * 40.0) * (if (section == 1) 0.35 else 0.2) else 0.0

            var pad = 0.0
            for (k in 0 until 3) {
                val f = midi(chord[k])
                padPhases[k * 2] += 2 * PI * f * 1.003 / RATE
                padPhases[k * 2 + 1] += 2 * PI * f * 0.997 / RATE
                pad += saw(padPhases[k * 2]) + saw(padPhases[k * 2 + 1])
            }
            lp += (pad - lp) * (if (section == 2) 0.02 else 0.06)
            val padOut = lp * 0.05 * (if (section == 2) 1.6 else 1.0)

            val sixteenth = (beats * 4).toInt()
            val bassNote = midi(chord[0] - 24 + if (sixteenth % 4 == 2) 12 else 0)
            bassPhase += 2 * PI * bassNote / RATE
            val bassEnv = exp(-((beats * 4) % 1.0) * 3.0)
            val bass = if (drums) sin(bassPhase) * 0.45 * bassEnv + saw(bassPhase) * 0.05 else 0.0

            val arpNote = midi(chord[sixteenth % 3] + 12)
            arpPhase += 2 * PI * arpNote / RATE
            val arp = (if (sin(arpPhase) > 0) 1.0 else -1.0) * exp(-((beats * 4) % 1.0) * 6.0) * (if (section == 3 || section == 1) 0.06 else 0.03)

            val mono = kick * 0.9 + snare + hat + bass
            out[i * 2] = ((mono + padOut * 1.1 + arp * 1.3) * 0.32).toFloat()
            out[i * 2 + 1] = ((mono + padOut * 0.9 + arp * 0.7) * 0.32).toFloat()
            n++
        }
    }

    private fun saw(phase: Double) = ((phase / (2 * PI)) % 1.0) * 2.0 - 1.0

    private fun midi(note: Int) = 440.0 * Math.pow(2.0, (note - 69) / 12.0)

    companion object {
        const val RATE = 48000
        private const val BPM = 122.0
        private val CHORDS = arrayOf(intArrayOf(57, 60, 64), intArrayOf(53, 57, 60), intArrayOf(48, 52, 55), intArrayOf(55, 59, 62))
    }
}
