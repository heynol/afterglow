package dev.heynol.afterglow.audio

import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/**
 * Tells music from speech using a few robust cues over the last seconds of audio:
 *
 * - **Rhythm**: onset autocorrelation peaks at a beat period (0.3–1 s).
 * - **Syllabic modulation**: speech loudness pulses at 3–7 Hz; music far less so.
 * - **Pauses**: speech stops between phrases; music rarely falls silent.
 * - **Tonality**: sustained, peaky chroma (chords, melody) instead of gliding or noisy pitch.
 * - **Bass**: music carries energy below 150 Hz; voices hardly do.
 *
 * Input arrives per analysis hop at any rate and is resampled to [FRAME_RATE] frames per second.
 * [score] is 0 (speech/noise) .. 1 (music); scores are published every 0.5 s once 4 s are available.
 */
class MusicClassifier {

    @Volatile
    var score = 0f
        private set

    @Volatile
    var ready = false
        private set

    /** Sub-scores for the diagnostics screen: rhythm, modulation, pauses, tonality, bass. */
    @Volatile
    var details = FloatArray(5)
        private set

    private val loud = FloatArray(CAPACITY)
    private val onset = FloatArray(CAPACITY)
    private val speech = FloatArray(CAPACITY)
    private val bass = FloatArray(CAPACITY)
    private val chroma = Array(CAPACITY) { FloatArray(12) }
    private var pos = 0
    private var count = 0
    private var sinceScore = 0

    // Accumulator for the current 20 ms frame
    private var accTime = 0f
    private var accN = 0
    private var accLoud = 0f
    private var accOnset = 0f
    private var accSpeech = 0f
    private var accBass = 0f
    private val accChroma = FloatArray(12)

    fun reset() {
        count = 0
        pos = 0
        sinceScore = 0
        accTime = 0f
        accN = 0
        score = 0f
        ready = false
    }

    fun update(dt: Float, rmsDb: Float, onset: Float, bassRatio: Float, speechAmp: Float, chroma: FloatArray) {
        accTime += dt
        accN++
        accLoud += rmsDb
        accOnset = max(accOnset, onset)
        accSpeech += speechAmp
        accBass += bassRatio
        for (i in 0 until 12) accChroma[i] += chroma[i]
        while (accTime >= FRAME) {
            accTime -= FRAME
            push()
        }
    }

    private fun push() {
        val n = max(accN, 1)
        loud[pos] = accLoud / n
        onset[pos] = accOnset
        speech[pos] = accSpeech / n
        bass[pos] = accBass / n
        for (i in 0 until 12) chroma[pos][i] = accChroma[i] / n
        pos = (pos + 1) % CAPACITY
        count = min(count + 1, CAPACITY)
        // Several frames can be emitted from one slow hop (Visualizer polling); they share values.
        if (accTime < FRAME) {
            accN = 0
            accLoud = 0f
            accOnset = 0f
            accSpeech = 0f
            accBass = 0f
            accChroma.fill(0f)
        }
        if (++sinceScore >= FRAME_RATE / 2 && count >= FRAME_RATE * 4) {
            sinceScore = 0
            computeScore()
        }
    }

    /** Value [back] frames ago (0 = newest). */
    private fun at(buf: FloatArray, back: Int) = buf[(pos - 1 - back + CAPACITY * 2) % CAPACITY]

    private fun computeScore() {
        val n = count
        val rhythm = rhythmScore(min(n, FRAME_RATE * 6))
        val modulation = modulationScore(min(n, 256))
        val pauses = pauseScore(n)
        val tonal = tonalScore(n)
        var bassSum = 0f
        for (i in 0 until n) bassSum += at(bass, i)
        val bassScore = smoothstep(0.03f, 0.18f, bassSum / n)

        details = floatArrayOf(rhythm, modulation, pauses, tonal, bassScore)
        val combined = 0.3f * rhythm + 0.2f * modulation + 0.2f * pauses + 0.15f * tonal + 0.15f * bassScore
        score = if (ready) score * 0.5f + combined * 0.5f else combined
        ready = true
    }

