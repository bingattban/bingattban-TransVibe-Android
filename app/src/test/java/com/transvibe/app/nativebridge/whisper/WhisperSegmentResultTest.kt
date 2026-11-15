package com.transvibe.app.nativebridge.whisper

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class WhisperSegmentResultTest {
    @Test fun `structured bridge mapping preserves unicode and real metrics`() {
        val texts = listOf(
            "مرحبا بالعالم",
            "Hello world",
            "مرحبا Hello",
            "🎬🔥",
            "𐍈 𝄞 𠀋",
            "العربية English हिन्दी 中文 🎧"
        )
        texts.forEach { text ->
            val native = WhisperSegmentResult(
                startMs = 120L,
                endMs = 950L,
                text = text,
                confidence = 0.8125f,
                avgLogProb = null,
                noSpeechProbability = 0.125f,
                tokenCount = 7
            )
            val mapped = WhisperNative.toRecognitionSegments(arrayOf(native)).single()
            assertEquals(text, mapped.segment.originalText)
            assertEquals(120L, mapped.segment.startMs)
            assertEquals(950L, mapped.segment.endMs)
            assertEquals(0.8125f, mapped.averageTokenProbability)
            assertNull(mapped.avgLogProb)
            assertEquals(0.125f, mapped.noSpeechProbability)
            assertEquals(7, mapped.tokenCount)
        }
    }
}
