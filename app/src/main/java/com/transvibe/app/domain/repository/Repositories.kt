package com.transvibe.app.domain.repository

import com.transvibe.app.domain.model.StorageInfo
import com.transvibe.app.domain.model.SourceAccessState
import com.transvibe.app.domain.model.SelectedVideo
import com.transvibe.app.domain.model.ProcessingResourceRequest
import com.transvibe.app.domain.error.ProcessingError
import com.transvibe.app.domain.model.AiResourceSnapshot
import com.transvibe.app.domain.model.AppPreferences
import com.transvibe.app.domain.model.DownloadProgress
import com.transvibe.app.domain.model.LanguageCode
import com.transvibe.app.domain.model.ProcessingHistorySummary
import com.transvibe.app.domain.model.ProcessingJob
import com.transvibe.app.domain.model.ProcessingJobState
import com.transvibe.app.domain.model.ProcessingSession
import com.transvibe.app.domain.model.ProcessingStage
import com.transvibe.app.domain.model.ProcessingState
import com.transvibe.app.domain.model.StartupState
import com.transvibe.app.domain.model.SubtitleSegment
import com.transvibe.app.domain.model.TranslationResourceState
import com.transvibe.app.domain.model.TranslationPackInfo
import com.transvibe.app.domain.model.TranslationPackStorage
import com.transvibe.app.domain.model.WhisperModel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow

/** Durable processing-job boundary. Domain models only; no Android framework types. */
interface ProcessingRepository {
    val currentState: Flow<ProcessingState>
    val currentStage: Flow<ProcessingStage>
    val canResumeCurrent: Flow<Boolean>

    fun create(job: ProcessingJob)
    fun get(jobId: String): ProcessingJob?
    fun getCurrent(): ProcessingJob?
    fun prepareResume(jobId: String, overlapMs: Long, rollingLimit: Int = 12): ProcessingResumeSnapshot
    fun persistCompletedChunk(
        jobId: String,
        segments: List<SubtitleSegment>,
        firstSegmentIndex: Int,
        lastCompletedMediaTimestampMs: Long,
        progress: Float,
        detectedLanguage: String?
    )
    fun segmentCount(jobId: String): Int
    fun loadSegmentBatch(jobId: String, afterIndex: Int, limit: Int = 64): List<PersistedSubtitleSegment>
    fun upsertSegmentBatch(jobId: String, segments: List<PersistedSubtitleSegment>)
    fun segmentBatches(jobId: String, batchSize: Int = 64): Sequence<List<SubtitleSegment>>
    fun observeRecentSegmentsForCurrent(limit: Int = 3): Flow<List<SubtitleSegment>>
    fun loadSegmentsWindow(jobId: String, fromMs: Long, toMs: Long, limit: Int = 80): List<SubtitleSegment>
    fun updateSegmentText(jobId: String, segmentId: Long, originalText: String, translatedText: String?): Boolean
    fun completePersistedSession(
        jobId: String,
        sourceUriPersistable: Boolean,
        detectedLanguage: String,
        srtPath: String,
        vttPath: String,
        timestamp: Long = System.currentTimeMillis()
    )
    fun requeueCurrentInterrupted(timestamp: Long = System.currentTimeMillis()): String?
    fun selectCurrent(jobId: String)
    fun clearCurrent()
    fun updateDetectedLanguage(jobId: String, detectedLanguage: String, timestamp: Long = System.currentTimeMillis())
    fun updateCheckpoint(
        jobId: String,
        state: ProcessingJobState,
        progress: Float,
        errorCode: String? = null,
        stage: ProcessingStage = ProcessingStage.NONE,
        timestamp: Long = System.currentTimeMillis()
    )
    fun finish(jobId: String, state: ProcessingJobState, errorCode: String?, timestamp: Long = System.currentTimeMillis()): Boolean
    fun reconcileRunningJobs(isServiceActive: (String) -> Boolean, timestamp: Long = System.currentTimeMillis()): Int
}

data class ProcessingResumeSnapshot(
    val resumeFromMs: Long,
    val rollingSegments: List<SubtitleSegment>,
    val rollingStartIndex: Int,
    val nextSegmentId: Long,
    val nextSegmentIndex: Int,
    val detectedLanguage: String?
)

data class PersistedSubtitleSegment(
    val segmentIndex: Int,
    val segment: SubtitleSegment
)

