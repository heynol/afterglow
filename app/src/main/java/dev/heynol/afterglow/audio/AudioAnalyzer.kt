package dev.heynol.afterglow.audio

import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.floor
import kotlin.math.ln
import kotlin.math.log10
import kotlin.math.log2
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.roundToInt
import kotlin.math.sqrt

/**
 * Turns PCM into [AudioFeatures]: log-frequency spectrum, band levels, bass transients, beats,
 * song energy and a trigger-stabilised waveform. Also feeds the [MusicClassifier].
 *
 * Two input modes:
 * - [processStream]: continuous interleaved PCM (playback capture, demo). Analyses every hop.
 * - [processWindow]: independent snapshots of the latest output (android.media.audiofx.Visualizer).
 *
 * Analysis runs on the caller's thread (one audio thread). Readers on other threads use [read],
 * which also applies the A/V delay and fades stale data.
 */
class AudioAnalyzer(private val clock: () -> Long = System::nanoTime) {

    val classifier = MusicClassifier()

    // --- Configuration (rebuilt when the sample rate or window size changes) ---
    private var sampleRate = 0
    private var fftSize = 0
    private var hop = 0
    private lateinit var fft: Fft
    private lateinit var window: FloatArray
    private var windowGain = 1f
    private lateinit var re: FloatArray
    private lateinit var im: FloatArray
    private lateinit var mag: FloatArray
    private lateinit var logMag: FloatArray
    private lateinit var prevLogMag: FloatArray
    private val binPos = FloatArray(AudioFeatures.BINS)
    private val binLo = IntArray(AudioFeatures.BINS)
    private val binHi = IntArray(AudioFeatures.BINS)
    private val binTilt = FloatArray(AudioFeatures.BINS)
    private lateinit var chromaIndex: IntArray
    private var bassEnd = 0
    private var midEnd = 0
    private var trebleEnd = 0
    private var speechStart = 0
    private var speechEnd = 0
    private var fluxEnd = 0
    private var lowFluxEnd = 0

    // --- Stream buffers ---
    private val ringL = FloatArray(RING)
    private val ringR = FloatArray(RING)
    private var writePos = 0
    private var sinceHop = 0
    private var filled = 0

    // --- State ---
    private val work = AudioFeatures()
    private var lastAnalysisNanos = 0L
    private var refDb = -40f
    private var bassPeak = FLOOR_AMP
    private var midPeak = FLOOR_AMP
    private var treblePeak = FLOOR_AMP
    private var levelPeak = FLOOR_AMP
    private var bassFast = 0f
    private var bassSlow = 0f
    private var longRms = 0f
    private var longPeak = FLOOR_AMP
    private var wavePeak = 0.05f
    private val raw = FloatArray(AudioFeatures.BINS)
    private val chroma = FloatArray(12)

    // Onset detection
    private val onsetHistory = FloatArray(ONSET_HISTORY)
    private var onsetPos = 0
    private var onsetCount = 0
    private var onsetPrev1 = 0f
    private var onsetPrev2 = 0f
    private var lowFluxMean = 1e-3f
    private var fullFluxMean = 1e-3f

    // --- Published frames (ring, newest at publishPos - 1) ---
    private val published = Array(PUBLISHED) { AudioFeatures() }
    private var publishPos = 0
    private var publishCount = 0
    private val lock = Any()

    @Volatile
    var lastInputNanos = 0L
        private set

    /** Continuous interleaved PCM (1 or 2 channels, float -1..1). */
    fun processStream(data: FloatArray, frames: Int, channels: Int, rate: Int) {
        configure(rate, if (rate >= 32000) 2048 else 1024)
        lastInputNanos = clock()
        var i = 0
        while (i < frames) {
            val l = data[i * channels]
            val r = if (channels > 1) data[i * channels + 1] else l
            ringL[writePos] = l
            ringR[writePos] = r
            writePos = (writePos + 1) and RING_MASK
            if (filled < RING) filled++
            if (++sinceHop >= hop) {
                sinceHop = 0
                if (filled >= fftSize + WAVE_SPAN) analyze()
            }
            i++
        }
    }

    /** One snapshot of the most recent output, e.g. 1024 samples from the Visualizer effect. */
    fun processWindow(mono: FloatArray, count: Int, rate: Int) {
        val size = Integer.highestOneBit(count).coerceAtMost(2048)
        configure(rate, size)
        lastInputNanos = clock()
        // Snapshots overlap or leave gaps, so each one is analysed on its own. The right channel is the
        // signal ~2.5 ms later: stereo scopes then draw open phase figures instead of a flat diagonal line.
        val lag = rate / 400
        for (i in count - size until count) {
            ringL[writePos] = mono[i]
            ringR[writePos] = mono[(i - lag).coerceAtLeast(0)]
            writePos = (writePos + 1) and RING_MASK
        }
        filled = max(filled, size)
        analyze()
    }

