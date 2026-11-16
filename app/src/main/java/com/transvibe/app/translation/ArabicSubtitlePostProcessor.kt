package com.transvibe.app.translation

/**
 * Conservative Arabic subtitle cleanup.
 *
 * This layer intentionally avoids grammatical rewriting. It only normalizes spacing,
 * punctuation, obvious ASR/translation repetition, and subtitle line wrapping while
 * preserving lexical content, numbers, names, URLs, codes, and other LTR islands.
 */
object ArabicSubtitlePostProcessor {
    private const val TARGET_LINE_LENGTH = 42
    private const val MAX_LINE_LENGTH = 52

    fun process(text: String): String {
        if (text.isBlank()) return text.trim()
        val protected = protectLiterals(text)
        var value = protected.text
            .replace('\u00A0', ' ')
            .replace(Regex("[\\u200E\\u200F\\u202A-\\u202E\\u2066-\\u2069]"), "")
            .replace(Regex("[ \\t]+"), " ")
            .replace(Regex(" *\\n+ *"), " ")
            .trim()

        value = value
            // Convert western marks before trimming spaces preceding Arabic punctuation.
            .replace(Regex("[?]+"), "؟")
            .replace(Regex(",+"), "،")
            .replace(Regex("\\s+([،؛:؟.!…])"), "$1")
            .replace(Regex("([،؛:؟.!…])(?=[\\p{L}\\p{N}])"), "$1 ")
            .replace(Regex("؛{2,}"), "؛")
            .replace(Regex("،{2,}"), "،")
            .replace(Regex("؟{2,}"), "؟")
            .replace(Regex("!{2,}"), "!")
            .replace(Regex("\\.{4,}"), "…")
            .replace(Regex("…{2,}"), "…")
            .replace(Regex("\\s{2,}"), " ")
            .trim()

        value = collapseRunawayWordRepetition(value)
        value = collapseRunawayPhraseRepetition(value)
        return protected.restore(optimizeLineBreak(value))
    }

    private data class ProtectedText(val text: String, val literals: List<String>) {
        fun restore(value: String): String {
            var out = value
            literals.forEachIndexed { index, literal -> out = out.replace("⟦AR$index⟧", literal) }
            return out
        }
    }

    private fun protectLiterals(input: String): ProtectedText {
        val literals = ArrayList<String>()
        val regex = Regex(
            "(?i)(https?://\\S+|www\\.\\S+|[A-Z0-9._%+-]+@[A-Z0-9.-]+\\.[A-Z]{2,}|(?<![\\p{L}\\p{N}_])(?:[A-Z]{2,}[A-Z0-9_-]*|[A-Za-z]+[_-][A-Za-z0-9_-]+|\\d+(?:[.,:/-]\\d+)*)(?![\\p{L}\\p{N}_]))"
        )
        val replaced = regex.replace(input) { match ->
            val index = literals.size
            literals += match.value
            "⟦AR$index⟧"
        }
        return ProtectedText(replaced, literals)
    }

    private fun collapseRunawayWordRepetition(text: String): String {
        val words = text.split(Regex("\\s+")).filter(String::isNotBlank)
        if (words.size < 3) return text
        val out = ArrayList<String>(words.size)
        var previousNormalized: String? = null
        var runLength = 0
        for (word in words) {
            val normalized = normalizeToken(word)
            if (normalized.isNotBlank() && normalized == previousNormalized) {
                runLength++
                // Preserve a deliberate double word, but trim 3+ identical consecutive words.
                if (runLength >= 3) continue
            } else {
                previousNormalized = normalized
                runLength = 1
            }
            out += word
        }
        return out.joinToString(" ")
    }

    private fun collapseRunawayPhraseRepetition(text: String): String {
        val tokens = text.split(Regex("\\s+")).filter(String::isNotBlank)
        if (tokens.size < 6) return text
        for (phraseSize in 5 downTo 2) {
            var index = 0
            val out = ArrayList<String>(tokens.size)
            while (index < tokens.size) {
                if (index + phraseSize * 3 <= tokens.size) {
                    val a = tokens.subList(index, index + phraseSize).map(::normalizeToken)
                    val b = tokens.subList(index + phraseSize, index + phraseSize * 2).map(::normalizeToken)
                    val c = tokens.subList(index + phraseSize * 2, index + phraseSize * 3).map(::normalizeToken)
                    if (a == b && b == c && a.any(String::isNotBlank)) {
                        out += tokens.subList(index, index + phraseSize)
                        index += phraseSize * 3
                        continue
                    }
                }
                out += tokens[index]
                index++
            }
            if (out.size != tokens.size) return out.joinToString(" ")
        }
        return text
    }

    private fun optimizeLineBreak(text: String): String {
        if (text.length <= MAX_LINE_LENGTH || '\n' in text) return text
        val spaces = text.indices.filter { text[it] == ' ' }
        if (spaces.isEmpty()) return text
        val midpoint = text.length / 2
        val preferred = spaces
            .filter { it in (text.length - MAX_LINE_LENGTH)..MAX_LINE_LENGTH }
            .minByOrNull { kotlin.math.abs(it - midpoint) }
            ?: spaces.minByOrNull { kotlin.math.abs(it - TARGET_LINE_LENGTH) }
            ?: return text
        val left = text.substring(0, preferred).trimEnd()
        val right = text.substring(preferred + 1).trimStart()
        return if (left.isBlank() || right.isBlank()) text else "$left\n$right"
    }

    private fun normalizeToken(token: String): String = token
        .lowercase()
        .replace(Regex("[^\\p{L}\\p{N}]+"), "")
}
