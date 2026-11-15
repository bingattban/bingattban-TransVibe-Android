package com.transvibe.app.translation

import com.transvibe.app.domain.repository.PersistedSubtitleSegment
import kotlin.math.max
import kotlin.math.roundToInt

/**
 * Stateful bounded translation window used for Arabic subtitle translation.
 *
 * It groups nearby ASR segments into short sentence-aware blocks, translates the block once,
 * then redistributes the Arabic result back to the original timing units. The original timeline
 * and segment indexes are never changed.
 */
class ContextualSubtitleTranslator(
    private val engine: TranslationEngine,
    private val arabicPostProcessor: (String) -> String = ArabicSubtitlePostProcessor::process,
    private val config: Config = Config()
) {
    data class Config(
        val minPreferredChars: Int = 40,
        val maxChars: Int = 160,
        val maxDurationMs: Long = 15_000L,
        val hardPauseMs: Long = 1_000L,
        val maxSentences: Int = 3,
        val maxSegments: Int = 8
    )

    private val pending = ArrayList<PersistedSubtitleSegment>(config.maxSegments)

    suspend fun accept(item: PersistedSubtitleSegment): List<PersistedSubtitleSegment> {
        val ready = ArrayList<PersistedSubtitleSegment>()
        if (pending.isNotEmpty() && shouldBreakBefore(item)) ready += flushInternal()
        pending += item
        if (shouldCloseAfterCurrent()) ready += flushInternal()
        return ready
    }

    suspend fun flush(): List<PersistedSubtitleSegment> = flushInternal()

    private fun shouldBreakBefore(next: PersistedSubtitleSegment): Boolean {
        val first = pending.first().segment
        val last = pending.last().segment
        val nextSegment = next.segment
        val nextText = sourceText(next)
        val pendingChars = pending.sumOf { sourceText(it).length } + (pending.size - 1).coerceAtLeast(0)
        val gap = nextSegment.startMs - last.endMs
        val projectedDuration = nextSegment.endMs - first.startMs
        return gap > config.hardPauseMs ||
            projectedDuration > config.maxDurationMs ||
            pendingChars + 1 + nextText.length > config.maxChars ||
            pending.size >= config.maxSegments
    }

    private fun shouldCloseAfterCurrent(): Boolean {
        if (pending.isEmpty()) return false
        val first = pending.first().segment
        val last = pending.last().segment
        val text = pending.joinToString(" ") { sourceText(it) }.trim()
        if (text.isBlank()) return true
        val duration = last.endMs - first.startMs
        val sentenceCount = sentenceEndRegex.findAll(text).count()
        return text.length >= config.maxChars ||
            duration >= config.maxDurationMs ||
            pending.size >= config.maxSegments ||
            sentenceCount >= config.maxSentences ||
            (sentenceCount >= 1 && text.length >= config.minPreferredChars)
    }

    private suspend fun flushInternal(): List<PersistedSubtitleSegment> {
        if (pending.isEmpty()) return emptyList()
        val block = pending.toList()
        pending.clear()

        val sourceParts = block.map { sourceText(it).trim() }
        val nonBlank = sourceParts.filter(String::isNotBlank)
        if (nonBlank.isEmpty()) return block

        val protected = ProtectedLiteralCodec.protect(nonBlank.joinToString(" "))
        val translatedRaw = engine.translate(protected.text).trim()
        val cleaned = protected.restore(arabicPostProcessor(translatedRaw))
        if (cleaned.isBlank()) return block

        val redistributed = redistribute(cleaned, block, sourceParts)
        return block.mapIndexed { index, persisted ->
            persisted.copy(segment = persisted.segment.copy(translatedText = redistributed[index]))
        }
    }

    private fun redistribute(
        translated: String,
        block: List<PersistedSubtitleSegment>,
        sourceParts: List<String>
    ): List<String> {
        if (block.size == 1) return listOf(translated)

        val sentencePieces = translated
            .split(sentenceSplitRegex)
            .map(String::trim)
            .filter(String::isNotBlank)
        if (sentencePieces.size == block.size) {
            return sentencePieces.map(arabicPostProcessor)
        }

        val words = translated.replace('\n', ' ')
            .split(Regex("\\s+"))
            .filter(String::isNotBlank)
        if (words.size < block.size) {
            return List(block.size) { index -> words.getOrNull(index)?.let(arabicPostProcessor).orEmpty() }
        }

        val weights = block.mapIndexed { index, item ->
            val charWeight = sourceParts[index].length.coerceAtLeast(1).toDouble()
            val durationWeight = (item.segment.endMs - item.segment.startMs).coerceAtLeast(1L).toDouble() / 80.0
            max(charWeight, durationWeight)
        }
        val totalWeight = weights.sum().coerceAtLeast(1.0)
        val result = ArrayList<String>(block.size)
        var cursor = 0
        var cumulativeWeight = 0.0
        for (index in block.indices) {
            cumulativeWeight += weights[index]
            val targetEnd = if (index == block.lastIndex) words.size else {
                (words.size * cumulativeWeight / totalWeight).roundToInt()
                    .coerceIn(cursor + 1, words.size - (block.size - index - 1))
            }
            val part = words.subList(cursor, targetEnd).joinToString(" ").trim()
            result += arabicPostProcessor(part)
            cursor = targetEnd
        }
        return result
    }

    private fun sourceText(item: PersistedSubtitleSegment): String =
        (item.segment.englishText ?: item.segment.originalText).trim()

    private data class ProtectedText(
        val text: String,
        val literals: List<String>
    ) {
        fun restore(value: String): String {
            var out = value
            literals.forEachIndexed { index, literal ->
                val id = Regex("(?i)(?:⟦\\s*TV$index\\s*⟧|\\[\\s*TV$index\\s*]|\\bTV$index\\b)")
                out = id.replace(out, Regex.escapeReplacement(literal))
            }
            return out
        }
    }

    private object ProtectedLiteralCodec {
        private val literalRegex = Regex(
            "(?i)(https?://\\S+|www\\.\\S+|[A-Z0-9._%+-]+@[A-Z0-9.-]+\\.[A-Z]{2,}|(?<![\\p{L}\\p{N}_])(?:(?-i:[A-Z]{2,}[A-Z0-9_-]*)|[A-Za-z0-9]+(?:[_-][A-Za-z0-9]+)+|\\d+(?:[.,:/-]\\d+)*)(?![\\p{L}\\p{N}_]))"
        )

        fun protect(input: String): ProtectedText {
            val literals = ArrayList<String>()
            val replaced = literalRegex.replace(input) { match ->
                val index = literals.size
                literals += match.value
                "⟦TV$index⟧"
            }
            return ProtectedText(replaced, literals)
        }
    }

    companion object {
        private val sentenceEndRegex = Regex("[.!?؟…]+(?:[\\\"'»”)]*)?(?=\\s|$)")
        private val sentenceSplitRegex = Regex("(?<=[.!?؟…])\\s+")
    }
}
