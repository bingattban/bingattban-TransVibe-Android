package com.transvibe.app.media.decoder

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.sin

class StreamingSincResamplerTest {
    @Test fun chunkedMatchesSinglePassAtCommonInputRates() {
        for (rate in listOf(8000, 22050, 44100, 48000)) {
            val source = FloatArray(rate / 2) { i ->
                (0.30 * sin(2 * PI * 440.0 * i / rate) + 0.17 * sin(2 * PI * 1750.0 * i / rate)).toFloat()
            }
            val whole = StreamingSincResampler(16000).let { resampler ->
                resampler.process(source, rate) + resampler.finish()
            }
            val chunked = StreamingSincResampler(16000).let { resampler ->
                val parts = ArrayList<FloatArray>()
                var offset = 0
                while (offset < source.size) {
                    val end = (offset + 991).coerceAtMost(source.size)
                    parts.add(resampler.process(source.copyOfRange(offset, end), rate))
                    offset = end
                }
                parts.add(resampler.finish())
                parts.fold(FloatArray(0)) { acc, part -> acc + part }
            }
            assertEquals("rate=$rate", whole.size, chunked.size)
            for (i in whole.indices) {
                assertTrue("rate=$rate i=$i", abs(whole[i] - chunked[i]) < 0.0001f)
            }
            assertTrue("unexpected resample length at $rate", abs(whole.size - 8000) <= 5)
        }
    }

    @Test fun identityRatePassesThroughWithoutModification() {
        val samples = floatArrayOf(-0.5f, 0.0f, 0.35f, 0.95f)
        val resampler = StreamingSincResampler(16000)
        assertTrue(samples === resampler.process(samples, 16000))
        assertEquals(0, resampler.finish().size)
    }
}
