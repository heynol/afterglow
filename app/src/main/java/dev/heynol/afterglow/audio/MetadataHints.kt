package dev.heynol.afterglow.audio

import kotlin.math.exp
import kotlin.math.ln

/** What the media session says is playing. All fields optional; YouTube fills title and artist (channel). */
data class NowPlaying(
    val packageName: String,
    val title: String?,
    val artist: String?,
    val album: String?,
    val durationMs: Long?,
    val playing: Boolean,
)

/**
 * Prior belief from media metadata that the current item is music, in -1 (clearly not) .. +1
 * (clearly music). Combined with the audio classifier in [combine].
 */
object MetadataHints {

    private val MUSIC_APPS = setOf(
        "com.google.android.apps.youtube.music",
        "com.google.android.youtube.tvmusic",
        "com.spotify.tv.android",
        "com.spotify.music",
        "com.amazon.music.tv",
        "com.aspiro.tidal",
        "deezer.android.tv",
        "com.apple.android.music",
        "com.plexapp.plexamp",
    )

    private val MUSIC_WORDS = Regex(
        """official\s+(music\s+)?(video|audio|visualizer|lyric)|music\s+video|lyrics?\b|\bfeat\.?\s|\bft\.\s|\bremix\b|\bacoustic\b|""" +
            """\blive\s+(at|from|session)\b|\bdj\s+set\b|\bmix\b|\bplaylist\b|\balbum\b|\bep\b|\bsingle\b|\bcover\b|\bkaraoke\b|""" +
            """\binstrumental\b|\blofi\b|\blo-fi\b|\bbeats?\s+to\b|\bsoundtrack\b|\bost\b|\bconcert\b|\bperformance\b|\bofficial\b""",
        RegexOption.IGNORE_CASE,
    )

    private val NON_MUSIC_WORDS = Regex(
        """\bpodcast\b|\bepisode\b|\bep\.\s*\d|\binterview\b|\breaction\b|\breview\b|\btutorial\b|\bhow\s+to\b|\bnews\b|""" +
            """\bgameplay\b|\blet'?s\s+play\b|\btrailer\b|\bhighlights\b|\bvlog\b|\bdocumentary\b|\bexplained\b|\blecture\b|\bunboxing\b|""" +
            """\bfolge\b|\bnachrichten\b|\bdoku\b|\berklärt\b""",
        RegexOption.IGNORE_CASE,
    )

    fun prior(np: NowPlaying?): Float {
        if (np == null) return 0f
        if (np.packageName in MUSIC_APPS) return 1f
        var p = 0f
        val title = np.title.orEmpty()
        val artist = np.artist.orEmpty()
        if (!np.album.isNullOrBlank()) p += 0.5f
        if (artist.endsWith(" - Topic") || artist.contains("VEVO", ignoreCase = true)) p += 0.8f
        if (MUSIC_WORDS.containsMatchIn(title)) p += 0.5f
        // "Artist - Song" is the most common music video title shape.
        if (Regex("""^[^-–|]{2,40}\s[-–]\s[^-–|]{2,80}$""").matches(title.substringBefore('(').trim())) p += 0.25f
        if (NON_MUSIC_WORDS.containsMatchIn(title)) p -= 0.7f
        np.durationMs?.let { d ->
            if (d in 90_000L..480_000L) p += 0.15f
            if (d > 40 * 60_000L && !MUSIC_WORDS.containsMatchIn(title)) p -= 0.3f
        }
        return p.coerceIn(-1f, 1f)
    }

    /** Probability of music from the audio score (0..1, null if no audio) and the metadata prior. */
    fun combine(audioScore: Float?, prior: Float): Float {
        val logit = if (audioScore == null) 0f else {
            val s = audioScore.coerceIn(0.02f, 0.98f)
            // Centre the classifier on its decision threshold.
            ln(s / (1 - s)) - ln(AUDIO_THRESHOLD / (1 - AUDIO_THRESHOLD))
        }
        return (1.0 / (1.0 + exp(-(logit + prior * 2.2f)))).toFloat()
    }

    const val AUDIO_THRESHOLD = 0.55f
}
