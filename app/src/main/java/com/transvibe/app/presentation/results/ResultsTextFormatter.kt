package com.transvibe.app.presentation.results

/** Display-only formatting. Never changes stored session metadata or exported filenames. */
internal object ResultsTextFormatter {
    fun backend(rawBackend: String, cpuLabel: String, gpuLabel: String, unknownLabel: String): String {
        val parts = rawBackend.trim().split('|', limit = 2)
        val kind = parts.firstOrNull()?.trim().orEmpty()
        val label = when {
            kind.equals("CPU", ignoreCase = true) -> cpuLabel
            kind.equals("GPU", ignoreCase = true) -> gpuLabel
            else -> return unknownLabel
        }
        val detail = parts.getOrNull(1)?.trim().orEmpty()
        if (detail.isBlank() || detail.equals(kind, ignoreCase = true) || detail.equals(label, ignoreCase = true)) {
            return label
        }
        return "$label · $detail"
    }

    /** Middle-ellipsize long names in the result header, preserving the suffix/extension. */
    fun compactVideoName(original: String, maxCodePoints: Int = 44): String {
        require(maxCodePoints >= 16)
        val total = original.codePointCount(0, original.length)
        if (total <= maxCodePoints) return original
        val suffixCount = minOf(12, maxCodePoints / 3)
        val prefixCount = maxCodePoints - suffixCount - 1
        val prefixEnd = original.offsetByCodePoints(0, prefixCount)
        val suffixStart = original.offsetByCodePoints(original.length, -suffixCount)
        return original.substring(0, prefixEnd) + "…" + original.substring(suffixStart)
    }
}
