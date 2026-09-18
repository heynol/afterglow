package dev.heynol.afterglow.audio

/**
 * One analysis frame in the form the shaders consume it (see assets/shaders/common.glsl).
 * Values are AGC-normalised to roughly 0..1 so visuals look alike at any playback volume.
 */
class AudioFeatures {
    /** Log-frequency spectrum from [MIN_HZ] to [MAX_HZ], smoothed for calm motion. */
    val spectrum = FloatArray(BINS)

    /** Same bins with instant attack and a short release, for peaks and history. */
    val spectrumFast = FloatArray(BINS)

    /** Trigger-aligned waveform, -1..1. */
    val waveL = FloatArray(WAVE)
    val waveR = FloatArray(WAVE)

    var bass = 0f
    var mid = 0f
    var treble = 0f
    var level = 0f

    /** Bass transient: kicks and drops. */
    var bassHit = 0f

    /** Slow song intensity: calm breakdowns are low, drops are high. */
    var energy = 0f

    var beatCount = 0
    var lastBeatNanos = 0L

    /** Capture time of the audio this frame describes. */
    var timeNanos = 0L

    var silent = true

    fun copyFrom(other: AudioFeatures) {
        other.spectrum.copyInto(spectrum)
        other.spectrumFast.copyInto(spectrumFast)
        other.waveL.copyInto(waveL)
        other.waveR.copyInto(waveR)
        bass = other.bass
        mid = other.mid
        treble = other.treble
        level = other.level
        bassHit = other.bassHit
        energy = other.energy
        beatCount = other.beatCount
        lastBeatNanos = other.lastBeatNanos
        timeNanos = other.timeNanos
        silent = other.silent
    }

    /** Fades everything towards silence, used when the source stalls (paused playback, lost capture). */
    fun fade(factor: Float) {
        for (i in 0 until BINS) {
            spectrum[i] *= factor
            spectrumFast[i] *= factor
        }
        for (i in 0 until WAVE) {
            waveL[i] *= factor
            waveR[i] *= factor
        }
        bass *= factor
        mid *= factor
        treble *= factor
        level *= factor
        bassHit *= factor
        energy *= factor
    }

    companion object {
        const val BINS = 512
        const val WAVE = 512
        const val MIN_HZ = 30f
        const val MAX_HZ = 16000f
    }
}
