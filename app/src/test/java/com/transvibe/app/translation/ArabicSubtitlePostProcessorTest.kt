package com.transvibe.app.translation

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ArabicSubtitlePostProcessorTest {
    @Test
    fun normalizesArabicPunctuationWithoutChangingProtectedLiterals() {
        val input = "مرحبا , كيف حالك ? افتح https://example.com/a,b?id=12 واستخدم API-2"
        val output = ArabicSubtitlePostProcessor.process(input)
        assertTrue(output.contains("مرحبا، كيف حالك؟"))
        assertTrue(output.contains("https://example.com/a,b?id=12"))
        assertTrue(output.contains("API-2"))
    }

    @Test
    fun trimsOnlyRunawayWordRepetition() {
        assertEquals("نعم نعم", ArabicSubtitlePostProcessor.process("نعم نعم نعم نعم"))
        assertEquals("لا لا", ArabicSubtitlePostProcessor.process("لا لا"))
    }
}