    /**
     * Copies the frame that was playing [delayNanos] ago into [out]. Returns false if nothing was
     * analysed yet. Frames older than [STALE_NANOS] fade out so visuals calm down when audio stops.
     */
    fun read(out: AudioFeatures, delayNanos: Long = 0L): Boolean {
        val now = clock()
        synchronized(lock) {
            if (publishCount == 0) return false
            val target = now - delayNanos
            var chosen = published[(publishPos - 1 + PUBLISHED) % PUBLISHED]
            for (k in 1..publishCount) {
                val f = published[(publishPos - k + PUBLISHED) % PUBLISHED]
                chosen = f
                if (f.timeNanos <= target) break
            }
            out.copyFrom(chosen)
        }
        val stale = now - delayNanos - out.timeNanos
        if (stale > STALE_NANOS) {
            out.fade(exp(-(stale - STALE_NANOS) / 4e8).toFloat())
            out.silent = true
        }
        return true
    }

    fun reset() {
        synchronized(lock) { publishCount = 0 }
        filled = 0
        sinceHop = 0
        onsetCount = 0
        refDb = -40f
        wavePeak = 0.05f
        classifier.reset()
    }

    private fun configure(rate: Int, size: Int) {
        if (rate == sampleRate && size == fftSize) return
        sampleRate = rate
        fftSize = size
        hop = size / 4
        fft = Fft(size)
        window = Fft.hann(size)
        windowGain = 2f / window.sum()
        re = FloatArray(size)
        im = FloatArray(size)
        mag = FloatArray(size / 2)
        logMag = FloatArray(size / 2)
        prevLogMag = FloatArray(size / 2)
        val binHz = rate.toFloat() / size
        val ratio = AudioFeatures.MAX_HZ / AudioFeatures.MIN_HZ
        for (j in 0 until AudioFeatures.BINS) {
            val f = AudioFeatures.MIN_HZ * ratio.pow(j / (AudioFeatures.BINS - 1f))
            val fNext = AudioFeatures.MIN_HZ * ratio.pow((j + 1) / (AudioFeatures.BINS - 1f))
            val pos = f / binHz
            val width = (fNext - f) / binHz
            binPos[j] = pos
            if (width <= 1f) {
                binLo[j] = -1 // interpolate
            } else {
                binLo[j] = floor(pos - width / 2).toInt().coerceIn(1, size / 2 - 1)
                binHi[j] = (pos + width / 2).roundToInt().coerceIn(binLo[j], size / 2 - 1)
            }
            // Pink-noise compensation: +3 dB per octave above 1 kHz, so highs don't vanish.
            binTilt[j] = (3f * log2(f / 1000f)).coerceIn(-6f, 12f)
        }
        fun bin(hz: Float) = (hz / binHz).roundToInt().coerceIn(1, size / 2)
        bassEnd = bin(150f)
        midEnd = bin(2000f)
        trebleEnd = bin(16000f)
        speechStart = bin(300f)
        speechEnd = bin(3400f)
        fluxEnd = bin(8000f)
        lowFluxEnd = bin(250f)
        chromaIndex = IntArray(size / 2) { k ->
            val hz = k * binHz
            if (hz < 80f || hz > 5000f) -1 else ((12 * log2(hz / 440f) + 69).roundToInt() % 12 + 12) % 12
        }
        filled = 0
        lastAnalysisNanos = 0L
    }

