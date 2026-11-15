package com.transvibe.app.domain.asr

import com.transvibe.app.domain.model.SubtitleSegment
import kotlin.math.max

/** Converts Moonshine's utterance-level text into subtitle-sized timed segments. */
object MoonshineSubtitleSegmenter {
    private const val MAX_CHARS = 72

    fun segment(text: String, startMs: Long, endMs: Long, targetIsEnglish: Boolean): List<SubtitleSegment> {
        val normalized = text.trim().replace(Regex("\\s+"), " ")
        if (normalized.isBlank()) return emptyList()
        val pieces = splitForSubtitles(normalized)
        if (pieces.isEmpty()) return emptyList()
        val duration = max(1L, endMs - startMs)
        val weights = pieces.map { max(1, it.length) }
        val totalWeight = weights.sum().coerceAtLeast(1)
        var cursor = startMs
        var consumedWeight = 0L
        return pieces.mapIndexed { index, piece ->
            consumedWeight += weights[index].toLong()
            val next = if (index == pieces.lastIndex) endMs else {
                val proportionalEnd = startMs + (duration * consumedWeight / totalWeight.toLong())
                proportionalEnd.coerceAtMost(endMs - (pieces.size - index - 1))
            }
            val safeEnd = max(cursor + 1L, next)
            SubtitleSegment(
                id = 0L,
                startMs = cursor,
                endMs = safeEnd,
                originalText = piece,
                englishText = piece,
                translatedText = if (targetIsEnglish) piece else null
            ).also { cursor = safeEnd }
        }
    }

    private fun splitForSubtitles(text: String): List<String> {
        val sentences = text.split(Regex("(?<=[.!?])\\s+"))
            .map(String::trim)
            .filter(String::isNotBlank)
        val output = mutableListOf<String>()
        for (sentence in sentences.ifEmpty { listOf(text) }) {
            if (sentence.length <= MAX_CHARS) {
                output += sentence
                continue
            }
            val words = sentence.split(' ').filter(String::isNotBlank)
            var current = StringBuilder()
            for (word in words) {
                if (current.isNotEmpty() && current.length + 1 + word.length > MAX_CHARS) {
                    output += current.toString()
                    current = StringBuilder()
                }
                if (current.isNotEmpty()) current.append(' ')
                current.append(word)
            }
            if (current.isNotEmpty()) output += current.toString()
        }
        return output
    }
}
