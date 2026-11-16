package com.transvibe.app.data.persistence

import com.transvibe.app.data.logging.AppLogger
import com.transvibe.app.domain.model.ProcessingHistorySummary
import com.transvibe.app.domain.model.ProcessingJobState
import com.transvibe.app.domain.model.ProcessingStage
import com.transvibe.app.domain.model.ProcessingOutcome
import com.transvibe.app.domain.model.ProcessingSession
import com.transvibe.app.domain.model.SubtitleSegment
import com.transvibe.app.domain.repository.SessionRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

class RoomProcessingHistoryRepository(
    private val dao: ProcessingDao,
    private val legacyMigrator: LegacySessionMigrator
) : SessionRepository {
    @Volatile
    private var legacyImportChecked = false
    private val legacyImportMutex = Mutex()
    private val internalFiles = InternalSessionFiles(legacyMigrator.internalSessionRoot)

    /**
     * Legacy JSON import touches Room synchronously, so it must never run from ViewModel/Compose
     * construction on the main thread. History collection performs the one-time import on IO
     * before subscribing to Room's observable history.
     */
    private suspend fun ensureLegacyImported() {
        if (legacyImportChecked) return
        legacyImportMutex.withLock {
            if (legacyImportChecked) return@withLock
            withContext(Dispatchers.IO) {
                legacyMigrator.migrateOnce()
            }
            legacyImportChecked = true
        }
    }

    override fun observeHistory(limit: Int): Flow<List<ProcessingHistorySummary>> = flow {
        ensureLegacyImported()
        emitAll(
            dao.observeTerminalSummaries(limit.coerceIn(1, 500))
                .map { rows -> rows.map(::toSummary) }
        )
    }.flowOn(Dispatchers.IO)

    override fun save(session: ProcessingSession) {
        val existing = dao.getJob(session.id)
            ?: error("Missing durable processing job ${session.id}")
        val now = System.currentTimeMillis()
        val completedJob = existing.copy(
            sourceUri = session.videoUri,
            displayName = session.videoName,
            selectedModelId = session.modelId,
            sourceLanguage = session.detectedLanguage,
            state = ProcessingJobState.COMPLETED.name,
            stage = ProcessingStage.COMPLETED.name,
            progress = 100f,
            checkpointTimestamp = now,
            errorCode = null,
            updatedTimestamp = now,
            sourceUriPersistable = session.sourceUriPersistable,
            detectedLanguage = session.detectedLanguage,
            srtPath = session.srtPath,
            vttPath = session.vttPath
        )
        val segments = session.segments.mapIndexed { index, segment -> segment.toEntity(session.id, index) }
        dao.saveCompletedSession(completedJob, segments)
    }

    override fun listSummaries(limit: Int): List<ProcessingHistorySummary> =
        dao.listTerminalSummaries(limit.coerceIn(1, 500)).map(::toSummary)

    override fun getSession(jobId: String): ProcessingSession? {
        val job = dao.getJob(jobId) ?: return null
        if (job.state != ProcessingJobState.COMPLETED.name) return null
        return ProcessingSession(
            id = job.jobId,
            videoUri = job.sourceUri,
            videoName = job.displayName,
            modelId = job.selectedModelId,
            detectedLanguage = job.detectedLanguage ?: job.sourceLanguage,
            targetLanguage = job.targetLanguage,
            createdAtEpochMs = job.createdTimestamp,
            srtPath = job.srtPath.orEmpty(),
            vttPath = job.vttPath.orEmpty(),
            // Subtitle text stays in Room and is loaded in bounded windows by the player/export path.
            segments = emptyList(),
            sourceUriPersistable = job.sourceUriPersistable,
            outcome = ProcessingOutcome.COMPLETED
        )
    }

    override fun deleteSession(jobId: String): Boolean {
        if (dao.deleteJobWithSegments(jobId) == 0) return false
        // The database delete succeeds first; exported files in Downloads/tree URIs
        // are never touched. Filesystem cleanup is limited to files/sessions/<jobId>.
        if (!internalFiles.deleteJobDirectory(jobId)) {
            AppLogger.d("SessionCleanup", "cleanup_skipped_or_failed job_id=$jobId")
        }
        return true
    }

    override fun deleteSessions(jobIds: Collection<String>): Int =
        jobIds.distinct().count(::deleteSession)

    private fun toSummary(row: ProcessingHistorySummaryRow) = ProcessingHistorySummary(
        jobId = row.jobId,
        displayName = row.displayName,
        modelId = row.selectedModelId,
        detectedLanguage = row.detectedLanguage ?: row.sourceLanguage,
        state = runCatching { ProcessingJobState.valueOf(row.state) }
            .getOrDefault(ProcessingJobState.INTERRUPTED),
        sourceUriPersistable = row.sourceUriPersistable,
        createdAtEpochMs = row.createdTimestamp,
        updatedAtEpochMs = row.updatedTimestamp
    )

    private fun SubtitleSegment.toEntity(jobId: String, index: Int) = SubtitleSegmentEntity(
        segmentId = id,
        jobId = jobId,
        segmentIndex = index,
        startTimeMs = startMs,
        endTimeMs = endMs,
        originalText = originalText,
        englishText = englishText,
        targetTranslatedText = translatedText
    )
}