    private fun analyze() {
        val now = clock()
        val dt = if (lastAnalysisNanos == 0L) hop.toFloat() / sampleRate else ((now - lastAnalysisNanos) / 1e9f).coerceIn(0.001f, 0.2f)
        lastAnalysisNanos = now

        // Window the newest fftSize samples (older WAVE_SPAN samples are kept for the scope trigger).
        val start = (writePos - fftSize + RING) and RING_MASK
        var sumSq = 0f
        for (i in 0 until fftSize) {
            val idx = (start + i) and RING_MASK
            val m = (ringL[idx] + ringR[idx]) * 0.5f
            sumSq += m * m
            re[i] = m * window[i]
            im[i] = 0f
        }
        val rms = sqrt(sumSq / fftSize)
        fft.transform(re, im)

        var bassPow = 0f
        var midPow = 0f
        var treblePow = 0f
        var speechPow = 0f
        var totalPow = 1e-12f
        var lowFlux = 0f
        var fullFlux = 0f
        chroma.fill(0f)
        val half = fftSize / 2
        for (k in 1 until half) {
            val a = sqrt(re[k] * re[k] + im[k] * im[k]) * windowGain
            mag[k] = a
            val p = a * a
            when {
                k < bassEnd -> bassPow += p
                k < midEnd -> midPow += p
                k < trebleEnd -> treblePow += p
            }
            if (k in speechStart until speechEnd) speechPow += p
            if (k < fluxEnd) totalPow += p
            val lm = ln(1f + 1000f * a)
            logMag[k] = lm
            val d = lm - prevLogMag[k]
            if (d > 0f && k < fluxEnd) {
                fullFlux += d
                if (k < lowFluxEnd) lowFlux += d
            }
            val c = chromaIndex[k]
            if (c >= 0) chroma[c] += p
        }
        logMag.copyInto(prevLogMag)

        val silent = rms < SILENCE_RMS
        val f = work

        // Spectrum
        var frameMax = -120f
        for (j in 0 until AudioFeatures.BINS) {
            val a = if (binLo[j] < 0) {
                val pos = binPos[j].coerceIn(1f, half - 2f)
                val i0 = pos.toInt()
                val t = pos - i0
                mag[i0] * (1 - t) + mag[i0 + 1] * t
            } else {
                var m = 0f
                for (k in binLo[j]..binHi[j]) m = max(m, mag[k])
                m
            }
            val db = 20f * log10(a + 1e-9f) + binTilt[j]
            raw[j] = db
            frameMax = max(frameMax, db)
        }
        refDb = if (frameMax > refDb) frameMax else max(refDb - REF_RELEASE_DB_PER_S * dt, REF_FLOOR_DB)
        val floorDb = refDb - RANGE_DB
        val fastDecay = exp(-dt / 0.10f)
        val smoothUp = 1f - exp(-dt / 0.035f)
        val smoothDown = 1f - exp(-dt / 0.30f)
        for (j in 0 until AudioFeatures.BINS) {
            val v = if (silent) 0f else ((raw[j] - floorDb) / RANGE_DB).coerceIn(0f, 1f)
            f.spectrumFast[j] = max(v, f.spectrumFast[j] * fastDecay)
            val s = f.spectrum[j]
            f.spectrum[j] = s + (v - s) * (if (v > s) smoothUp else smoothDown)
        }

        // Bands (AGC per band, peak memory of a few seconds)
        val peakDecay = exp(-dt / 6f)
        val bassAmp = sqrt(bassPow)
        val midAmp = sqrt(midPow)
        val trebleAmp = sqrt(treblePow)
        bassPeak = max(max(bassAmp, bassPeak * peakDecay), FLOOR_AMP)
        midPeak = max(max(midAmp, midPeak * peakDecay), FLOOR_AMP)
        treblePeak = max(max(trebleAmp, treblePeak * peakDecay), FLOOR_AMP)
        levelPeak = max(max(rms, levelPeak * peakDecay), FLOOR_AMP)
        f.bass = follow(f.bass, (bassAmp / bassPeak).pow(0.8f), dt)
        f.mid = follow(f.mid, (midAmp / midPeak).pow(0.8f), dt)
        f.treble = follow(f.treble, (trebleAmp / treblePeak).pow(0.8f), dt)
        f.level = follow(f.level, (rms / levelPeak).pow(0.8f), dt)

        bassFast += (bassAmp - bassFast) * (1f - exp(-dt / (if (bassAmp > bassFast) 0.008f else 0.06f)))
        bassSlow += (bassAmp - bassSlow) * (1f - exp(-dt / 0.3f))
        f.bassHit = ((bassFast - bassSlow * 1.1f) / bassPeak * 3f).coerceIn(0f, 1f)

        longRms += (rms - longRms) * (1f - exp(-dt / 2.5f))
        longPeak = max(max(longRms, longPeak * exp(-dt / 45f)), FLOOR_AMP)
        f.energy = (longRms / longPeak).coerceIn(0f, 1f)

        // Onsets: low-frequency flux (kicks) weighted over full-band flux, adaptive threshold.
        lowFluxMean += (lowFlux - lowFluxMean) * (1f - exp(-dt / 2f))
        fullFluxMean += (fullFlux - fullFluxMean) * (1f - exp(-dt / 2f))
        val onset = if (silent) 0f else 0.65f * lowFlux / max(lowFluxMean, 1e-3f) + 0.35f * fullFlux / max(fullFluxMean, 1e-3f)
        detectBeat(onset, now)

        writeWaveform(dt)

        f.silent = silent
        f.timeNanos = now
        classifier.update(
            dt = dt,
            rmsDb = 20f * log10(rms + 1e-9f),
            onset = onset,
            bassRatio = bassPow / totalPow,
            speechAmp = sqrt(speechPow),
            chroma = chroma,
        )
        synchronized(lock) {
            published[publishPos].copyFrom(f)
            publishPos = (publishPos + 1) % PUBLISHED
            publishCount = min(publishCount + 1, PUBLISHED)
        }
    }

