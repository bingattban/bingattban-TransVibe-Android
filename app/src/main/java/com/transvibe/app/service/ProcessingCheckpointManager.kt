package com.transvibe.app.service

import com.transvibe.app.data.logging.AppLogger
import com.transvibe.app.domain.concurrency.AppDispatchers
import com.transvibe.app.domain.model.ProcessingJobState
import com.transvibe.app.domain.model.ProcessingStage
import com.transvibe.app.domain.model.SubtitleSegment
import com.transvibe.app.domain.repository.ProcessingRepository
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext

internal class ProcessingCheckpointManager(
    private val processingRepository: ProcessingRepository
) {
    @Volatile private var lastPersistedProgress = -1
    @Volatile var lastPersistedStage: ProcessingStage = ProcessingStage.NONE
        private set
    private val persistedSegmentSignatures = HashMap<Int, PersistedSegmentSignature>()

    fun begin(initialProgress: Int, initialStage: ProcessingStage) {
        lastPersistedProgress = initialProgress
        lastPersistedStage = initialStage
        persistedSegmentSignatures.clear()
    }

    fun seedPersistedWindow(segments: List<SubtitleSegment>, firstSegmentIndex: Int) {
        persistedSegmentSignatures.clear()
        segments.forEachIndexed { offset, segment ->
            persistedSegmentSignatures[firstSegmentIndex + offset] = segment.persistenceSignature()
        }
    }

    suspend fun persistCompletedChunk(
        jobId: String,
        rollingSegments: List<SubtitleSegment>,
        rollingStartIndex: Int,
        completedMediaTimestampMs: Long,
        progress: Int,
        detectedLanguage: String?
    ) {
        val durableProgress = maxOf(progress.coerceIn(0, 100), lastPersistedProgress.coerceAtLeast(0))
        val firstDirtyOffset = rollingSegments.indices.firstOrNull { offset ->
            val absoluteIndex = rollingStartIndex + offset
            persistedSegmentSignatures[absoluteIndex] != rollingSegments[offset].persistenceSignature()
        }
        val dirtyTail = if (firstDirtyOffset == null) emptyList() else rollingSegments.subList(firstDirtyOffset, rollingSegments.size)
        val dirtyStartIndex = rollingStartIndex + (firstDirtyOffset ?: rollingSegments.size)
        withContext(AppDispatchers.io) {
            processingRepository.persistCompletedChunk(
                jobId = jobId,
                segments = dirtyTail,
                firstSegmentIndex = dirtyStartIndex,
                lastCompletedMediaTimestampMs = completedMediaTimestampMs,
                progress = durableProgress.toFloat(),
                detectedLanguage = detectedLanguage
            )
        }
        if (firstDirtyOffset != null) {
            persistedSegmentSignatures.keys.removeAll { it >= dirtyStartIndex }
            dirtyTail.forEachIndexed { offset, segment ->
                persistedSegmentSignatures[dirtyStartIndex + offset] = segment.persistenceSignature()
            }
        }
        val minimumRetainedIndex = rollingStartIndex
        persistedSegmentSignatures.keys.removeAll { it < minimumRetainedIndex }
        lastPersistedProgress = durableProgress
        lastPersistedStage = ProcessingStage.RECOGNIZING
    }

    suspend fun checkpoint(jobId: String, progress: Int, stage: ProcessingStage) {
        val normalized = maxOf(progress.coerceIn(0, 100), lastPersistedProgress.coerceAtLeast(0))
        if (normalized == lastPersistedProgress && stage == lastPersistedStage) return
        withContext(AppDispatchers.io) {
            processingRepository.updateCheckpoint(
                jobId = jobId,
                state = ProcessingJobState.RUNNING,
                progress = normalized.toFloat(),
                stage = stage
            )
        }
        lastPersistedProgress = normalized
        lastPersistedStage = stage
        AppLogger.event("Processing", "stage_checkpoint", AppLogger.Fields(jobId = jobId, stage = stage.name))
    }

    suspend fun persistTerminalState(jobId: String, state: ProcessingJobState, errorCode: String?) {
        withContext(NonCancellable + AppDispatchers.io) {
            processingRepository.finish(jobId, state, errorCode)
        }
    }

    private data class PersistedSegmentSignature(
        val id: Long,
        val startMs: Long,
        val endMs: Long,
        val originalText: String,
        val englishText: String?,
        val translatedText: String?
    )

    private fun SubtitleSegment.persistenceSignature() = PersistedSegmentSignature(
        id = id,
        startMs = startMs,
        endMs = endMs,
        originalText = originalText,
        englishText = englishText,
        translatedText = translatedText
    )

}
