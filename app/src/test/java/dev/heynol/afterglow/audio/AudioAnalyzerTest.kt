package dev.heynol.afterglow.audio

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.ln

class AudioAnalyzerTest {

    private val clock = FakeClock()
    private val analyzer = AudioAnalyzer(clock)
    private val out = AudioFeatures()

    private fun binFor(hz: Float): Int {
        val ratio = AudioFeatures.MAX_HZ / AudioFeatures.MIN_HZ
        return (ln(hz / AudioFeatures.MIN_HZ) / ln(ratio) * (AudioFeatures.BINS - 1)).toInt()
    }

    @Test
    fun sineShowsUpAtItsLogBin() {
        Signals.feed(analyzer, clock, Signals.sine(1f, 1000f))
        assertTrue(analyzer.read(out))
        val peak = out.spectrum.indices.maxBy { out.spectrum[it] }
        assertEquals(binFor(1000f).toFloat(), peak.toFloat(), 6f)
        assertFalse(out.silent)
        // Trigger-aligned scope: starts near a rising zero crossing
        assertTrue(out.waveL[0] in -0.25f..0.25f && out.waveL[3] > out.waveL[0])
    }

    @Test
    fun beatsFollowTheKick() {
        val beats = mutableListOf<Long>()
        var last = 0
        Signals.feed(analyzer, clock, Signals.music(10f, bpm = 120f)) {
            analyzer.read(out)
            if (out.beatCount != last) {
                last = out.beatCount
                beats += out.lastBeatNanos
            }
        }
        // 20 kicks in 10 s; allow warm-up misses and the odd extra onset.
        assertTrue("beats=${beats.size}", beats.size in 15..24)
        val gaps = beats.zipWithNext { a, b -> (b - a) / 1e6 }.sorted()
        assertEquals(500.0, gaps[gaps.size / 2], 40.0)
        assertTrue(out.bass > 0.2f && out.energy > 0.3f)
    }

    @Test
    fun silenceFadesOut() {
        Signals.feed(analyzer, clock, Signals.music(2f))
        Signals.feed(analyzer, clock, FloatArray(48000 * 2))
        analyzer.read(out)
        assertTrue(out.silent)
        assertTrue(out.spectrum.max() < 0.05f)
    }

    @Test
    fun staleDataFades() {
        Signals.feed(analyzer, clock, Signals.music(2f))
        clock.nanos += 2_000_000_000L
        analyzer.read(out)
        assertTrue(out.silent)
        assertTrue(out.level < 0.05f)
    }

    @Test
    fun delayedReadReturnsOlderFrame() {
        Signals.feed(analyzer, clock, Signals.sine(1f, 200f))
        Signals.feed(analyzer, clock, Signals.sine(0.3f, 4000f))
        analyzer.read(out, delayNanos = 0)
        val now = out.spectrumFast.indices.maxBy { out.spectrumFast[it] }
        analyzer.read(out, delayNanos = 420_000_000L) // before the 4 kHz tone started
        val before = out.spectrumFast.indices.maxBy { out.spectrumFast[it] }
        assertTrue(now > before)
    }

    @Test
    fun visualizerWindowsAreAnalysed() {
        val sine = Signals.sine(0.1f, 440f)
        val mono = FloatArray(1024) { sine[it * 2] }
        repeat(30) {
            analyzer.processWindow(mono, 1024, 48000)
            clock.nanos += 16_000_000L
        }
        analyzer.read(out)
        val peak = out.spectrum.indices.maxBy { out.spectrum[it] }
        assertEquals(binFor(440f).toFloat(), peak.toFloat(), 12f)
    }
}