/** Completed-session/history boundary, separate from active processing state. */
interface SessionRepository {
    fun observeHistory(limit: Int = 100): Flow<List<ProcessingHistorySummary>>
    fun save(session: ProcessingSession)
    fun listSummaries(limit: Int = 100): List<ProcessingHistorySummary>
    fun getSession(jobId: String): ProcessingSession?
    fun deleteSession(jobId: String): Boolean
    fun deleteSessions(jobIds: Collection<String>): Int
}

/** Model lifecycle boundary. URI transport is represented as String to keep Android types out of domain. */
interface ModelRepository {
    val resourceSnapshot: StateFlow<AiResourceSnapshot>
    val startupState: StateFlow<StartupState>
    val selectedModelId: StateFlow<String>
    val models: StateFlow<List<WhisperModel>>

    fun selectModel(id: String)
    fun selectedModel(): WhisperModel
    fun downloadState(model: WhisperModel): StateFlow<DownloadProgress>
    fun download(model: WhisperModel)
    fun retry(model: WhisperModel)
    fun importModel(model: WhisperModel, sourceUri: String)
    suspend fun exportModel(model: WhisperModel, destinationUri: String): Result<Unit>
    fun importUserModel(sourceUri: String, displayName: String? = null)
    fun pause(model: WhisperModel)
    fun cancel(model: WhisperModel)
    fun delete(model: WhisperModel)
    suspend fun refreshAll()
}

interface SettingsRepository {
    val preferences: Flow<AppPreferences>
    suspend fun setKeepScreenOnDuringPlayback(enabled: Boolean)
    suspend fun setPreferredExportFormat(format: String)
    suspend fun setProcessingProfile(profile: String)
    suspend fun setApplicationLanguage(code: String)
    suspend fun setInterfaceTextScale(scale: Float)
    suspend fun setVideoSpeechLanguage(code: String)
    suspend fun setSubtitleTargetLanguage(code: String)
    suspend fun setWifiOnlyTranslationDownloads(enabled: Boolean)
    suspend fun setPreferredAsrModelId(id: String)
    suspend fun setDefaultProcessingModelId(id: String)
    suspend fun setSubtitleTextScale(scale: Float)
    suspend fun setSubtitleDisplayMode(mode: String)
    suspend fun setSubtitleBackgroundOpacity(opacity: Float)
    suspend fun setSubtitleBackgroundStyle(style: String)
    suspend fun setSubtitleTextColor(color: String)
    suspend fun setSubtitleVerticalPosition(position: Float)
    suspend fun setSubtitleAlignment(alignment: String)
    suspend fun setThemeMode(mode: String)
    suspend fun setSubtitleOutputTreeUri(uri: String?)
}

/** Local ML Kit translation-language model lifecycle. Models are installed on demand only. */
interface TranslationPackRepository {
    val busy: StateFlow<Boolean>
    val installedPacks: StateFlow<List<TranslationPackInfo>>
    val storage: StateFlow<TranslationPackStorage>
    fun status(language: LanguageCode): StateFlow<TranslationResourceState>
    suspend fun refreshInstalled(): List<TranslationPackInfo>
    suspend fun isReady(language: LanguageCode): Boolean
    suspend fun download(language: LanguageCode): Result<Unit>
    suspend fun remove(language: LanguageCode): Result<Unit>
}

interface WhisperModelBinaryValidator {
    suspend fun validate(candidatePath: String): Boolean
    fun validationFailureMessage(): String? = null
}

/** Android-facing selection boundary expressed with URI strings. */
interface VideoSelectionGateway {
    fun select(uriString: String): SelectedVideo
}

interface ProcessingPreflight {
    suspend fun validate(video: SelectedVideo?, model: WhisperModel, request: ProcessingResourceRequest): ProcessingError?
}

interface ProcessingTaskGateway {
    suspend fun start(video: SelectedVideo, modelId: String, prefs: AppPreferences): Result<Unit>
    fun cancel()
    suspend fun resume(): Result<Unit>
    suspend fun retry(): Result<Unit>
    suspend fun clearCurrent()
}


sealed interface PlayerExportResult {
    data object Success : PlayerExportResult
    data class Failure(val error: ProcessingError) : PlayerExportResult
}

interface PlayerGateway {
    fun checkSourceAccess(session: ProcessingSession): SourceAccessState
    fun loadSubtitleWindow(sessionId: String, positionMs: Long): List<SubtitleSegment>
    fun updateSegment(sessionId: String, segment: SubtitleSegment): Boolean
    fun export(session: ProcessingSession, destinationUri: String, webVtt: Boolean): PlayerExportResult
    fun exportToPreferredDestination(session: ProcessingSession, treeUri: String, webVtt: Boolean): PlayerExportResult
}

interface StorageInspector {
    fun inspect(): StorageInfo
}

