package com.transvibe.app.translation

import com.transvibe.app.domain.model.DownloadPolicy
import com.transvibe.app.domain.model.LanguageCode
import com.transvibe.app.domain.model.SubtitleSegment
import com.transvibe.app.domain.repository.PersistedSubtitleSegment
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ContextualSubtitleTranslatorTest {
    @Test
    fun groupsShortSegmentsBeforeTranslationAndPreservesTimeline() = runBlocking {
        val engine = FakeEngine("هذا اختبار للسياق. وهذه جملة ثانية مفيدة.")
        val translator = ContextualSubtitleTranslator(engine)
        val a = persisted(0, 0, 2_000, "This is a test")
        val b = persisted(1, 2_050, 4_000, "of context. This is another useful sentence.")

        assertTrue(translator.accept(a).isEmpty())
        val result = translator.accept(b)

        assertEquals(1, engine.calls.size)
        assertEquals("This is a test of context. This is another useful sentence.", engine.calls.single())
        assertEquals(2, result.size)
        assertEquals(a.segment.startMs, result[0].segment.startMs)
        assertEquals(a.segment.endMs, result[0].segment.endMs)
        assertEquals(b.segment.startMs, result[1].segment.startMs)
        assertEquals(b.segment.endMs, result[1].segment.endMs)
        assertTrue(result.all { !it.segment.translatedText.isNullOrBlank() })
    }

    @Test
    fun hardPauseClosesPreviousContextWindow() = runBlocking {
        val engine = FakeEngine("ترجمة")
        val translator = ContextualSubtitleTranslator(engine)
        val a = persisted(0, 0, 1_000, "Short fragment")
        val b = persisted(1, 3_000, 4_000, "Next fragment")

        assertTrue(translator.accept(a).isEmpty())
        val emitted = translator.accept(b)

        assertEquals(1, engine.calls.size)
        assertEquals(1, emitted.size)
        assertEquals(0, emitted.single().segmentIndex)
    }

    @Test
    fun ordinaryEnglishWordsAreNotReplacedByTranslationPlaceholders() = runBlocking {
        val engine = FakeEngine("ترجمة سليمة")
        val translator = ContextualSubtitleTranslator(engine)
        val item = persisted(0, 0, 1_500, "Bookmark manager and cloud security")
        translator.accept(item)
        translator.flush()
        assertEquals("Bookmark manager and cloud security", engine.calls.single())
    }

    @Test
    fun protectedLiteralsRestoreEvenWhenTranslatorLowercasesPlaceholder() = runBlocking {
        val engine = object : TranslationEngine {
            override val sourceLanguage = LanguageCode.ENGLISH
            override val targetLanguage = LanguageCode.of("ar")
            override suspend fun isReady() = true
            override suspend fun prepare(policy: DownloadPolicy) = Unit
            override suspend fun translate(text: String) = text.lowercase()
            override fun close() = Unit
        }
        val translator = ContextualSubtitleTranslator(engine)
        val item = persisted(0, 0, 1_500, "Open https://start.me at 10:30")
        translator.accept(item)
        val result = translator.flush().single().segment.translatedText.orEmpty()
        assertTrue(result.contains("https://start.me"))
        assertTrue(result.contains("10:30"))
        assertTrue(!result.contains("tv0", ignoreCase = true))
    }

    private fun persisted(index: Int, start: Long, end: Long, english: String) = PersistedSubtitleSegment(
        index,
        SubtitleSegment(index.toLong(), start, end, english, english, null)
    )

    private class FakeEngine(private val result: String) : TranslationEngine {
        override val sourceLanguage = LanguageCode.ENGLISH
        override val targetLanguage = LanguageCode.of("ar")
        val calls = mutableListOf<String>()
        override suspend fun isReady() = true
        override suspend fun prepare(policy: DownloadPolicy) = Unit
        override suspend fun translate(text: String): String {
            calls += text
            return result
        }
        override fun close() = Unit
    }
}
