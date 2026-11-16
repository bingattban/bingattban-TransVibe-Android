package com.transvibe.app.translation

import com.transvibe.app.domain.repository.PersistedSubtitleSegment

/**
 * Collects completed translation windows in memory for a single bounded Room write.
 * A context window may span two database fetches; only completed items are returned.
 * Already-translated cues are preserved verbatim and close the prior context window.
 */
class ContextualSubtitleBatcher(private val contextual: ContextualSubtitleTranslator) {
    suspend fun translateBatch(
        items: List<PersistedSubtitleSegment>,
        checkCancellation: suspend () -> Unit = {}
    ): List<PersistedSubtitleSegment> {
        if (items.isEmpty()) return emptyList()
        val ready = ArrayList<PersistedSubtitleSegment>(items.size)
        for (item in items) {
            checkCancellation()
            if (!item.segment.translatedText.isNullOrBlank()) {
                ready.addAll(contextual.flush())
                ready.add(item)
            } else {
                ready.addAll(contextual.accept(item))
            }
        }
        return ready
    }

    suspend fun flush(): List<PersistedSubtitleSegment> = contextual.flush()
}