    private fun detectBeat(onset: Float, now: Long) {
        onsetHistory[onsetPos] = onset
        onsetPos = (onsetPos + 1) % ONSET_HISTORY
        onsetCount = min(onsetCount + 1, ONSET_HISTORY)
        var mean = 0f
        var sq = 0f
        for (i in 0 until onsetCount) {
            mean += onsetHistory[i]
            sq += onsetHistory[i] * onsetHistory[i]
        }
        mean /= onsetCount
        val std = sqrt(max(sq / onsetCount - mean * mean, 0f))
        val threshold = mean + 1.3f * std + 0.3f
        // Peak picking one hop late: the previous value must be a local maximum above threshold.
        val candidate = onsetPrev1
        val sinceBeat = now - work.lastBeatNanos
        if (onsetCount > 8 && candidate > threshold && candidate > onsetPrev2 && candidate >= onset && sinceBeat > MIN_BEAT_GAP_NANOS) {
            work.beatCount++
            work.lastBeatNanos = now
        }
        onsetPrev2 = onsetPrev1
        onsetPrev1 = onset
    }

    private fun writeWaveform(dt: Float) {
        val f = work
        val decim = if (sampleRate >= 32000 && filled >= fftSize + WAVE_SPAN) 2 else 1
        val span = AudioFeatures.WAVE * decim
        val search = min(AudioFeatures.WAVE, max(filled - span, 0))
        val base = (writePos - span - search + RING) and RING_MASK
        // Rising zero crossing of a lightly smoothed mono signal keeps periodic waves standing still.
        var trigger = search
        var prev = 0f
        for (i in 0 until search) {
            val idx = (base + i) and RING_MASK
            val m = (ringL[idx] + ringR[idx]) * 0.5f
            val s = prev * 0.6f + m * 0.4f
            if (i > 0 && prev < 0f && s >= 0f) {
                trigger = i
                break
            }
            prev = s
        }
        var peak = 0f
        val begin = (base + trigger) and RING_MASK
        for (i in 0 until AudioFeatures.WAVE) {
            var l = 0f
            var r = 0f
            for (d in 0 until decim) {
                val idx = (begin + i * decim + d) and RING_MASK
                l += ringL[idx]
                r += ringR[idx]
            }
            l /= decim
            r /= decim
            f.waveL[i] = l
            f.waveR[i] = r
            peak = max(peak, max(abs(l), abs(r)))
        }
        wavePeak = max(max(peak, wavePeak * exp(-dt / 1.5f)), 0.02f)
        val g = 0.95f / wavePeak
        for (i in 0 until AudioFeatures.WAVE) {
            f.waveL[i] = (f.waveL[i] * g).coerceIn(-1f, 1f)
            f.waveR[i] = (f.waveR[i] * g).coerceIn(-1f, 1f)
        }
    }

    private fun follow(current: Float, target: Float, dt: Float): Float {
        val tau = if (target > current) 0.015f else 0.18f
        return current + (target.coerceIn(0f, 1.2f) - current) * (1f - exp(-dt / tau))
    }

    private companion object {
        const val RING = 8192
        const val RING_MASK = RING - 1
        const val WAVE_SPAN = AudioFeatures.WAVE * 3
        const val PUBLISHED = 64
        const val ONSET_HISTORY = 80 // ~0.85 s at 94 hops/s
        const val SILENCE_RMS = 5.6e-4f // -65 dBFS
        const val FLOOR_AMP = 1e-3f
        const val RANGE_DB = 50f
        const val REF_RELEASE_DB_PER_S = 3f
        const val REF_FLOOR_DB = -65f
        const val STALE_NANOS = 300_000_000L
        const val MIN_BEAT_GAP_NANOS = 280_000_000L
    }
}
