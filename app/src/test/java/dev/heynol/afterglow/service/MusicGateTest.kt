package dev.heynol.afterglow.service

import dev.heynol.afterglow.audio.FakeClock
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MusicGateTest {
    private val clock = FakeClock()
    private val gate = MusicGate(clock)

    private fun run(seconds: Float, playing: Boolean, score: Float?, prior: Float = 0f) {
        repeat((seconds * 4).toInt()) {
            gate.update(playing, score, prior)
            clock.nanos += 250_000_000L
        }
    }

    @Test
    fun needsSustainedConfidence() {
        run(2f, playing = true, score = 0.9f)
        assertFalse(gate.detected)
        run(1f, playing = true, score = 0.9f)
        assertTrue(gate.detected)
    }

    @Test
    fun survivesShortQuietPassages() {
        run(4f, playing = true, score = 0.9f)
        run(8f, playing = true, score = 0.2f)
        assertTrue(gate.detected)
        run(8f, playing = true, score = 0.2f)
        assertFalse(gate.detected)
    }

    @Test
    fun pauseEndsAfterTimeout() {
        run(4f, playing = true, score = 0.9f)
        run(9f, playing = false, score = 0.9f)
        assertTrue(gate.detected)
        run(2f, playing = false, score = 0.9f)
        assertFalse(gate.detected)
    }

    @Test
    fun anyPlaybackMode() {
        gate.mode = MusicGate.Mode.ANY_PLAYBACK
        run(2f, playing = true, score = 0.1f, prior = -1f)
        assertTrue(gate.detected)
    }

    @Test
    fun trackChangesFromMetadataAndGaps() {
        val detector = TrackChangeDetector(clock)
        assertFalse(detector.onMetadata("Song A", "X"))
        assertFalse(detector.onMetadata("Song A", "X"))
        assertTrue(detector.onMetadata("Song B", "X"))

        val audioOnly = TrackChangeDetector(clock)
        clock.nanos += 60_000_000_000L
        var changes = 0
        fun step(silent: Boolean, seconds: Float) = repeat((seconds * 10).toInt()) {
            if (audioOnly.onAudio(silent, hasMetadata = false)) changes++
            clock.nanos += 100_000_000L
        }
        step(false, 30f)
        step(true, 0.5f) // too short to be a gap
        step(false, 60f)
        step(true, 1.5f)
        step(false, 3f)
        assertTrue("changes=$changes", changes == 1)
    }
}
