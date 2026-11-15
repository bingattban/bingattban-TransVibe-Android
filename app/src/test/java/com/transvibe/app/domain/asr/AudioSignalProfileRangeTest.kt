package com.transvibe.app.domain.asr

import org.junit.Test
import org.junit.Assert.assertEquals

class AudioSignalProfileRangeTest {
    @Test
    fun rangeProfileMatchesMaterializedSlice() {
        val source = FloatArray(4096) { index -> ((index % 97) - 48) / 64f }
        val start = 321
        val end = 3021
        val expected = AudioSignalProfile.from(source.copyOfRange(start, end), 16_000, 0.63f)
        val actual = AudioSignalProfile.fromRange(source, start, end, 16_000, 0.63f)
        assertEquals(expected, actual)
    }
}
