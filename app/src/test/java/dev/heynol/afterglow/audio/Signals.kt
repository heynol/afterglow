package dev.heynol.afterglow.audio

import java.util.Random
import kotlin.math.PI
import kotlin.math.exp
import kotlin.math.max
import kotlin.math.sin

/** Synthetic test material at 48 kHz stereo. */
object Signals {
    const val RATE = 48000

    /** Four-on-the-floor at [bpm]: kick, bass, sustained chords, off-beat hats. */
    fun music(seconds: Float, bpm: Float = 120f, seed: Long = 1): FloatArray {
        val rnd = Random(seed)
        val n = (seconds * RATE).toInt()
        val out = FloatArray(n * 2)
        val beat = 60f / bpm
        val chords = arrayOf(floatArrayOf(220f, 261.63f, 329.63f), floatArrayOf(174.61f, 220f, 261.63f), floatArrayOf(130.81f, 164.81f, 196f), floatArrayOf(196f, 246.94f, 293.66f))
        var phaseKick = 0.0
        for (i in 0 until n) {
            val t = i.toFloat() / RATE
            val tb = t % beat
            val kickFreq = 50.0 + 110.0 * exp(-tb * 30.0)
            phaseKick += 2 * PI * kickFreq / RATE
            val kick = sin(phaseKick) * exp(-tb * 9.0) * 0.8
            val bar = (t / (beat * 4)).toInt()
            val chord = chords[bar % 4]
            var pad = 0.0
            for (f in chord) pad += sin(2 * PI * f * t) * 0.12 + sin(2 * PI * f * 2 * t) * 0.05
            val bass = sin(2 * PI * chord[0] / 4 * t) * 0.25
            val th = (t + beat / 2) % beat
            val hat = if (th < 0.03f) (rnd.nextFloat() - 0.5f) * 0.25 * exp(-th * 120.0) else 0.0
            val s = ((kick + pad + bass + hat) * 0.35).toFloat()
            out[i * 2] = s
            out[i * 2 + 1] = s * 0.9f + (pad * 0.03).toFloat()
        }
        return out
    }

    /** Speech-like: voiced syllables at ~4–5 Hz with gliding pitch, fricatives and phrase pauses. */
    fun speech(seconds: Float, seed: Long = 2): FloatArray {
        val rnd = Random(seed)
        val n = (seconds * RATE).toInt()
        val out = FloatArray(n * 2)
        var phraseEnd = 0f
        var pauseEnd = 0f
        var phase = 0.0
        var syllableRate = 4.5f
        for (i in 0 until n) {
            val t = i.toFloat() / RATE
            if (t >= pauseEnd) {
                phraseEnd = t + 0.8f + rnd.nextFloat() * 1.2f
                pauseEnd = phraseEnd + 0.25f + rnd.nextFloat() * 0.35f
                syllableRate = 3.8f + rnd.nextFloat() * 1.8f
            }
            val speaking = t < phraseEnd
            val syl = max(0.0, sin(2 * PI * syllableRate * t)).let { it * it }
            val f0 = 125.0 + 30.0 * sin(2 * PI * 0.7 * t) + 15.0 * sin(2 * PI * 3.1 * t)
            phase += 2 * PI * f0 / RATE
            var voice = 0.0
            for (h in 1..24) {
                val fh = f0 * h
                // Crude formants around 500 Hz and 1500 Hz
                val w = exp(-((fh - 500) / 300).let { it * it }) + 0.6 * exp(-((fh - 1500) / 500).let { it * it }) + 0.05
                voice += sin(phase * h) * w / h
            }
            val fricative = (rnd.nextFloat() - 0.5f) * 0.3f * (if (sin(2 * PI * syllableRate * 0.5 * t) > 0.9) 1f else 0f)
            val s = if (speaking) ((voice * syl + fricative * syl) * 0.3).toFloat() else (rnd.nextFloat() - 0.5f) * 0.0005f
            out[i * 2] = s
            out[i * 2 + 1] = s
        }
        return out
    }

    fun sine(seconds: Float, hz: Float, amp: Float = 0.5f): FloatArray {
        val n = (seconds * RATE).toInt()
        return FloatArray(n * 2) { (amp * sin(2 * PI * hz * (it / 2) / RATE)).toFloat() }
    }

    /**
     * Feeds [pcm] in 10 ms blocks with a fake clock advancing in real time, so time constants and
     * beat spacing behave as on a device.
     */
    fun feed(analyzer: AudioAnalyzer, clock: FakeClock, pcm: FloatArray, onBlock: () -> Unit = {}) {
        val block = RATE / 100
        val frames = pcm.size / 2
        val buf = FloatArray(block * 2)
        var i = 0
        while (i + block <= frames) {
            System.arraycopy(pcm, i * 2, buf, 0, block * 2)
            analyzer.processStream(buf, block, 2, RATE)
            clock.nanos += 10_000_000L
            onBlock()
            i += block
        }
    }
}

class FakeClock(var nanos: Long = 1_000_000_000L) : () -> Long {
    override fun invoke() = nanos
}
