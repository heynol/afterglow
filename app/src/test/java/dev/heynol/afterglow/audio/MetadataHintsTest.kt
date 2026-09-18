package dev.heynol.afterglow.audio

import org.junit.Assert.assertTrue
import org.junit.Test

class MetadataHintsTest {
    private fun np(title: String, artist: String? = null, album: String? = null, duration: Long? = null, pkg: String = "com.google.android.youtube.tv") =
        NowPlaying(pkg, title, artist, album, duration, playing = true)

    @Test
    fun musicVideosArePositive() {
        assertTrue(MetadataHints.prior(np("Daft Punk - Get Lucky (Official Audio) ft. Pharrell Williams", "Daft Punk")) > 0.5f)
        assertTrue(MetadataHints.prior(np("Get Lucky", "Daft Punk - Topic")) > 0.5f)
        assertTrue(MetadataHints.prior(np("Anything", pkg = "com.google.android.apps.youtube.music")) == 1f)
    }

    @Test
    fun talksAreNegative() {
        assertTrue(MetadataHints.prior(np("The Tech Podcast Episode 42: AI news", "Some Channel", duration = 3_600_000)) < -0.3f)
        assertTrue(MetadataHints.prior(np("iPhone 17 Review", "MKBHD")) < 0f)
    }

    @Test
    fun combineRespectsBothSignals() {
        assertTrue(MetadataHints.combine(0.8f, 0f) > 0.6f)
        assertTrue(MetadataHints.combine(0.3f, 0f) < 0.4f)
        assertTrue(MetadataHints.combine(0.45f, 0.8f) > 0.6f)
        assertTrue(MetadataHints.combine(null, 1f) > 0.85f)
        assertTrue(MetadataHints.combine(0.9f, -0.7f) < MetadataHints.combine(0.9f, 0f))
    }
}