    /** Autocorrelation of the onset envelope at beat periods relative to lag 0. */
    private fun rhythmScore(n: Int): Float {
        var mean = 0f
        for (i in 0 until n) mean += at(onset, i)
        mean /= n
        val x = FloatArray(n) { at(onset, n - 1 - it) - mean }
        var r0 = 0f
        for (v in x) r0 += v * v
        if (r0 < 1e-6f) return 0f
        var best = 0f
        for (lag in FRAME_RATE * 3 / 10..FRAME_RATE) {
            var r = 0f
            for (i in 0 until n - lag) r += x[i] * x[i + lag]
            best = max(best, r / r0 * n / (n - lag))
        }
        return smoothstep(0.12f, 0.4f, best)
    }

    /** Share of loudness modulation at syllable rate (2.5–7 Hz) in the speech band. Low = musical. */
    private fun modulationScore(n: Int): Float {
        if (n < 128) return 0.5f
        val env = FloatArray(n) { ln1p(at(speech, n - 1 - it) * 200f) }
        val mean = env.average().toFloat()
        var total = 0f
        for (i in env.indices) {
            env[i] -= mean
            total += env[i] * env[i]
        }
        if (total < 1e-5f) return 0.5f
        var syllabic = 0f
        var broad = 0f
        // Goertzel power in 0.5 Hz steps from 0.5 to 12 Hz
        var hz = 0.5f
        while (hz <= 12f) {
            val w = 2.0 * PI * hz / FRAME_RATE
            val c = 2.0 * cos(w)
            var s1 = 0.0
            var s2 = 0.0
            for (i in env.indices) {
                val hann = 0.5 - 0.5 * cos(2.0 * PI * i / (n - 1))
                val s0 = env[i] * hann + c * s1 - s2
                s2 = s1
                s1 = s0
            }
            val p = (s1 * s1 + s2 * s2 - c * s1 * s2).toFloat()
            broad += p
            if (hz in 2.5f..7f) syllabic += p
            hz += 0.5f
        }
        val ratio = if (broad > 0f) syllabic / broad else 0f
        return 1f - smoothstep(0.3f, 0.6f, ratio)
    }

    /** Speech pauses: share of frames well below the recent loud level. */
    private fun pauseScore(n: Int): Float {
        val sorted = FloatArray(n) { at(loud, it) }.also { it.sort() }
        val loudLevel = sorted[(n * 0.9f).toInt().coerceAtMost(n - 1)]
        if (loudLevel < -60f) return 0f
        val threshold = loudLevel - 18f
        var quiet = 0
        for (v in sorted) if (v < threshold) quiet++ else break
        return 1f - smoothstep(0.04f, 0.2f, quiet.toFloat() / n)
    }

    /** Stable, peaky chroma across half-second blocks. */
    private fun tonalScore(n: Int): Float {
        val block = FRAME_RATE / 2
        val blocks = min(n / block, 10)
        if (blocks < 3) return 0.5f
        var prev: FloatArray? = null
        var similarity = 0f
        var peakiness = 0f
        for (b in 0 until blocks) {
            val v = FloatArray(12)
            for (i in 0 until block) {
                val frame = chroma[(pos - 1 - b * block - i + CAPACITY * 2) % CAPACITY]
                for (k in 0 until 12) v[k] += frame[k]
            }
            var norm = 0f
            var maxV = 0f
            var sum = 0f
            for (k in 0 until 12) {
                norm += v[k] * v[k]
                maxV = max(maxV, v[k])
                sum += v[k]
            }
            norm = sqrt(norm)
            if (norm < 1e-9f) return 0f
            for (k in 0 until 12) v[k] /= norm
            peakiness += if (maxV > 0f) (maxV - sum / 12f) / maxV else 0f
            prev?.let { p ->
                var dot = 0f
                for (k in 0 until 12) dot += p[k] * v[k]
                similarity += dot
            }
            prev = v
        }
        similarity /= (blocks - 1)
        peakiness /= blocks
        return smoothstep(0.7f, 0.92f, similarity) * smoothstep(0.35f, 0.65f, peakiness)
    }

    private fun ln1p(x: Float) = kotlin.math.ln(1f + x)

    private fun smoothstep(a: Float, b: Float, x: Float): Float {
        val t = ((x - a) / (b - a)).coerceIn(0f, 1f)
        return t * t * (3f - 2f * t)
    }

    companion object {
        const val FRAME_RATE = 50
        private const val FRAME = 1f / FRAME_RATE
        private const val CAPACITY = FRAME_RATE * 8
    }
}
