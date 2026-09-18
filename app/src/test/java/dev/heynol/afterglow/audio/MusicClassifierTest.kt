package dev.heynol.afterglow.audio

import org.junit.Assert.assertTrue
import org.junit.Test

class MusicClassifierTest {

    private fun score(pcm: FloatArray): Pair<Float, FloatArray> {
        val clock = FakeClock()
        val analyzer = AudioAnalyzer(clock)
        Signals.feed(analyzer, clock, pcm)
        return analyzer.classifier.score to analyzer.classifier.details
    }

    @Test
    fun musicScoresHigh() {
        for (bpm in listOf(90f, 124f, 170f)) {
            val (s, d) = score(Signals.music(9f, bpm))
            println("music bpm=$bpm score=$s details=${d.toList()}")
            assertTrue("bpm=$bpm score=$s details=${d.toList()}", s > 0.62f)
        }
    }

    @Test
    fun songsWithVocalsStillCountAsMusic() {
        val music = Signals.music(9f, 100f)
        val vocals = Signals.speech(9f, 5)
        val mix = FloatArray(music.size) { music[it] * 0.8f + vocals[it] * 0.9f }
        val (s, d) = score(mix)
        println("vocal mix score=$s details=${d.toList()}")
        assertTrue("score=$s details=${d.toList()}", s > 0.6f)
    }

    @Test
    fun speechScoresLow() {
        for (seed in 1L..3L) {
            val (s, d) = score(Signals.speech(9f, seed))
            println("speech seed=$seed score=$s details=${d.toList()}")
            assertTrue("seed=$seed score=$s details=${d.toList()}", s < 0.45f)
        }
    }
}
