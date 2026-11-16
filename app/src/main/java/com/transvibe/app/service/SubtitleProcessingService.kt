package com.transvibe.app.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.app.ServiceCompat
import com.transvibe.app.MainActivity
import com.transvibe.app.R
import com.transvibe.app.TransVibeApplication
import com.transvibe.app.data.logging.AppLogger
import com.transvibe.app.domain.concurrency.AppDispatchers
import com.transvibe.app.domain.error.DomainErrorCode
import com.transvibe.app.domain.language.SourceLanguagePolicy
import com.transvibe.app.domain.model.ProcessingJobState
import com.transvibe.app.domain.model.ProcessingStage
import com.transvibe.app.domain.repository.ProcessingRepository
import com.transvibe.app.domain.runtime.ProcessingProfile
import com.transvibe.app.presentation.notification.NotificationPolicy
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Android lifecycle boundary for subtitle processing.
 *
 * Pipeline intelligence lives in [ProcessingCoordinator]; this service owns only foreground-service
 * lifecycle, notifications, wake-lock ownership, cancellation entry points, and durable stale-job
 * repair when Android recreates the service for a cancel command.
 */
class SubtitleProcessingService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + AppDispatchers.computation)
    private lateinit var applicationScope: CoroutineScope
    private lateinit var processingRepository: ProcessingRepository
    private lateinit var coordinator: ProcessingCoordinator

    @Volatile private var activeJob: Job? = null
    @Volatile private var activeProcessingJobId: String? = null
    private var wakeLock: PowerManager.WakeLock? = null
    @Volatile private var timeoutInterruptionPersisted = false
    // Repeated taps must not dispatch repeated native cancellation requests.
    @Volatile private var cancellationRequestedForJobId: String? = null

    override fun onCreate() {
        super.onCreate()
        (application as TransVibeApplication).inject(this)
        createChannel()
        AppLogger.event("ProcessingService", "service_created")
    }

    internal fun inject(dependencies: SubtitleProcessingDependencies) {
        applicationScope = dependencies.applicationScope
        processingRepository = dependencies.processingRepository
        coordinator = ProcessingCoordinator(
            context = applicationContext,
            dependencies = dependencies,
            onProgress = ::updateNotification
        )
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_CANCEL -> {
                cancelProcessing()
                return START_NOT_STICKY
            }

            ACTION_START -> {
                val jobId = intent.getStringExtra(EXTRA_JOB_ID) ?: return START_NOT_STICKY
                AppLogger.event("ProcessingService", "start_command_received", AppLogger.Fields(jobId = jobId))

                if (activeJob?.isCompleted == false) {
                    // A repeated ACTION_START for the same job is not a competing job.
                    // Never mark the currently RUNNING job FAILED on duplicate delivery.
                    if (activeProcessingJobId == jobId) {
                        AppLogger.event("ProcessingService", "duplicate_start_ignored", AppLogger.Fields(jobId = jobId))
                        return START_NOT_STICKY
                    }
                    scope.launch(AppDispatchers.io) {
                        if (processingRepository.get(jobId) != null) {
                            processingRepository.finish(jobId, ProcessingJobState.FAILED, ERROR_PROCESSOR_BUSY)
                        }
                    }
                    return START_NOT_STICKY
                }

                // Reset cancellation before exposing the new job to its cancellation entry points.
                coordinator.startNewJob()
                cancellationRequestedForJobId = null
                // Android requires promotion immediately; durable lookup remains off the main thread.
                promoteToForeground("تهيئة المعالجة", 1)
                activeProcessingJobId = jobId
                timeoutInterruptionPersisted = false
                ProcessingServiceRuntimeRegistry.markActive(jobId)

                val job = scope.launch {
                    var completed = false
                    var completedName: String? = null
                    try {
                    val persistedJob = withContext(AppDispatchers.io) { processingRepository.get(jobId) }
                    if (persistedJob == null) {
                        return@launch
                    }
                    val uri = runCatching { android.net.Uri.parse(persistedJob.sourceUri) }.getOrNull()
                    if (uri == null) {
                        withContext(AppDispatchers.io) {
                            processingRepository.finish(jobId, ProcessingJobState.FAILED, ERROR_INVALID_JOB)
                        }
                        return@launch
                    }
                    if (persistedJob.state != ProcessingJobState.QUEUED) {
                        return@launch
                    }

                    val requestedProfile = ProcessingProfile.fromPersisted(persistedJob.processingMode)
                    val sourceLanguagePolicy = SourceLanguagePolicy.parse(persistedJob.sourceLanguage)
                    val startingProgress = if (persistedJob.lastCompletedMediaTimestampMs > 0L) {
                        persistedJob.progress.coerceIn(1f, 75f)
                    } else {
                        1f
                    }
                    withContext(AppDispatchers.io) {
                        processingRepository.updateCheckpoint(
                            jobId,
                            ProcessingJobState.RUNNING,
                            startingProgress,
                            stage = ProcessingStage.INSPECTING_MEDIA
                        )
                    }
                    coordinator.begin(startingProgress.toInt(), ProcessingStage.INSPECTING_MEDIA)
                    acquireProcessingWakeLock()

                        completed = coordinator.process(
                            jobId = jobId,
                            uri = uri,
                            modelId = persistedJob.selectedModelId,
                            requestedProfile = requestedProfile,
                            sourceLanguagePolicy = sourceLanguagePolicy,
                            targetLanguage = persistedJob.targetLanguage.trim().lowercase().ifBlank { "ar" }
                        )
                        if (completed) completedName = persistedJob.displayName
                    } finally {
                        releaseWakeLock()
                        stopForeground(STOP_FOREGROUND_REMOVE)
                        if (completed) completedName?.let(::postCompletionNotification)
                        ProcessingServiceRuntimeRegistry.markInactive(jobId)
                        if (activeProcessingJobId == jobId) activeProcessingJobId = null
                        stopSelf(startId)
                    }
                }
                activeJob = job
                job.invokeOnCompletion { if (activeJob === job) activeJob = null }
            }
        }
        return START_NOT_STICKY
    }

    override fun onTimeout(startId: Int, fgsType: Int) {
        timeoutInterruptionPersisted = true
        coordinator.requestExternalInterruption()
        activeJob?.cancel(CancellationException("Foreground service timeout"))
        activeProcessingJobId?.let { jobId ->
            applicationScope.launch(AppDispatchers.io) {
                processingRepository.finish(
                    jobId,
                    ProcessingJobState.INTERRUPTED,
                    DomainErrorCode.INTERRUPTED.stableCode
                )
            }
        }
        releaseWakeLock()
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf(startId)
    }

    private fun cancelProcessing() {
        val knownJobId = activeProcessingJobId
        // ACTION_CANCEL is delivered on the service main thread, serializing rapid taps.
        if (knownJobId != null && cancellationRequestedForJobId == knownJobId) return
        cancellationRequestedForJobId = knownJobId
        AppLogger.event("ProcessingService", "cancel_requested", AppLogger.Fields(jobId = knownJobId))

        // Native inference may be inside a long JNI call. Set the abort flag and cancel its
        // coroutine immediately, but do not destroy the context from another thread.
        coordinator.requestCancellation()
        // Keep the notification honest while a non-interruptible native call winds down.
        if (knownJobId != null) updateNotification("جار إيقاف المعالجة...", 0)
        val runningJob = activeJob?.takeIf { !it.isCompleted }
        runningJob?.cancel(CancellationException("User requested cancellation"))

        // Persist the cancellation on the independent application scope. Waiting for the worker
        // coroutine is unsafe: a long native call can delay its catch/finally indefinitely.
        applicationScope.launch(AppDispatchers.io) {
            val persisted = knownJobId?.let(processingRepository::get) ?: processingRepository.getCurrent()
            if (persisted != null && persisted.state in setOf(
                    ProcessingJobState.QUEUED,
                    ProcessingJobState.RUNNING
                ) && processingRepository.finish(
                    persisted.jobId,
                    ProcessingJobState.CANCELLED,
                    DomainErrorCode.CANCELLED.stableCode
                )
            ) {
                AppLogger.event(
                    "ProcessingService", "cancel_persisted",
                    AppLogger.Fields(jobId = persisted.jobId)
                )
            }
        }
        // Keep foreground service/runtime resources until native inference actually returns;
        // declaring CANCELLED in Room must never free a live JNI context or launch another job.
        if (runningJob != null) return
        knownJobId?.let(ProcessingServiceRuntimeRegistry::markInactive)
        activeProcessingJobId = null
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    override fun onDestroy() {
        if (!timeoutInterruptionPersisted) {
            activeProcessingJobId?.let { jobId ->
                applicationScope.launch(AppDispatchers.io) {
                    val persisted = processingRepository.get(jobId)
                    if (persisted?.state == ProcessingJobState.RUNNING) {
                        processingRepository.finish(jobId, ProcessingJobState.INTERRUPTED, ERROR_SERVICE_DESTROYED)
                    }
                }
            }
        }
        activeProcessingJobId?.let(ProcessingServiceRuntimeRegistry::markInactive)
        coordinator.requestExternalInterruption()
        activeJob?.cancel()
        releaseWakeLock()
        applicationScope.launch { coordinator.releaseRuntimes() }
        scope.cancel()
        super.onDestroy()
    }

    private fun acquireProcessingWakeLock() {
        check(wakeLock?.isHeld != true) { "Processing WakeLock is already owned" }
        wakeLock = getSystemService(PowerManager::class.java)
            .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "TransVibe:Processing")
            .apply {
                setReferenceCounted(false)
                acquire()
            }
    }

    private fun releaseWakeLock() {
        val lock = wakeLock
        wakeLock = null
        if (lock?.isHeld == true) runCatching { lock.release() }
    }

    private fun promoteToForeground(text: String, progress: Int) {
        val type = if (Build.VERSION.SDK_INT >= 35) {
            ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROCESSING
        } else {
            0
        }
        ServiceCompat.startForeground(this, NOTIFICATION_ID, processingNotification(text, progress), type)
    }

    private fun createChannel() {
        if (Build.VERSION.SDK_INT >= 26) {
            getSystemService(NotificationManager::class.java).createNotificationChannel(
                NotificationChannel(CHANNEL, "معالجة الترجمة النصية", NotificationManager.IMPORTANCE_LOW)
            )
        }
    }

    private fun processingNotification(text: String, progress: Int): Notification {
        val launch = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val cancel = PendingIntent.getService(
            this,
            1,
            Intent(this, SubtitleProcessingService::class.java).setAction(ACTION_CANCEL),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val stopping = cancellationRequestedForJobId != null
        val builder = NotificationCompat.Builder(this, CHANNEL)
            .setSmallIcon(R.drawable.ic_stat_translation)
            .setContentTitle("TransVibe")
            .setContentText(if (stopping) "جار إيقاف المعالجة..." else text)
            .setContentIntent(launch)
            .setOnlyAlertOnce(true)
            .setOngoing(true)
            .setProgress(if (stopping) 0 else 100, if (stopping) 0 else progress, stopping)
        // Sending a second cancellation request cannot speed up native inference.
        if (!stopping) builder.addAction(android.R.drawable.ic_delete, "إلغاء", cancel)
        return builder.build()
    }

    private fun updateNotification(text: String, progress: Int) {
        // Android 13+: optional notification updates require runtime permission.
        // Foreground service creation remains handled by ServiceCompat.startForeground.
        if (Build.VERSION.SDK_INT >= 33 &&
            checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS) !=
            android.content.pm.PackageManager.PERMISSION_GRANTED
        ) return
        try {
            NotificationManagerCompat.from(this).notify(
                NOTIFICATION_ID,
                processingNotification(text, progress)
            )
        } catch (_: SecurityException) {
            // Permission may be revoked between the check and notification delivery.
        }
    }

    private fun postCompletionNotification(videoName: String) {
        val notificationPermissionGranted = Build.VERSION.SDK_INT < 33 ||
            checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS) ==
            android.content.pm.PackageManager.PERMISSION_GRANTED
        if (!NotificationPolicy.canPostCompletionNotification(Build.VERSION.SDK_INT, notificationPermissionGranted)) {
            return
        }
        val launch = PendingIntent.getActivity(
            this,
            2,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val notification = NotificationCompat.Builder(this, CHANNEL)
            .setSmallIcon(R.drawable.ic_stat_translation)
            .setContentTitle("اكتملت الترجمة النصية")
            .setContentText(videoName)
            .setContentIntent(launch)
            .setAutoCancel(true)
            .setOngoing(false)
            .build()
        try {
            NotificationManagerCompat.from(this).notify(COMPLETION_NOTIFICATION_ID, notification)
        } catch (_: SecurityException) {
            // Completion notifications are optional when permission is revoked.
        }
    }

    companion object {
        const val ACTION_START = "com.transvibe.START"
        const val ACTION_CANCEL = "com.transvibe.CANCEL"
        const val EXTRA_JOB_ID = "job_id"

        private const val CHANNEL = "processing"
        private const val NOTIFICATION_ID = 2001
        private const val COMPLETION_NOTIFICATION_ID = 2002
        private const val ERROR_PROCESSOR_BUSY = "PROCESSOR_BUSY"
        private const val ERROR_SERVICE_DESTROYED = "SERVICE_DESTROYED"
        private const val ERROR_INVALID_JOB = "INVALID_JOB"

        fun start(context: Context, jobId: String) {
            val intent = Intent(context, SubtitleProcessingService::class.java)
                .setAction(ACTION_START)
                .putExtra(EXTRA_JOB_ID, jobId)
            context.startForegroundService(intent)
        }

        fun cancel(context: Context) {
            context.startService(
                Intent(context, SubtitleProcessingService::class.java).setAction(ACTION_CANCEL)
            )
        }
    }
}
