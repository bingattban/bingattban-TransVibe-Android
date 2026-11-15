package com.transvibe.app.data.processing

import android.app.Application
import com.transvibe.app.domain.repository.ProcessingRepository
import com.transvibe.app.domain.repository.ProcessingTaskGateway
import com.transvibe.app.domain.model.*
import com.transvibe.app.domain.concurrency.AppDispatchers
import com.transvibe.app.data.logging.AppLogger
import com.transvibe.app.service.SubtitleProcessingService
import com.transvibe.app.service.ProcessingServiceRuntimeRegistry
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.withContext
import java.util.UUID

fun interface ProcessingServiceStarter {
    fun start(app: Application, jobId: String)
}

interface ProcessingServiceController : ProcessingServiceStarter {
    fun cancel(app: Application)
}

private object AndroidProcessingServiceController : ProcessingServiceController {
    override fun start(app: Application, jobId: String) = SubtitleProcessingService.start(app, jobId)
    override fun cancel(app: Application) = SubtitleProcessingService.cancel(app)
}

class ProcessingTaskController(
    private val app: Application,
    private val repository: ProcessingRepository,
    private val serviceController: ProcessingServiceController = AndroidProcessingServiceController
) : ProcessingTaskGateway {
    override suspend fun start(video: SelectedVideo, modelId: String, prefs: AppPreferences): Result<Unit> {
        if (ProcessingServiceRuntimeRegistry.hasActiveProcessing()) {
            return Result.failure(IllegalStateException("PROCESSOR_BUSY: previous native inference is still stopping"))
        }
        val jobId = UUID.randomUUID().toString()
        val now = System.currentTimeMillis()
        val job = ProcessingJob(
            jobId = jobId,
            sourceUri = video.uriString,
            displayName = video.displayName,
            selectedModelId = modelId,
            sourceLanguage = prefs.videoSpeechLanguage,
            targetLanguage = prefs.subtitleTargetLanguage,
            processingMode = prefs.processingProfile,
            state = ProcessingJobState.QUEUED,
            progress = 0f,
            checkpointTimestamp = now,
            errorCode = null,
            createdTimestamp = now,
            updatedTimestamp = now
        )
        return try {
            withContext(AppDispatchers.io) { repository.create(job) }
            AppLogger.event(
                "ProcessingLaunch", "job_persisted",
                AppLogger.Fields(
                    jobId = jobId, modelId = modelId,
                    sourceLanguage = prefs.videoSpeechLanguage, targetLanguage = prefs.subtitleTargetLanguage
                )
            )
            withContext(AppDispatchers.main) { serviceController.start(app, jobId) }
            AppLogger.event(
                "ProcessingLaunch", "service_start_dispatched",
                AppLogger.Fields(jobId = jobId, modelId = modelId)
            )
            Result.success(Unit)
        } catch (ce: CancellationException) {
            throw ce
        } catch (t: Throwable) {
            withContext(AppDispatchers.io) {
                runCatching { repository.finish(jobId, ProcessingJobState.FAILED, "SERVICE_START_FAILED") }
            }
            AppLogger.e("ProcessingLaunch", "event=service_start_failed job_id=$jobId", t)
            Result.failure(t)
        }
    }

    override fun cancel() = serviceController.cancel(app)

    override suspend fun resume(): Result<Unit> {
        // The job may be marked INTERRUPTED/CANCELLED before a long native call exits.
        // Do not requeue until the previous service has fully released inference resources.
        if (ProcessingServiceRuntimeRegistry.hasActiveProcessing()) {
            return Result.failure(IllegalStateException("PROCESSOR_BUSY: native inference is still stopping"))
        }
        val jobId = withContext(AppDispatchers.io) { repository.requeueCurrentInterrupted() }
            ?: return Result.failure(IllegalStateException("NO_INTERRUPTED_JOB"))
        return try {
            withContext(AppDispatchers.main) { serviceController.start(app, jobId) }
            Result.success(Unit)
        } catch (ce: CancellationException) {
            throw ce
        } catch (t: Throwable) {
            withContext(AppDispatchers.io) { repository.finish(jobId, ProcessingJobState.INTERRUPTED, "SERVICE_START_FAILED") }
            AppLogger.e("ProcessingLaunch", "event=resume_service_start_failed job_id=$jobId", t)
            Result.failure(t)
        }
    }

    override suspend fun retry(): Result<Unit> {
        if (ProcessingServiceRuntimeRegistry.hasActiveProcessing()) {
            return Result.failure(IllegalStateException("PROCESSOR_BUSY: native inference is still stopping"))
        }
        val current = withContext(AppDispatchers.io) { repository.getCurrent() }
            ?: return Result.failure(IllegalStateException("NO_CURRENT_JOB"))
        if (current.state == ProcessingJobState.INTERRUPTED && current.lastCompletedMediaTimestampMs > 0L) {
            return resume()
        }
        if (current.state != ProcessingJobState.FAILED) return Result.failure(IllegalStateException("JOB_NOT_RETRYABLE"))
        val now = System.currentTimeMillis()
        val retryJob = current.copy(
            jobId = UUID.randomUUID().toString(),
            state = ProcessingJobState.QUEUED,
            stage = ProcessingStage.NONE,
            progress = 0f,
            checkpointTimestamp = now,
            errorCode = null,
            createdTimestamp = now,
            updatedTimestamp = now,
            lastCompletedMediaTimestampMs = 0L,
            lastPersistedSegmentIndex = -1
        )
        return try {
            withContext(AppDispatchers.io) { repository.create(retryJob) }
            withContext(AppDispatchers.main) { serviceController.start(app, retryJob.jobId) }
            Result.success(Unit)
        } catch (ce: CancellationException) {
            throw ce
        } catch (t: Throwable) {
            withContext(AppDispatchers.io) {
                runCatching { repository.finish(retryJob.jobId, ProcessingJobState.FAILED, "SERVICE_START_FAILED") }
            }
            AppLogger.e("ProcessingLaunch", "event=retry_service_start_failed job_id=${retryJob.jobId}", t)
            Result.failure(t)
        }
    }

    override suspend fun clearCurrent() = withContext(AppDispatchers.io) { repository.clearCurrent() }
}
