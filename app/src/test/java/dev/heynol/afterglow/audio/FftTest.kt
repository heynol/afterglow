package dev.heynol.afterglow.audio

import org.junit.Assert.assertEquals
import org.junit.Test
import kotlin.math.PI
import kotlin.math.sin
import kotlin.math.sqrt

class FftTest {
    @Test
    fun sinePeaksAtItsBin() {
        val n = 1024
        val fft = Fft(n)
        val re = FloatArray(n) { sin(2 * PI * 37 * it / n).toFloat() }
        val im = FloatArray(n)
        fft.transform(re, im)
        val mags = (0 until n / 2).map { sqrt(re[it] * re[it] + im[it] * im[it]) }
        assertEquals(37, mags.indices.maxBy { mags[it] })
        assertEquals(n / 2f, mags[37], 0.01f * n)
    }
}
