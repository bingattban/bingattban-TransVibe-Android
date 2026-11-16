package com.transvibe.app.translation

import com.transvibe.app.domain.model.DownloadPolicy
import com.transvibe.app.domain.model.LanguageCode
import com.transvibe.app.domain.model.SubtitleSegment
import com.transvibe.app.domain.repository.PersistedSubtitleSegment
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ContextualSubtitleBatcherTest {
    private class FakeEngine : TranslationEngine {
        override val sourceLanguage = LanguageCode.ENGLISH
        override val targetLanguage = LanguageCode.of("ar")
        val translatedInputs = mutableListOf<String>()
        override suspend fun isReady() = true
        override suspend fun prepare(policy: DownloadPolicy) = Unit
        override suspend fun translate(text: String): String {
            translatedInputs += text
            return "كلمات مترجمة ومتتابعة وصحيحة لاختبار ترتيب المقاطع"
        }
        override fun close() = Unit
    }

    private fun segment(index: Int, text: String, translated: String? = null) =
        PersistedSubtitleSegment(
            index,
            SubtitleSegment(index.toLong(), index * 1_000L, (index + 1) * 1_000L,
                text, text, translated)
        )

    @Test fun translationRetainsContextAcrossReadBatchesAndFlushesOnce() = runBlocking {
        val engine = FakeEngine()
        val batcher = ContextualSubtitleBatcher(ContextualSubtitleTranslator(engine))
        val first = batcher.translateBatch(listOf(segment(0, "Hello")))
        assertTrue(first.isEmpty())
        assertTrue(engine.translatedInputs.isEmpty())
        val second = batcher.translateBatch(listOf(segment(1, "there"), segment(2, "friend")))
        val remaining = batcher.flush()
        val result = first + second + remaining
        assertEquals(listOf(0, 1, 2), result.map { it.segmentIndex })
        assertEquals(1, engine.translatedInputs.size)
        assertEquals("Hello there friend", engine.translatedInputs.single())
        assertTrue(result.all { !it.segment.translatedText.isNullOrBlank() })
    }

    @Test fun previousTranslatedCueIsNeverRewrittenAndBreaksWindow() = runBlocking {
        val engine = FakeEngine()
        val batcher = ContextualSubtitleBatcher(ContextualSubtitleTranslator(engine))
        val result = batcher.translateBatch(listOf(
            segment(0, "first"), segment(1, "already", "ترجمة سابقة"), segment(2, "last")
        )) + batcher.flush()
        assertEquals(listOf(0, 1, 2), result.map { it.segmentIndex })
        assertEquals("ترجمة سابقة", result[1].segment.translatedText)
        assertEquals(listOf("first", "last"), engine.translatedInputs)
    }

    @Test fun cancellationStopsAccumulatingBeforeNextSegment() = runBlocking {
        val engine = FakeEngine()
        val batcher = ContextualSubtitleBatcher(ContextualSubtitleTranslator(engine))
        var checks = 0
        val failed = runCatching {
            batcher.translateBatch(listOf(segment(0, "first"), segment(1, "second"))) {
                if (++checks == 2) throw kotlinx.coroutines.CancellationException("stop")
            }
        }.exceptionOrNull()
        assertTrue(failed is kotlinx.coroutines.CancellationException)
        assertEquals(2, checks)
        assertFalse(engine.translatedInputs.isNotEmpty())
    }
}
