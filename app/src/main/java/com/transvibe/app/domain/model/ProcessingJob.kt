package com.transvibe.app.domain.model

enum class ProcessingStage {
    INSPECTING_MEDIA,
    PREPARING_AUDIO,
    DETECTING_LANGUAGE,
    RECOGNIZING,
    TRANSLATING,
    GENERATING_SUBTITLES,
    FINALIZING,
    COMPLETED,
    // Legacy values retained for durable rows created by older builds.
    PREPARING,
    EXTRACTING_AUDIO,
    EXPORTING,
    NONE
}

enum class ProcessingJobState {
    QUEUED,
    RUNNING,
    COMPLETED,
    FAILED,
    CANCELLED,
    INTERRUPTED
}

data class ProcessingJob(
    val jobId: String,
    val sourceUri: String,
    val displayName: String,
    val selectedModelId: String,
    val sourceLanguage: String,
    val targetLanguage: String,
    val processingMode: String,
    val state: ProcessingJobState,
    val stage: ProcessingStage = ProcessingStage.NONE,
    val progress: Float,
    val checkpointTimestamp: Long,
    val errorCode: String?,
    val createdTimestamp: Long,
    val updatedTimestamp: Long,
    val lastCompletedMediaTimestampMs: Long = 0L,
    val lastPersistedSegmentIndex: Int = -1
)
