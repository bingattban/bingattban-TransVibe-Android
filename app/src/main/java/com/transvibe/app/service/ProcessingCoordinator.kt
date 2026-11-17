package com.transvibe.app.service

import com.transvibe.app.BuildConfig
import android.app.ActivityManager
import android.content.Context
import android.net.Uri
import android.os.Build
import android.os.PowerManager
import com.transvibe.app.data.error.ProcessingErrorMapper
import com.transvibe.app.data.logging.AppLogger
import com.transvibe.app.data.model.AiResourceManager
import com.transvibe.app.data.media.FfmpegAudioExtractor
import com.transvibe.app.data.media.PcmWavChunkReader
import com.transvibe.app.data.runtime.DevicePerformanceProfileStore
import com.transvibe.app.data.runtime.SessionPerformanceReport
import com.transvibe.app.data.runtime.SessionPerformanceStore
import com.transvibe.app.data.subtitle.SegmentLanguageAligner
import com.transvibe.app.data.subtitle.SubtitlePostProcessor
import com.transvibe.app.domain.asr.AdaptiveAsrRouter
import com.transvibe.app.domain.asr.AsrResourceSnapshot
import com.transvibe.app.domain.asr.AudioSignalProfile
import com.transvibe.app.domain.asr.FastAsrEngine
import com.transvibe.app.domain.asr.FastAsrRouteRequest
import com.transvibe.app.domain.asr.MoonshineSubtitleSegmenter
import com.transvibe.app.domain.concurrency.AppDispatchers
import com.transvibe.app.domain.error.DomainErrorCode
import com.transvibe.app.domain.error.ProcessingError
import com.transvibe.app.domain.error.RecoveryAction
import com.transvibe.app.domain.language.AdaptiveLanguageMonitor
import com.transvibe.app.domain.language.RecognitionQuality
import com.transvibe.app.domain.language.SourceLanguageMode
import com.transvibe.app.domain.language.SourceLanguagePolicy
import com.transvibe.app.domain.model.AiResourceState
import com.transvibe.app.domain.model.LanguageCode
import com.transvibe.app.domain.model.ModelCapability
import com.transvibe.app.domain.model.OfficialWhisperModels
import com.transvibe.app.domain.model.ProcessingJobState
import com.transvibe.app.domain.model.ProcessingResourceRequest
import com.transvibe.app.domain.model.ProcessingStage
import com.transvibe.app.domain.model.SubtitleSegment
import com.transvibe.app.domain.model.WhisperModel
import com.transvibe.app.domain.registry.AiModelRegistry
import com.transvibe.app.domain.repository.PersistedSubtitleSegment
import com.transvibe.app.domain.repository.ProcessingRepository
import com.transvibe.app.domain.repository.SettingsRepository
import com.transvibe.app.domain.runtime.ProcessingProfile
import com.transvibe.app.domain.runtime.ThermalStateController
import com.transvibe.app.domain.runtime.DevicePerformanceCalibrationSession
import com.transvibe.app.domain.runtime.PerformanceMetricsCollector
import com.transvibe.app.domain.runtime.DevicePerformanceCompatibility
import com.transvibe.app.domain.runtime.ProcessingRuntime
import com.transvibe.app.domain.whisper.RollingWhisperPrompt
import com.transvibe.app.domain.whisper.RecognitionEngineTier
import com.transvibe.app.domain.whisper.RecognitionQualityAction
import com.transvibe.app.domain.whisper.RecognitionQualityConfig
import com.transvibe.app.domain.whisper.RecognitionQualityEvaluator
import com.transvibe.app.domain.whisper.RecognitionQualityInput
import com.transvibe.app.media.decoder.AudioDecoder
import com.transvibe.app.media.inspector.MediaSourceInspector
import com.transvibe.app.media.vad.VoiceActivityDetector
import com.transvibe.app.nativebridge.moonshine.MoonshineRuntimeManager
import com.transvibe.app.nativebridge.sensevoice.SenseVoiceRuntimeManager
import com.transvibe.app.nativebridge.whisper.WhisperNative
import com.transvibe.app.nativebridge.whisper.WhisperRuntimeManager
import com.transvibe.app.translation.ContextualSubtitleTranslator
import com.transvibe.app.translation.ContextualSubtitleBatcher
import com.transvibe.app.translation.TranslationEngine
import com.transvibe.app.translation.TranslationEngineFactory
import com.transvibe.app.translation.requireInstalledForProcessing
import java.io.File
import java.util.concurrent.atomic.AtomicLong
import kotlin.math.roundToInt
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

internal class ProcessingCoordinator(
    private val context: Context,
    dependencies: SubtitleProcessingDependencies,
    private val onProgress: (String, Int) -> Unit
) {
    private val processingRepository: ProcessingRepository = dependencies.processingRepository
    private val resources: AiResourceManager = dependencies.resources
    private val mediaInspector: MediaSourceInspector = dependencies.mediaInspector
    private val translationEngines: TranslationEngineFactory = dependencies.translationEngines
    private val voiceActivityDetector: VoiceActivityDetector = dependencies.voiceActivityDetector
    private val modelRegistry: AiModelRegistry = dependencies.modelRegistry
    private val whisperRuntimeManager: WhisperRuntimeManager = dependencies.whisperRuntimeManager
    private val moonshineRuntimeManager: MoonshineRuntimeManager = dependencies.moonshineRuntimeManager
    private val moonshineBaseArabicRuntimeManager = dependencies.moonshineBaseArabicRuntimeManager
    private val senseVoiceRuntimeManager: SenseVoiceRuntimeManager = dependencies.senseVoiceRuntimeManager
    private val settingsRepository: SettingsRepository = dependencies.settingsRepository
    private val devicePerformanceProfileStore: DevicePerformanceProfileStore = dependencies.devicePerformanceProfileStore
    private val performanceMetricsStore = dependencies.performanceMetricsStore

    @Volatile private var nativeHandle: Long = 0L
    @Volatile private var activeRuntimeLease: WhisperRuntimeManager.Lease? = null
    @Volatile private var activeRuntimeModelId: String? = null
    @Volatile private var externalInterruptionRequested = false
    @Volatile private var cancellationRequested = false
    private val checkpoints = ProcessingCheckpointManager(processingRepository)
    private val exporter = SubtitleExportCoordinator(context, processingRepository, settingsRepository)

    /** Reset only when ACTION_START is accepted, before a cancel can race the worker. */
    fun startNewJob() {
        externalInterruptionRequested = false
        cancellationRequested = false
    }

    fun begin(initialProgress: Int, initialStage: ProcessingStage) {
        // Do not clear cancellation here: ACTION_CANCEL may arrive during preflight / DB IO.
        checkpoints.begin(initialProgress, initialStage)
    }

    fun requestCancellation() {
        cancellationRequested = true
        cancelNativeInference()
    }

    fun requestExternalInterruption() {
        externalInterruptionRequested = true
        cancelNativeInference()
    }

    suspend fun releaseRuntimes() {
        whisperRuntimeManager.releaseWhenIdle()
        moonshineRuntimeManager.release()
        moonshineBaseArabicRuntimeManager.release()
        senseVoiceRuntimeManager.release()
    }

    suspend fun process(
        jobId: String,
        uri: Uri,
        modelId: String,
        requestedProfile: ProcessingProfile,
        sourceLanguagePolicy: SourceLanguagePolicy,
        targetLanguage: String
    ): Boolean {
        var translator: TranslationEngine? = null
        var sessionDir: File? = null
        var completed = false
        var performanceMetrics: PerformanceMetricsCollector? = null
        val jobStartedAtNs = System.nanoTime()
        val decodePipelineMs = AtomicLong(0L)
        val languageMs = AtomicLong(0L)
        val asrMs = AtomicLong(0L)
        val vadMs = AtomicLong(0L)
        val targetTranslationMs = AtomicLong(0L)
        var selectedBackend = "UNVERIFIED"
        try {
            currentCoroutineContext().ensureActive()
            if (cancellationRequested) throw CancellationException("User requested cancellation before processing")
            val model = modelRegistry.byId(modelId) ?: throw DomainException(
                ProcessingError.ModelMissing(retryable = false)
            )
            AppLogger.event(
                "Processing",
                "job_started",
                AppLogger.Fields(
                    jobId = jobId,
                    modelId = model.id,
                    sourceLanguage = sourceLanguagePolicy.manualLanguage ?: "auto",
                    targetLanguage = targetLanguage,
                    stage = ProcessingStage.INSPECTING_MEDIA.name
                )
            )
            val manualLanguage = sourceLanguagePolicy.manualLanguage ?: "en"
            if (sourceLanguagePolicy.requiresInitialDetection && ModelCapability.LANGUAGE_DETECTION !in model.capabilities) {
                throw DomainException(
                    ProcessingError.ModelLoadFailure(
                        cause = IllegalStateException("automatic_language_detection_not_supported:${model.id}"),
                        retryable = false,
                        recovery = RecoveryAction.OPEN_AI_RESOURCES
                    )
                )
            }
            if (sourceLanguagePolicy.mode == SourceLanguageMode.MANUAL && manualLanguage != "en" &&
                ModelCapability.MULTILINGUAL_RECOGNITION !in model.capabilities
            ) {
                throw DomainException(
                    ProcessingError.ModelLoadFailure(
                        cause = IllegalStateException("multilingual_recognition_not_supported:${model.id}"),
                        retryable = false,
                        recovery = RecoveryAction.OPEN_AI_RESOURCES
                    )
                )
            }
            val initialRequest = ProcessingResourceRequest(
                sourceLanguage = if (sourceLanguagePolicy.mode == SourceLanguageMode.MANUAL) manualLanguage else "auto",
                targetLanguage = targetLanguage,
                vadRequested = true
            )
            val ready = resources.checkPipeline(model, initialRequest)
            if (ready != AiResourceState.Ready) throw DomainException(ProcessingErrorMapper.fromResource(ready))

            checkpoints.checkpoint(jobId, 3, ProcessingStage.INSPECTING_MEDIA)
            onProgress("فحص الوسائط", 3)
            mediaInspector.ensureReadable(uri)
            try {
                mediaInspector.inspectAudio(uri)
            } catch (inspection: MediaSourceInspector.InspectionException) {
                if (inspection is MediaSourceInspector.InspectionException.PermissionDenied ||
                    !FfmpegAudioExtractor(context).isAvailable()) throw inspection
                AppLogger.d("AudioPipeline", "inspector_unsupported_ffmpeg_fallback_available")
            }
            currentCoroutineContext().ensureActive()

            val resume = withContext(AppDispatchers.io) {
                processingRepository.prepareResume(jobId, RESUME_OVERLAP_MS)
            }
            val rollingSegments = resume.rollingSegments.toMutableList()
            var rollingStartIndex = resume.rollingStartIndex
            checkpoints.seedPersistedWindow(rollingSegments, rollingStartIndex)
            var detected: String? = if (sourceLanguagePolicy.mode == SourceLanguageMode.MANUAL) manualLanguage else resume.detectedLanguage
            var detectedLanguageConfidence: Float? = if (sourceLanguagePolicy.mode == SourceLanguageMode.MANUAL) 1.0f else null
            var translationChecked = false
            var detectedBranchChecked = sourceLanguagePolicy.mode == SourceLanguageMode.MANUAL
            val qualityPolicy = RecognitionQualityConfig.policy // Numeric thresholds remain disabled until benchmark evidence is supplied.
            val adaptiveLanguageMonitor = AdaptiveLanguageMonitor()
            val adaptiveAsrRouter = AdaptiveAsrRouter(minLanguageConfidenceForFastAsr = qualityPolicy.minLanguageConfidence)
            val sourcePrompt = RollingWhisperPrompt(maxSentences = 3, maxCharacters = 240)
            val englishPrompt = RollingWhisperPrompt(maxSentences = 3, maxCharacters = 240)
            sourcePrompt.seed(rollingSegments) { it.originalText.takeIf { text -> text.isNotBlank() } }
            englishPrompt.seed(rollingSegments) { it.englishText }
            var segmentId = resume.nextSegmentId
            val profile = requestedProfile
            val runtimePreferences = settingsRepository.preferences.first()
            val preferredAsrModelId = runtimePreferences.preferredAsrModelId
            // Detect with a separately installed lightweight multilingual Whisper model,
            // regardless of the model chosen for transcription. Never download implicitly.
            // If none is installed, preserve the selected model's existing behavior.
            val languageDetectionModel = OfficialWhisperModels.whisperModels
                .filter { it.id in setOf("tiny", "base", "small") &&
                    ModelCapability.LANGUAGE_DETECTION in it.capabilities && whisperRuntimeManager.isInstalled(it) }
                .minByOrNull { when (it.id) { "tiny" -> 0; "base" -> 1; else -> 2 } }
                ?: model
            AppLogger.d("LanguageDetection", "event=detector_selected selected_model=${model.id} detector=${languageDetectionModel.id} separate=${languageDetectionModel.id != model.id}")
            val thermalController = ThermalStateController()
            val initialResources = currentAsrResourceSnapshot(thermalController)
            val thermallyConstrained = initialResources.thermallyConstrained
            val activityManager = context.getSystemService(ActivityManager::class.java)
            val memoryClassMb = activityManager?.memoryClass?.coerceAtLeast(1) ?: 256
            val lowRamDevice = activityManager?.isLowRamDevice == true
            val deviceClass = when { lowRamDevice -> "low_ram_${memoryClassMb}mb"; memoryClassMb >= 512 -> "high_${memoryClassMb}mb"; memoryClassMb >= 256 -> "normal_${memoryClassMb}mb"; else -> "low_${memoryClassMb}mb" }
            var threads = 0
            val metricsCollector = PerformanceMetricsCollector(
                model = model.id,
                languageProvider = { detected ?: manualLanguage },
                deviceClass = deviceClass,
                threadsProvider = { threads },
                thermalProvider = { currentAsrResourceSnapshot(thermalController).thermalState.name }
            )
            performanceMetrics = metricsCollector
            val performanceCompatibilityKey = DevicePerformanceCompatibility.key(BuildConfig.VERSION_NAME, model)
            var devicePerformanceProfile = devicePerformanceProfileStore.load(performanceCompatibilityKey)
            val tuning = ProcessingRuntime.tuning(
                model = model,
                profile = profile,
                thermallyConstrained = thermallyConstrained,
                thermalState = initialResources.thermalState,
                lowMemory = initialResources.lowMemory,
                availableMemoryBytes = initialResources.availableMemoryBytes,
                devicePerformanceProfile = devicePerformanceProfile
            )
            // Never calibrate a multi-minute heavy inference on user media; respect the
            // runtime's model/thermal thread ceiling for lighter models.
            val calibrationSession = if (devicePerformanceProfile == null &&
                com.transvibe.app.domain.runtime.DevicePerformanceCalibrationPolicy.shouldCalibrate(
                    model, tuning.whisperThreads, thermallyConstrained, initialResources.lowMemory
                )
            ) {
                DevicePerformanceCalibrationSession(
                    compatibilityKey = performanceCompatibilityKey,
                    processors = Runtime.getRuntime().availableProcessors(),
                    memoryClassMb = memoryClassMb,
                    lowRamDevice = lowRamDevice,
                    maxThreads = tuning.whisperThreads
                )
            } else null
            threads = tuning.whisperThreads
            AppLogger.d(
                "ProcessingRuntime",
                "mode=$profile preserveOriginal=${profile.preserveOriginalTranscript} " +
                    "foreignWhisperPassCap=${profile.maxForeignLanguageWhisperPasses} threads=$threads " +
                    "chunkSeconds=${tuning.audioChunkSeconds} chunkRange=${tuning.audioChunking.minimumSeconds}-${tuning.audioChunking.maximumSeconds}s " +
                    "channelCapacity=${tuning.channelCapacity} thermalConstrained=$thermallyConstrained lowMemory=${initialResources.lowMemory} " +
                    "model=${model.id} sourceMode=${sourceLanguagePolicy.mode} " +
                    "preferredAsr=$preferredAsrModelId moonshineInstalled=${moonshineRuntimeManager.isInstalled()} " +
                    "moonshineBaseArabicInstalled=${moonshineBaseArabicRuntimeManager.isInstalled()} " +
                    "preferredAsrInstalled=${when (preferredAsrModelId) { "moonshine-base-ar" -> moonshineBaseArabicRuntimeManager.isInstalled(); "moonshine-tiny-en" -> moonshineRuntimeManager.isInstalled(); else -> false }} " +
                    "senseVoiceInstalled=${senseVoiceRuntimeManager.isInstalled()} " +
                    "calibrated=${devicePerformanceProfile != null} rtf=${devicePerformanceProfile?.typicalRtf ?: -1.0}"
            )

            val totalDurationUs = AtomicLong(1L)
            val vadInputSamples = AtomicLong(0L)
            val vadSpeechSamples = AtomicLong(0L)
            val vadRegionCount = AtomicLong(0L)
            val pipelineStartedAtNs = System.nanoTime()
            val thermalStatusAtStart = currentThermalStatus()
            suspend fun ensureWhisperRuntime(targetModel: WhisperModel): Long {
                if (nativeHandle != 0L && activeRuntimeModelId == targetModel.id) return nativeHandle
                activeRuntimeLease?.close()
                activeRuntimeLease = null
                nativeHandle = 0L
                val lease = withContext(ProcessingRuntime.inferenceDispatcher) {
                    whisperRuntimeManager.acquire(targetModel)
                }
                activeRuntimeLease = lease
                nativeHandle = lease.handle
                activeRuntimeModelId = targetModel.id
                val backend = WhisperNative.runtimeBackend(nativeHandle)
                AppLogger.d("WhisperBackend", "model=${targetModel.id} active_backend=$backend")
                if (targetModel.id == model.id) selectedBackend = backend
                if (nativeHandle == 0L) throw DomainException(
                    ProcessingError.ModelLoadFailure()
                )
                return nativeHandle
            }

            suspend fun transcribeWhisper(
                handle: Long,
                samples: FloatArray,
                offset: Int,
                sampleCount: Int,
                language: String?,
                translate: Boolean,
                prompt: String?
            ): List<WhisperNative.RecognitionSegment> {
                val started = System.nanoTime()
                val resourcesBeforeInference = currentAsrResourceSnapshot(thermalController)
                val effectiveTuning = ProcessingRuntime.tuning(
                    model = model,
                    profile = profile,
                    processors = Runtime.getRuntime().availableProcessors(),
                    thermalState = resourcesBeforeInference.thermalState,
                    lowMemory = resourcesBeforeInference.lowMemory,
                    availableMemoryBytes = resourcesBeforeInference.availableMemoryBytes,
                    devicePerformanceProfile = devicePerformanceProfile
                )
                val activeCalibrationSession = calibrationSession.takeIf {
                    devicePerformanceProfile == null && activeRuntimeModelId == model.id &&
                        !resourcesBeforeInference.thermallyConstrained && !resourcesBeforeInference.lowMemory
                }
                val canCalibrate = activeCalibrationSession != null
                val callThreads = activeCalibrationSession?.nextThreadCount(effectiveTuning.whisperThreads)
                // Even calibration must obey the current thermal/memory and model limits.
                val effectiveThreads = callThreads?.coerceAtMost(effectiveTuning.whisperThreads)
                    ?: effectiveTuning.whisperThreads
                threads = effectiveThreads
                val result = withContext(ProcessingRuntime.inferenceDispatcher) {
                    WhisperNative.transcribeDetailed(
                        handle, samples, offset, sampleCount, language, translate, effectiveThreads, prompt
                    )
                }
                if (canCalibrate) {
                    val elapsedMs = ((System.nanoTime() - started) / 1_000_000L).coerceAtLeast(1L)
                    val audioMs = (sampleCount * 1_000L / AUDIO_SAMPLE_RATE_HZ).coerceAtLeast(1L)
                    val resourcesNow = currentAsrResourceSnapshot(thermalController)
                    if (!resourcesNow.thermallyConstrained && !resourcesNow.lowMemory) {
                        activeCalibrationSession.record(effectiveThreads, elapsedMs, audioMs)?.let { calibrated ->
                            devicePerformanceProfileStore.save(calibrated)
                            devicePerformanceProfile = calibrated
                            threads = calibrated.optimalWhisperThreads
                            AppLogger.d(
                                "DeviceCalibration",
                                "event=profile_saved threads=${calibrated.optimalWhisperThreads} " +
                                    "rtf=${String.format(java.util.Locale.US, "%.3f", calibrated.typicalRtf)} " +
                                    "chunk=${calibrated.preferredChunkDurationSeconds}s prefetch=${calibrated.recommendedPrefetch} " +
                                    "memory_class_mb=${calibrated.deviceMemoryClassMb}"
                            )
                        }
                    }
                }
                val audioMs = (sampleCount * 1_000L / AUDIO_SAMPLE_RATE_HZ).coerceAtLeast(1L)
                val processingMs = ((System.nanoTime() - started) / 1_000_000L).coerceAtLeast(1L)
                val averageSegmentMs = result.map { (it.segment.endMs - it.segment.startMs).coerceAtLeast(0L) }.average()
                metricsCollector.record("whisper", audioMs, processingMs, averageSegmentMs, threadsOverride = effectiveThreads)
                AppLogger.d("WhisperPerformance", "event=inference_completed model=${activeRuntimeModelId ?: model.id} audio_ms=$audioMs processing_ms=$processingMs threads=$effectiveThreads rtf=${String.format(java.util.Locale.US, "%.3f", processingMs.toDouble() / audioMs.toDouble())} calibration=${canCalibrate}")
                return result
            }

            fun strongerInstalledModel(primary: WhisperModel, language: String, requiresTranslation: Boolean): WhisperModel? {
                val resourcesNow = currentAsrResourceSnapshot(thermalController)
                return modelRegistry.models.value
                    .asSequence()
                    .filter { candidate -> candidate.id != primary.id }
                    .filter { candidate -> candidate.performanceTier.ordinal > primary.performanceTier.ordinal }
                    .filter { candidate -> language == "en" || ModelCapability.MULTILINGUAL_RECOGNITION in candidate.capabilities }
                    .filter { candidate -> !requiresTranslation || ModelCapability.SPEECH_TO_ENGLISH_TRANSLATION in candidate.capabilities }
                    .filter(whisperRuntimeManager::isInstalled)
                    .filter { candidate ->
                        val ram = candidate.estimatedRamBytes
                        ram == null || resourcesNow.availableMemoryBytes <= 0L ||
                            (!resourcesNow.lowMemory && resourcesNow.availableMemoryBytes >= ram + WHISPER_ESCALATION_MEMORY_HEADROOM_BYTES)
                    }
                    .sortedWith(compareBy<WhisperModel> { it.performanceTier.ordinal }.thenBy { it.estimatedRamBytes ?: Long.MAX_VALUE })
                    .firstOrNull()
            }

            // Job-scoped circuit breaker: a broken ONNX model must not be retried
            // for every VAD region, wasting minutes before Whisper fallback.
            var moonshineUnavailableForJob = false
            var processedChunkIndex = 0
            suspend fun processAudioChunk(chunk: AudioDecoder.AudioChunk) {
                currentCoroutineContext().ensureActive()
                val currentChunkIndex = processedChunkIndex++
                val chunkStartedNs = System.nanoTime()
                AppLogger.event(
                    "Processing",
                    "chunk_started",
                    AppLogger.Fields(
                        jobId = jobId,
                        stage = ProcessingStage.RECOGNIZING.name,
                        modelId = activeRuntimeModelId ?: model.id,
                        sourceLanguage = detected ?: sourceLanguagePolicy.manualLanguage ?: "auto",
                        targetLanguage = targetLanguage,
                        chunkIndex = currentChunkIndex,
                        elapsedMs = (System.nanoTime() - pipelineStartedAtNs) / 1_000_000L
                    )
                )
                val durationProgress = if (totalDurationUs.get() > 1L) {
                    (chunk.endMs * 1000.0 / totalDurationUs.get().toDouble()).coerceIn(0.0, 1.0)
                } else 0.0
                val recognizeProgress = (20 + durationProgress * 55).roundToInt().coerceIn(20, 75)

                vadInputSamples.addAndGet(chunk.samples.size.toLong())
                val vadStartedNs = System.nanoTime()
                val speechRegions = voiceActivityDetector.detectSpeechRegions(chunk.samples, AUDIO_SAMPLE_RATE_HZ)
                val vadElapsedMs = (System.nanoTime() - vadStartedNs) / 1_000_000L
                vadMs.addAndGet(vadElapsedMs)
                AppLogger.d("PipelineTiming", "stage=vad job_id=$jobId chunk_index=$currentChunkIndex audio_ms=${chunk.endMs - chunk.startMs} elapsed_ms=$vadElapsedMs regions=${speechRegions.size}")
                // One pass serves both VAD metrics and the chunk-level speech density.
                val speechSampleCount = speechRegions.sumOf { (it.endSample - it.startSample).toLong() }
                vadSpeechSamples.addAndGet(speechSampleCount)
                vadRegionCount.addAndGet(speechRegions.size.toLong())
                if (speechRegions.isEmpty()) {
                    checkpoints.persistCompletedChunk(jobId, rollingSegments, rollingStartIndex, chunk.endMs, recognizeProgress, detected)
                    AppLogger.d("PipelineTiming", "stage=chunk_complete job_id=$jobId chunk_index=$currentChunkIndex elapsed_ms=${(System.nanoTime() - chunkStartedNs) / 1_000_000L} skipped_silence=true")
                    onProgress("التعرف على الكلام", recognizeProgress)
                    return
                }

                // Reuse the VAD sample count; no second traversal of speech regions.
                val chunkSpeechDensity = speechSampleCount.toFloat() / chunk.samples.size.coerceAtLeast(1)
                for (region in speechRegions) {
                    currentCoroutineContext().ensureActive()
                    val regionSampleCount = region.endSample - region.startSample
                    var materializedSpeechSamples: FloatArray? = null
                    fun speechSamplesForFastAsr(): FloatArray = materializedSpeechSamples
                        ?: chunk.samples.copyOfRange(region.startSample, region.endSample).also { materializedSpeechSamples = it }
                    val regionStartMs = chunk.startMs + region.startSample * 1_000L / AUDIO_SAMPLE_RATE_HZ
                    val signalProfile = AudioSignalProfile.fromRange(
                        chunk.samples, region.startSample, region.endSample, AUDIO_SAMPLE_RATE_HZ, chunkSpeechDensity
                    )

                    if (sourceLanguagePolicy.requiresInitialDetection && detected == null) {
                        checkpoints.checkpoint(jobId, recognizeProgress.coerceAtLeast(10), ProcessingStage.DETECTING_LANGUAGE)
                        onProgress("اكتشاف اللغة", recognizeProgress.coerceAtLeast(10))
                        val detectionStartedNs = System.nanoTime()
                        val primaryHandle = ensureWhisperRuntime(languageDetectionModel)
                        val detectionSamples = buildDistributedLanguageSample(chunk.samples, region.startSample, region.endSample)
                        var detection = withContext(ProcessingRuntime.inferenceDispatcher) {
                            WhisperNative.detectLanguageDetailed(primaryHandle, detectionSamples, 0, detectionSamples.size, threads)
                        }
                        val initialDetectionConfidence = detection.confidence ?: -1f
                        if (initialDetectionConfidence >= 0f && initialDetectionConfidence < LANGUAGE_CONFIDENCE_RETRY_THRESHOLD && regionSampleCount > detectionSamples.size) {
                            val retryCount = minOf(regionSampleCount, LANGUAGE_DETECTION_RETRY_SAMPLES)
                            val retryOffset = region.startSample + ((regionSampleCount - retryCount) / 2).coerceAtLeast(0)
                            val retry = withContext(ProcessingRuntime.inferenceDispatcher) {
                                WhisperNative.detectLanguageDetailed(primaryHandle, chunk.samples, retryOffset, retryCount, threads)
                            }
                            val retryConfidence = retry.confidence ?: -1f
                            val currentConfidence = detection.confidence ?: -1f
                            if (retryConfidence > currentConfidence) detection = retry
                        }
                        val detectedNow = detection.language.ifBlank { "en" }.lowercase()
                        detected = detectedNow
                        detectedLanguageConfidence = detection.confidence
                        languageMs.addAndGet((System.nanoTime() - detectionStartedNs) / 1_000_000L)
                        AppLogger.d("PipelineTiming", "stage=language_detection job_id=$jobId chunk_index=$currentChunkIndex elapsed_ms=${(System.nanoTime() - detectionStartedNs) / 1_000_000L} detector=${languageDetectionModel.id}")
                        AppLogger.d("LanguageDetection", "event=initial_detect engine=whisper_lang_auto_detect detector_model=${languageDetectionModel.id} job_id=$jobId language=$detectedNow confidence=${detection.confidence} distributed_samples=${detectionSamples.size}")
                        withContext(AppDispatchers.io) { processingRepository.updateDetectedLanguage(jobId, detectedNow) }
                    }

                    val lang = detected ?: manualLanguage
                    if (sourceLanguagePolicy.mode != SourceLanguageMode.MANUAL && detected != null && !detectedBranchChecked) {
                        val branchReadiness = resources.checkPipeline(
                            model,
                            ProcessingResourceRequest(sourceLanguage = lang, targetLanguage = targetLanguage, vadRequested = true)
                        )
                        if (branchReadiness != AiResourceState.Ready) {
                            throw DomainException(ProcessingErrorMapper.fromResource(branchReadiness))
                        }
                        detectedBranchChecked = true
                    }

                    val needsTargetTranslator = targetLanguage != "en" && lang != targetLanguage
                    if (needsTargetTranslator && !translationChecked) {
                        val engine = translationEngines.create(LanguageCode.ENGLISH, LanguageCode.of(targetLanguage))
                        if (!engine.isReady()) {
                            engine.close()
                            throw DomainException(ProcessingError.TranslationPackMissing())
                        }
                        translator = engine
                        translationChecked = true
                    }

                    checkpoints.checkpoint(jobId, recognizeProgress.coerceAtLeast(20), ProcessingStage.RECOGNIZING)
                    onProgress("التعرف على الكلام", recognizeProgress.coerceAtLeast(20))

                    suspend fun recognize(handle: Long): Pair<List<SubtitleSegment>, List<WhisperNative.RecognitionSegment>> {
                        var evidence: List<WhisperNative.RecognitionSegment> = emptyList()
                        val output = when {
                            lang == "en" -> {
                                val recognized = transcribeWhisper(
                                    handle, chunk.samples, region.startSample, regionSampleCount, "en", false, englishPrompt.valueOrNull()
                                )
                                evidence = recognized
                                recognized.mapNotNull { detailed ->
                                    val r = detailed.segment
                                    val text = r.originalText.trim()
                                    if (text.isBlank()) null else SubtitleSegment(0L, regionStartMs + r.startMs, (regionStartMs + r.endMs).coerceAtLeast(regionStartMs + r.startMs + 1L), text, text, if (targetLanguage == "en") text else null)
                                }
                            }
                            lang == targetLanguage -> {
                                val recognized = transcribeWhisper(
                                    handle, chunk.samples, region.startSample, regionSampleCount, lang, false, sourcePrompt.valueOrNull()
                                )
                                evidence = recognized
                                recognized.mapNotNull { detailed ->
                                    val r = detailed.segment
                                    val text = r.originalText.trim()
                                    if (text.isBlank()) null else SubtitleSegment(0L, regionStartMs + r.startMs, (regionStartMs + r.endMs).coerceAtLeast(regionStartMs + r.startMs + 1L), text, translatedText = text)
                                }
                            }
                            profile.preserveOriginalTranscript -> {
                                val originalDetailed = transcribeWhisper(
                                    handle, chunk.samples, region.startSample, regionSampleCount, lang, false, sourcePrompt.valueOrNull()
                                )
                                evidence = originalDetailed
                                val originalRaw = originalDetailed.map { it.segment }
                                currentCoroutineContext().ensureActive()
                                val englishRaw = transcribeWhisper(
                                    handle, chunk.samples, region.startSample, regionSampleCount, lang, true, englishPrompt.valueOrNull()
                                ).map { it.segment }
                                val originalSegments = originalRaw.mapNotNull { r ->
                                    val text = r.originalText.trim()
                                    if (text.isBlank()) null else SubtitleSegment(r.id, regionStartMs + r.startMs, (regionStartMs + r.endMs).coerceAtLeast(regionStartMs + r.startMs + 1L), text)
                                }
                                val englishSegments = englishRaw.mapNotNull { r ->
                                    val text = r.originalText.trim()
                                    if (text.isBlank()) null else SubtitleSegment(r.id, regionStartMs + r.startMs, (regionStartMs + r.endMs).coerceAtLeast(regionStartMs + r.startMs + 1L), text, text, if (targetLanguage == "en") text else null)
                                }
                                SegmentLanguageAligner.combineOriginalAndEnglish(originalSegments, englishSegments)
                            }
                            else -> {
                                val recognized = transcribeWhisper(
                                    handle, chunk.samples, region.startSample, regionSampleCount, lang, true, englishPrompt.valueOrNull()
                                )
                                evidence = recognized
                                recognized.mapNotNull { detailed ->
                                    val r = detailed.segment
                                    val english = r.originalText.trim()
                                    if (english.isBlank()) null else SubtitleSegment(0L, regionStartMs + r.startMs, (regionStartMs + r.endMs).coerceAtLeast(regionStartMs + r.startMs + 1L), "", english, if (targetLanguage == "en") english else null)
                                }
                            }
                        }
                        return output to evidence
                    }

                    val languageConfidence: Float? = detectedLanguageConfidence
                    val fastRoute = if (moonshineUnavailableForJob) null else adaptiveAsrRouter.choose(
                        FastAsrRouteRequest(
                            language = lang,
                            languageConfidence = languageConfidence,
                            preferredModelId = preferredAsrModelId,
                            processingProfile = profile,
                            signal = signalProfile,
                            resources = currentAsrResourceSnapshot(thermalController),
                            senseVoiceInstalled = senseVoiceRuntimeManager.isInstalled(),
                            moonshineInstalled = if (preferredAsrModelId == "moonshine-base-ar") moonshineBaseArabicRuntimeManager.isInstalled() else moonshineRuntimeManager.isInstalled()
                        )
                    )
                    var usedFastAsr = false
                    val regionAsrStartedNs = System.nanoTime()
                    var recognition: Pair<List<SubtitleSegment>, List<WhisperNative.RecognitionSegment>>

                    if (fastRoute == FastAsrEngine.SENSEVOICE) {
                        var reportedLanguage: String? = null
                        recognition = runCatching {
                            val speechSamples = speechSamplesForFastAsr()
                            val regionEndMs = regionStartMs + regionSampleCount * 1_000L / AUDIO_SAMPLE_RATE_HZ
                            val startedAtNs = System.nanoTime()
                            val result = withContext(ProcessingRuntime.inferenceDispatcher) {
                                senseVoiceRuntimeManager.transcribe(speechSamples, threads.coerceIn(1, 4), lang)
                            }
                            reportedLanguage = result.language
                            val decodeMs = (System.nanoTime() - startedAtNs) / 1_000_000L
                            val audioMs = (regionSampleCount * 1_000L / AUDIO_SAMPLE_RATE_HZ).coerceAtLeast(1L)
                            AppLogger.d("FastASR", "event=sensevoice_benchmark job_id=$jobId chunk_index=$currentChunkIndex decode_ms=$decodeMs audio_ms=$audioMs rtf=${String.format(java.util.Locale.US, "%.3f", decodeMs.toDouble()/audioMs)} language=$lang")
                            metricsCollector.record("sensevoice", audioMs, decodeMs, modelOverride = "sensevoice", languageOverride = lang, threadsOverride = threads.coerceIn(1, 4))
                            val sourceText = result.text.trim()
                            val fastAssessment = adaptiveAsrRouter.assessFastResult(
                                FastAsrEngine.SENSEVOICE,
                                sourceText,
                                lang,
                                result.language,
                                signalProfile
                            )
                            if (!fastAssessment.accepted) {
                                AppLogger.d("FastASR", "event=sensevoice_quality_fallback job_id=$jobId chunk_index=$currentChunkIndex reason=${fastAssessment.reason} expected=$lang reported=${result.language}")
                                return@runCatching emptyList<SubtitleSegment>() to emptyList<WhisperNative.RecognitionSegment>()
                            }
                            // Skip unnecessary translation when source and target match.
                            if (lang == targetLanguage) {
                                val direct = MoonshineSubtitleSegmenter.segment(
                                    sourceText, regionStartMs,
                                    regionEndMs.coerceAtLeast(regionStartMs + 1L), false
                                ).map { it.copy(englishText = if (lang == "en") it.originalText else null, translatedText = it.originalText) }
                                return@runCatching direct to emptyList<WhisperNative.RecognitionSegment>()
                            }
                            val englishText = if (lang == "en") sourceText else {
                                var bridge: TranslationEngine? = null
                                try {
                                    bridge = translationEngines.create(LanguageCode.of(lang), LanguageCode.ENGLISH)
                                    bridge.requireInstalledForProcessing()
                                    val bridgeStartedAtNs = System.nanoTime()
                                    val translatedBridge = bridge.translate(sourceText)
                                    val bridgeMs = ((System.nanoTime() - bridgeStartedAtNs) / 1_000_000L).coerceAtLeast(1L)
                                    metricsCollector.record(
                                        engine = "translation",
                                        audioDurationMs = (regionSampleCount * 1_000L / AUDIO_SAMPLE_RATE_HZ).coerceAtLeast(1L),
                                        processingDurationMs = bridgeMs,
                                        averageSegmentDurationMs = (regionSampleCount * 1_000.0 / AUDIO_SAMPLE_RATE_HZ).coerceAtLeast(0.0),
                                        modelOverride = "mlkit",
                                        languageOverride = "$lang->en",
                                        threadsOverride = 0
                                    )
                                    translatedBridge
                                } finally { runCatching { bridge?.close() } }
                            }
                            val englishSegments = MoonshineSubtitleSegmenter.segment(
                                text = englishText,
                                startMs = regionStartMs,
                                endMs = regionEndMs.coerceAtLeast(regionStartMs + 1L),
                                targetIsEnglish = targetLanguage == "en"
                            )
                            val alignedSegments = if (lang == "en") englishSegments else {
                                val originalSegments = MoonshineSubtitleSegmenter.segment(
                                    text = sourceText,
                                    startMs = regionStartMs,
                                    endMs = regionEndMs.coerceAtLeast(regionStartMs + 1L),
                                    targetIsEnglish = false
                                ).map { it.copy(englishText = null, translatedText = null) }
                                SegmentLanguageAligner.combineOriginalAndEnglish(originalSegments, englishSegments)
                            }
                            alignedSegments to emptyList<WhisperNative.RecognitionSegment>()
                        }.getOrElse { fastError ->
                            // Cancellation is a user stop, not a model failure. Never
                            // start Whisper fallback after a cancelled SenseVoice run.
                            if (fastError is CancellationException) throw fastError
                            currentCoroutineContext().ensureActive()
                            adaptiveAsrRouter.recordFailure(FastAsrEngine.SENSEVOICE)
                            AppLogger.e("FastASR", "event=sensevoice_failed_fallback_whisper job_id=$jobId chunk_index=$currentChunkIndex language=$lang reported=${reportedLanguage.orEmpty()}", fastError)
                            emptyList<SubtitleSegment>() to emptyList()
                        }
                        if (recognition.first.isNotEmpty()) {
                            usedFastAsr = true
                        } else {
                            currentCoroutineContext().ensureActive()
                            metricsCollector.recordFallback()
                            AppLogger.d("FastASR", "event=sensevoice_fallback_whisper job_id=$jobId chunk_index=$currentChunkIndex language=$lang")
                            // Do not keep two large ASR runtimes resident while falling back.
                            senseVoiceRuntimeManager.release()
                            recognition = recognize(ensureWhisperRuntime(model))
                        }
                    } else if (fastRoute == FastAsrEngine.MOONSHINE) {
                        recognition = runCatching {
                            val speechSamples = speechSamplesForFastAsr()
                            val regionEndMs = regionStartMs + regionSampleCount * 1_000L / AUDIO_SAMPLE_RATE_HZ
                            val moonshineStartedAtNs = System.nanoTime()
                            val text = withContext(ProcessingRuntime.inferenceDispatcher) {
                                if (preferredAsrModelId == "moonshine-base-ar") moonshineBaseArabicRuntimeManager.transcribe(speechSamples, threads.coerceIn(1, 2)) else moonshineRuntimeManager.transcribe(speechSamples, threads.coerceIn(1, 2))
                            }
                            val moonshineDecodeMs = (System.nanoTime() - moonshineStartedAtNs) / 1_000_000L
                            val moonshineAudioMs = (regionSampleCount * 1_000L / AUDIO_SAMPLE_RATE_HZ).coerceAtLeast(1L)
                            val moonshineRtf = moonshineDecodeMs.toDouble() / moonshineAudioMs.toDouble()
                            AppLogger.d(
                                "FastASR",
                                "event=moonshine_benchmark job_id=$jobId chunk_index=$currentChunkIndex " +
                                    "decode_ms=$moonshineDecodeMs audio_ms=$moonshineAudioMs " +
                                    "rtf=${String.format(java.util.Locale.US, "%.3f", moonshineRtf)} " +
                                    "samples=$regionSampleCount threads=${threads.coerceIn(1, 2)}"
                            )
                            metricsCollector.record("moonshine", moonshineAudioMs, moonshineDecodeMs, modelOverride = preferredAsrModelId, languageOverride = lang, threadsOverride = threads.coerceIn(1, 2))
                            val fastAssessment = adaptiveAsrRouter.assessFastResult(
                                FastAsrEngine.MOONSHINE,
                                text,
                                lang,
                                null,
                                signalProfile
                            )
                            if (!fastAssessment.accepted) {
                                AppLogger.d("FastASR", "event=moonshine_quality_fallback job_id=$jobId chunk_index=$currentChunkIndex reason=${fastAssessment.reason}")
                                return@runCatching emptyList<SubtitleSegment>() to emptyList<WhisperNative.RecognitionSegment>()
                            }
                            AppLogger.event(
                                "FastASR",
                                "moonshine_decoded",
                                AppLogger.Fields(
                                    jobId = jobId,
                                    stage = ProcessingStage.RECOGNIZING.name,
                                    modelId = preferredAsrModelId,
                                    sourceLanguage = lang,
                                    targetLanguage = targetLanguage,
                                    chunkIndex = currentChunkIndex,
                                    elapsedMs = (System.nanoTime() - pipelineStartedAtNs) / 1_000_000L
                                )
                            )
                            val endMs = regionEndMs.coerceAtLeast(regionStartMs + 1L)
                            val subtitles = if (lang == targetLanguage) {
                                MoonshineSubtitleSegmenter.segment(text, regionStartMs, endMs, false)
                                    .map { it.copy(englishText = if (lang == "en") it.originalText else null, translatedText = it.originalText) }
                            } else if (lang == "en") {
                                MoonshineSubtitleSegmenter.segment(text, regionStartMs, endMs, targetLanguage == "en")
                            } else {
                                // The segmenter assumes English text. Never store Arabic as englishText.
                                val original = MoonshineSubtitleSegmenter.segment(text, regionStartMs, endMs, false)
                                    .map { it.copy(englishText = null, translatedText = null) }
                                var bridge: TranslationEngine? = null
                                try {
                                    bridge = translationEngines.create(LanguageCode.of(lang), LanguageCode.ENGLISH)
                                    bridge.requireInstalledForProcessing()
                                    val english = bridge.translate(text)
                                    check(english.isNotBlank()) { "English bridge returned empty text" }
                                    val translated = MoonshineSubtitleSegmenter.segment(english, regionStartMs, endMs, targetLanguage == "en")
                                    SegmentLanguageAligner.combineOriginalAndEnglish(original, translated)
                                } finally {
                                    runCatching { bridge?.close() }
                                }
                            }
                            subtitles to emptyList<WhisperNative.RecognitionSegment>()
                        }.getOrElse { fastError ->
                            // Cancellation must propagate; it is not an ASR failure and
                            // must never start a new Whisper inference after Stop.
                            if (fastError is CancellationException) throw fastError
                            // A runtime/ONNX failure is generally persistent for the
                            // current model and job; avoid repeating expensive attempts.
                            moonshineUnavailableForJob = true
                            adaptiveAsrRouter.recordFailure(FastAsrEngine.MOONSHINE)
                            AppLogger.e("FastASR", "event=moonshine_failed_fallback_whisper job_id=$jobId chunk_index=$currentChunkIndex", fastError)
                            emptyList<SubtitleSegment>() to emptyList()
                        }
                        if (recognition.first.isNotEmpty()) {
                            usedFastAsr = true
                        } else {
                            metricsCollector.recordFallback()
                            AppLogger.d("FastASR", "event=moonshine_fallback_whisper job_id=$jobId chunk_index=$currentChunkIndex")
                            // Do not keep two ASR runtimes resident while falling back.
                            if (preferredAsrModelId == "moonshine-base-ar") moonshineBaseArabicRuntimeManager.release() else moonshineRuntimeManager.release()
                            recognition = recognize(ensureWhisperRuntime(model))
                        }
                    } else {
                        recognition = recognize(ensureWhisperRuntime(model))
                    }
                    var candidates = recognition.first
                    var qualityEvidence = recognition.second
                    var whisperAssessment: com.transvibe.app.domain.whisper.RecognitionQualityAssessment? = null
                    if (!usedFastAsr) {
                        val primaryText = candidates.joinToString(" ") { it.englishText ?: it.translatedText ?: it.originalText }.trim()
                        val primaryAssessment = RecognitionQualityEvaluator.evaluate(
                            RecognitionQualityInput(
                                transcript = primaryText,
                                evidence = qualityEvidence,
                                languageConfidence = detectedLanguageConfidence,
                                speechDurationMs = signalProfile.durationMs,
                                engineTier = RecognitionEngineTier.WHISPER_PRIMARY
                            ),
                            qualityPolicy
                        )
                        whisperAssessment = primaryAssessment
                        // FAST prioritizes latency: do not decode the same audio again with a
                        // larger Whisper model when the first pass already yielded usable text.
                        // Still allow rescue for empty/unusable recognition; BALANCED and
                        // PRESERVE_ORIGINAL retain their existing quality cascade behavior.
                        val fastUsableResult = profile == ProcessingProfile.FAST && primaryAssessment.hasUsableText
                        val escalationAllowed = !fastUsableResult &&
                            (thermalController.current() == com.transvibe.app.domain.runtime.ThermalState.NORMAL || !primaryAssessment.hasUsableText)
                        if (fastUsableResult && primaryAssessment.action == RecognitionQualityAction.ESCALATE_TO_STRONGER_MODEL) {
                            AppLogger.d("WhisperCascade", "event=fast_skipped_escalation job_id=$jobId chunk_index=$currentChunkIndex model=${model.id}")
                        }
                        if (escalationAllowed && primaryAssessment.action == RecognitionQualityAction.ESCALATE_TO_STRONGER_MODEL) {
                            val needsWhisperTranslation = lang != "en" && lang != targetLanguage
                            val stronger = strongerInstalledModel(model, lang, needsWhisperTranslation)
                            if (stronger != null) {
                                AppLogger.event(
                                    "WhisperCascade",
                                    "escalation",
                                    AppLogger.Fields(
                                        jobId = jobId,
                                        stage = ProcessingStage.RECOGNIZING.name,
                                        modelId = stronger.id,
                                        sourceLanguage = lang,
                                        targetLanguage = targetLanguage,
                                        chunkIndex = currentChunkIndex,
                                        elapsedMs = (System.nanoTime() - pipelineStartedAtNs) / 1_000_000L
                                    )
                                )
                                val strongHandle = ensureWhisperRuntime(stronger)
                                val escalated = recognize(strongHandle)
                                val escalatedText = escalated.first.joinToString(" ") { it.englishText ?: it.translatedText ?: it.originalText }.trim()
                                val escalatedAssessment = RecognitionQualityEvaluator.evaluate(
                                    RecognitionQualityInput(
                                        transcript = escalatedText,
                                        evidence = escalated.second,
                                        languageConfidence = detectedLanguageConfidence,
                                        speechDurationMs = signalProfile.durationMs,
                                        engineTier = RecognitionEngineTier.WHISPER_STRONGER
                                    ),
                                    qualityPolicy
                                )
                                if (escalatedAssessment.hasUsableText && escalatedAssessment.action != RecognitionQualityAction.RETRY) {
                                    candidates = escalated.first
                                    qualityEvidence = escalated.second
                                    whisperAssessment = escalatedAssessment
                                }
                            }
                        }
                        if (primaryAssessment.action == RecognitionQualityAction.IGNORE_AS_NO_SPEECH) {
                            candidates = emptyList()
                            qualityEvidence = emptyList()
                        }
                    }

                    asrMs.addAndGet((System.nanoTime() - regionAsrStartedNs) / 1_000_000L)
                    AppLogger.d("PipelineTiming", "stage=region_asr job_id=$jobId chunk_index=$currentChunkIndex engine=${if (usedFastAsr) "fast_asr" else "whisper"} audio_ms=${regionSampleCount * 1000L / AUDIO_SAMPLE_RATE_HZ} elapsed_ms=${(System.nanoTime() - regionAsrStartedNs) / 1_000_000L} segments=${candidates.size}")
                    for (candidate0 in candidates) {
                        val beforeSize = rollingSegments.size
                        val candidate = candidate0.copy(id = segmentId)
                        if (SubtitlePostProcessor.acceptWithDedup(rollingSegments, candidate)) {
                            SubtitlePostProcessor.compactAdjacentTail(rollingSegments)
                            if (rollingSegments.size > beforeSize) segmentId++
                        }
                    }

                    if (sourceLanguagePolicy.allowsRedetection) {
                        val textForQuality = candidates.joinToString(" ") {
                            it.englishText ?: it.translatedText ?: it.originalText
                        }.trim()
                        val qualityAssessment = whisperAssessment ?: RecognitionQualityEvaluator.evaluate(
                            RecognitionQualityInput(
                                transcript = textForQuality,
                                evidence = qualityEvidence,
                                languageConfidence = detectedLanguageConfidence,
                                speechDurationMs = signalProfile.durationMs,
                                engineTier = if (usedFastAsr) RecognitionEngineTier.FAST_ASR else RecognitionEngineTier.WHISPER_PRIMARY
                            ),
                            qualityPolicy
                        )
                        val quality = RecognitionQuality(isLowConfidence = qualityAssessment.isUncertain)
                        if (adaptiveLanguageMonitor.record(quality)) {
                            val redetectionSamples = buildDistributedLanguageSample(chunk.samples, region.startSample, region.endSample)
                            val redetectStartedNs = System.nanoTime()
                            val primaryHandle = ensureWhisperRuntime(languageDetectionModel)
                            val redetection = withContext(ProcessingRuntime.inferenceDispatcher) {
                                WhisperNative.detectLanguageDetailed(primaryHandle, redetectionSamples, 0, redetectionSamples.size, threads)
                            }
                            languageMs.addAndGet((System.nanoTime() - redetectStartedNs) / 1_000_000L)
                            val redetected = redetection.language.trim().lowercase()
                            detectedLanguageConfidence = redetection.confidence
                            if (redetected.isNotBlank() && redetected != detected) {
                                AppLogger.event(
                                    "LanguageDetection",
                                    "adaptive_redetect",
                                    AppLogger.Fields(
                                        jobId = jobId,
                                        stage = ProcessingStage.DETECTING_LANGUAGE.name,
                                        modelId = model.id,
                                        sourceLanguage = redetected,
                                        targetLanguage = targetLanguage,
                                        chunkIndex = currentChunkIndex
                                    )
                                )
                                detected = redetected
                                withContext(AppDispatchers.io) { processingRepository.updateDetectedLanguage(jobId, redetected) }
                                detectedBranchChecked = false
                                runCatching { translator?.close() }
                                translator = null
                                translationChecked = false
                                adaptiveLanguageMonitor.resetAfterLanguageChange()
                            }
                        }
                    }
                }

                checkpoints.persistCompletedChunk(jobId, rollingSegments, rollingStartIndex, chunk.endMs, recognizeProgress, detected)
                // Only persisted/finalized subtitle text is allowed to influence the next chunk.
                sourcePrompt.seed(rollingSegments) { it.originalText.takeIf { text -> text.isNotBlank() } }
                englishPrompt.seed(rollingSegments) { it.englishText }
                val overflow = (rollingSegments.size - ROLLING_WINDOW_SEGMENTS).coerceAtLeast(0)
                repeat(overflow) { rollingSegments.removeAt(0) }
                rollingStartIndex += overflow
                AppLogger.d("PipelineTiming", "stage=chunk_complete job_id=$jobId chunk_index=$currentChunkIndex elapsed_ms=${(System.nanoTime() - chunkStartedNs) / 1_000_000L} speech_regions=${speechRegions.size}")
                onProgress("التعرف على الكلام", recognizeProgress)
            }

            checkpoints.checkpoint(jobId, 5, ProcessingStage.PREPARING_AUDIO)
            onProgress("تجهيز الصوت", 5)
            coroutineScope {
                // MediaCodec can report many identical progress percentages.
                // Avoid redundant Android notification/Binder work per decoded buffer.
                val extractionProgressGate = com.transvibe.app.domain.runtime.DistinctProgressGate()
                val audioChunks = Channel<AudioDecoder.AudioChunk>(capacity = tuning.channelCapacity)
                val producer = launch(AppDispatchers.io) {
                    val decoderStartedNs = System.nanoTime()
                    try {
                        val extractionProgress: suspend (Long, Long) -> Unit = { processedUs, totalUs ->
                            totalDurationUs.set(totalUs.coerceAtLeast(1L))
                            if (detected == null) {
                                val p = if (totalUs > 0L) {
                                    val ratio = processedUs.toDouble() / totalUs.toDouble()
                                    (5 + ratio * 15).roundToInt().coerceIn(5, 20)
                                } else 8
                                if (extractionProgressGate.shouldEmit("استخراج الصوت", p)) {
                                    onProgress("استخراج الصوت", p)
                                }
                            }
                        }
                        var emittedAnyChunk = false
                        val emitAudioChunk: suspend (AudioDecoder.AudioChunk) -> Unit = { chunk ->
                            currentCoroutineContext().ensureActive()
                            emittedAnyChunk = true
                            audioChunks.send(chunk)
                        }
                        try {
                            AudioDecoder(context).decodeChunks(
                                uri = uri,
                                chunkSeconds = tuning.audioChunkSeconds,
                                overlapMs = tuning.audioChunking.overlapMs,
                                chunkingPolicy = tuning.audioChunking,
                                startMs = resume.resumeFromMs,
                                onProgress = extractionProgress,
                                onChunk = emitAudioChunk
                            )
                        } catch (decoderError: AudioDecoder.AudioPipelineException) {
                            // Preserve MediaCodec's faster bounded pipeline. FFmpegKit is a
                            // fallback ONLY when it failed before emitting a single chunk;
                            // restarting after progress could corrupt resumable timestamps.
                            val ffmpeg = FfmpegAudioExtractor(context)
                            if (emittedAnyChunk || !ffmpeg.isAvailable()) throw decoderError
                            AppLogger.d("AudioPipeline", "codec_unavailable_using_ffmpeg_pcm_fallback")
                            ffmpeg.withPcm16kMonoWav(uri) { wav ->
                                PcmWavChunkReader().decodeChunks(
                                    wav = wav, chunkSeconds = tuning.audioChunkSeconds,
                                    overlapMs = tuning.audioChunking.overlapMs,
                                    startMs = resume.resumeFromMs,
                                    onProgress = extractionProgress,
                                    onChunk = emitAudioChunk
                                )
                            }
                        }
                    } finally {
                        // Includes any waiting on the bounded channel (producer/consumer overlap).
                        decodePipelineMs.addAndGet((System.nanoTime() - decoderStartedNs) / 1_000_000L)
                        audioChunks.close()
                    }
                }
                try {
                    for (chunk in audioChunks) {
                        currentCoroutineContext().ensureActive()
                        processAudioChunk(chunk)
                    }
                    producer.join()
                } finally {
                    producer.cancel()
                    audioChunks.cancel()
                }
            }

            val pipelineElapsedMs = (System.nanoTime() - pipelineStartedAtNs) / 1_000_000L
            val thermalStatusAtEnd = currentThermalStatus()
            AppLogger.d(
                "AudioPipelineMetrics",
                "job=$jobId elapsedMs=$pipelineElapsedMs capacity=${tuning.channelCapacity} " +
                    "thermalStart=$thermalStatusAtStart thermalEnd=$thermalStatusAtEnd mode=$profile " +
                    "vadInputSamples=${vadInputSamples.get()} vadSpeechSamples=${vadSpeechSamples.get()} " +
                    "vadRegions=${vadRegionCount.get()}"
            )

            val persistedCount = withContext(AppDispatchers.io) { processingRepository.segmentCount(jobId) }
            if (persistedCount == 0) throw DomainException(ProcessingError.NativeFailure(cause = IllegalStateException("no_recognizable_speech"), retryable = false))
            val lang = detected ?: manualLanguage
            val needsTargetTranslator = targetLanguage != "en" && lang != targetLanguage
            if (needsTargetTranslator && translator == null) {
                val engine = translationEngines.create(LanguageCode.ENGLISH, LanguageCode.of(targetLanguage))
                if (!engine.isReady()) {
                    engine.close()
                    throw DomainException(ProcessingError.TranslationPackMissing())
                }
                translator = engine
                translationChecked = true
            }
            checkpoints.checkpoint(jobId, 75, ProcessingStage.RECOGNIZING); onProgress("اكتمل التعرف على الكلام", 75)

            if (needsTargetTranslator) {
                checkpoints.checkpoint(jobId, 75, ProcessingStage.TRANSLATING); onProgress("ترجمة النص", 75)
                val engine = translator ?: throw DomainException(ProcessingError.TranslationPackMissing())
                var afterIndex = -1
                var translatedCount = 0
                val contextualArabicTranslator = if (targetLanguage == "ar") ContextualSubtitleTranslator(engine) else null
                val arabicBatcher = contextualArabicTranslator?.let(::ContextualSubtitleBatcher)

                suspend fun persistTranslated(items: List<PersistedSubtitleSegment>) {
                    if (items.isEmpty()) return
                    withContext(AppDispatchers.io) { processingRepository.upsertSegmentBatch(jobId, items) }
                    translatedCount += items.size
                    val p = 75 + (translatedCount * 20.0 / persistedCount.coerceAtLeast(1)).roundToInt()
                    checkpoints.checkpoint(jobId, p.coerceAtMost(95), ProcessingStage.TRANSLATING)
                    onProgress("ترجمة النص", p.coerceAtMost(95))
                }

                val translationStartedAtNs = System.nanoTime()
                var translationAudioMs = 0L
                var translationSegments = 0
                try {
                    while (true) {
                        currentCoroutineContext().ensureActive()
                        val batch = withContext(AppDispatchers.io) {
                            processingRepository.loadSegmentBatch(jobId, afterIndex, TRANSLATION_BATCH_SEGMENTS)
                        }
                        if (batch.isEmpty()) break
                        translationAudioMs += batch.sumOf { (it.segment.endMs - it.segment.startMs).coerceAtLeast(0L) }
                        translationSegments += batch.size

                        if (arabicBatcher != null) {
                            // Translate in contextual windows, but persist all completed windows
                            // in one Room transaction per fetched batch instead of one per cue.
                            // Pending context is retained across batch boundaries.
                            persistTranslated(arabicBatcher.translateBatch(batch) {
                                currentCoroutineContext().ensureActive()
                            })
                        } else {
                            val translatedBatch = ArrayList<PersistedSubtitleSegment>(batch.size)
                            for (item in batch) {
                                currentCoroutineContext().ensureActive()
                                val segment = item.segment
                                val translated = if (segment.translatedText.isNullOrBlank()) {
                                    segment.copy(translatedText = engine.translate(segment.englishText ?: segment.originalText))
                                } else segment
                                translatedBatch += item.copy(segment = translated)
                            }
                            persistTranslated(translatedBatch)
                        }
                        afterIndex = batch.last().segmentIndex
                    }
                    if (arabicBatcher != null) persistTranslated(arabicBatcher.flush())
                    val translationMs = ((System.nanoTime() - translationStartedAtNs) / 1_000_000L).coerceAtLeast(1L)
                    targetTranslationMs.addAndGet(translationMs)
                    if (translationAudioMs > 0L) {
                        metricsCollector.record(
                            engine = "translation",
                            audioDurationMs = translationAudioMs,
                            processingDurationMs = translationMs,
                            averageSegmentDurationMs = translationAudioMs.toDouble() / translationSegments.coerceAtLeast(1),
                            modelOverride = "mlkit",
                            languageOverride = "$lang->$targetLanguage",
                            threadsOverride = 0
                        )
                    }
                } catch (ce: CancellationException) {
                    throw ce
                } catch (t: Throwable) {
                    throw DomainException(ProcessingError.TranslationFailure(t))
                }
            }

            checkpoints.checkpoint(jobId, 95, ProcessingStage.GENERATING_SUBTITLES); onProgress("إنشاء الترجمة", 95)
            val saveStartedNs = System.nanoTime()
            val videoName = withContext(AppDispatchers.io) { processingRepository.get(jobId)?.displayName }
                ?.takeIf { it.isNotBlank() } ?: "TransVibe"
            val exportDir = exporter.sessionDirectory(jobId)
            sessionDir = exportDir
            val exported = withContext(AppDispatchers.io) { exporter.exportSession(jobId, videoName, exportDir) }
            currentCoroutineContext().ensureActive()
            checkpoints.checkpoint(jobId, 97, ProcessingStage.GENERATING_SUBTITLES); onProgress("تم إنشاء ملفات الترجمة", 97)
            checkpoints.checkpoint(jobId, 99, ProcessingStage.FINALIZING); onProgress("إنهاء المعالجة", 99)
            var exportFailure = ""
            val automatic = withContext(AppDispatchers.io) {
                runCatching { exporter.exportAutomaticCopies(jobId, videoName) }
                    .onFailure {
                        exportFailure = it.message ?: it.javaClass.simpleName
                        AppLogger.d("SubtitleExport", "automatic_export_failed job_id=$jobId cause=$exportFailure")
                    }.getOrNull()
            }
            val persistable = exporter.isSourcePersistable(uri)
            val srt = exported.srt
            val vtt = exported.vtt
            val saveMs = (System.nanoTime() - saveStartedNs) / 1_000_000L
            val report = SessionPerformanceReport(
                jobId = jobId, videoName = videoName, modelId = model.id,
                sourceLanguage = lang, targetLanguage = targetLanguage,
                segmentCount = persistedCount,
                elapsedMs = (System.nanoTime() - jobStartedAtNs) / 1_000_000L,
                extractionPipelineMs = decodePipelineMs.get(),
                languageDetectionMs = languageMs.get(), recognitionMs = asrMs.get(),
                translationMs = targetTranslationMs.get(), savingMs = saveMs,
                vadMs = vadMs.get(), gpuBackend = selectedBackend,
                srtLocation = automatic?.srt ?: srt.absolutePath,
                vttLocation = automatic?.vtt ?: vtt.absolutePath,
                exportFailure = exportFailure
            )
            withContext(AppDispatchers.io) {
                runCatching { SessionPerformanceStore.save(context, report) }
                    .onFailure { AppLogger.d("PerformanceReport", "report_save_failed job_id=$jobId cause=${it.javaClass.simpleName}") }
            }
            withContext(AppDispatchers.io) {
                processingRepository.completePersistedSession(
                    jobId = jobId,
                    sourceUriPersistable = persistable,
                    detectedLanguage = lang,
                    srtPath = srt.absolutePath,
                    vttPath = vtt.absolutePath
                )
            }
            AppLogger.d("PerformanceReport", "job_id=$jobId decode_pipeline_ms=${report.extractionPipelineMs} " +
                "language_ms=${report.languageDetectionMs} asr_ms=${report.recognitionMs} " +
                "translation_ms=${report.translationMs} save_ms=${report.savingMs} " +
                "vad_ms=${report.vadMs} backend=${report.gpuBackend}")
            AppLogger.event(
                "Processing",
                "job_completed",
                AppLogger.Fields(
                    jobId = jobId,
                    stage = ProcessingStage.COMPLETED.name,
                    modelId = model.id,
                    sourceLanguage = lang,
                    targetLanguage = targetLanguage,
                    elapsedMs = (System.nanoTime() - pipelineStartedAtNs) / 1_000_000L
                )
            )
            completed = true
        } catch (ce: CancellationException) {
            cancelNativeInference()
            if (!externalInterruptionRequested) {
                checkpoints.persistTerminalState(
                    jobId,
                    ProcessingJobState.CANCELLED,
                    DomainErrorCode.CANCELLED.stableCode
                )
                AppLogger.event(
                    "ProcessingService",
                    "cancel_persisted",
                    AppLogger.Fields(jobId = jobId, stage = checkpoints.lastPersistedStage.name)
                )
            }
            throw ce
        } catch (e: DomainException) {
            AppLogger.error("Processing", e.error, "job_failed", AppLogger.Fields(jobId = jobId, stage = checkpoints.lastPersistedStage.name, modelId = modelId, targetLanguage = targetLanguage))
            if (externalInterruptionRequested) Unit
            else if (cancellationRequested || e.error.code == DomainErrorCode.CANCELLED) {
                checkpoints.persistTerminalState(jobId, ProcessingJobState.CANCELLED, e.error.code.stableCode)
            } else {
                checkpoints.persistTerminalState(jobId, ProcessingJobState.FAILED, e.error.code.stableCode)
            }
        } catch (t: Throwable) {
            if (externalInterruptionRequested) Unit
            else if (cancellationRequested) {
                checkpoints.persistTerminalState(jobId, ProcessingJobState.CANCELLED, DomainErrorCode.CANCELLED.stableCode)
            } else {
                val mapped = ProcessingErrorMapper.fromThrowable(t)
                AppLogger.error("Processing", mapped, "job_failed", AppLogger.Fields(jobId = jobId, stage = checkpoints.lastPersistedStage.name, modelId = modelId, targetLanguage = targetLanguage))
                if (mapped.code == DomainErrorCode.CANCELLED) {
                    checkpoints.persistTerminalState(jobId, ProcessingJobState.CANCELLED, mapped.code.stableCode)
                } else {
                    checkpoints.persistTerminalState(jobId, ProcessingJobState.FAILED, mapped.code.stableCode)
                }
            }
        } finally {
            try {
                val metrics = performanceMetrics?.snapshot().orEmpty()
                if (metrics.isNotEmpty()) performanceMetricsStore.append(metrics)
            } catch (_: Throwable) {
                // Metrics are diagnostic only; never affect processing completion.
            }
            runCatching { translator?.close() }
            if (!completed) runCatching { sessionDir?.deleteRecursively() }
            nativeHandle = 0L
            activeRuntimeModelId = null
            activeRuntimeLease?.close()
            activeRuntimeLease = null
        }
        return completed
    }


    fun cancelNativeInference() {
        activeRuntimeLease?.cancel() ?: whisperRuntimeManager.cancelActive()
    }
    private fun isPowerSaveMode(): Boolean = context.getSystemService(PowerManager::class.java).isPowerSaveMode

    private fun currentAsrResourceSnapshot(thermalController: ThermalStateController): AsrResourceSnapshot {
        val manager = context.getSystemService(ActivityManager::class.java)
        val info = ActivityManager.MemoryInfo()
        manager.getMemoryInfo(info)
        return AsrResourceSnapshot(
            thermalState = thermalController.update(currentThermalStatus(), isPowerSaveMode()),
            thermallyConstrained = thermalController.current() != com.transvibe.app.domain.runtime.ThermalState.NORMAL,
            lowMemory = info.lowMemory,
            availableMemoryBytes = info.availMem
        )
    }

    private fun currentThermalStatus(): Int = if (Build.VERSION.SDK_INT >= 29) {
        context.getSystemService(PowerManager::class.java).currentThermalStatus
    } else {
        -1
    }

    private class DomainException(val error: ProcessingError) : Exception(error.code.stableCode, error.technicalCause)

    private fun buildDistributedLanguageSample(samples: FloatArray, start: Int, end: Int): FloatArray {
        val safeStart = start.coerceIn(0, samples.size)
        val safeEnd = end.coerceIn(safeStart, samples.size)
        val count = safeEnd - safeStart
        if (count <= LANGUAGE_DETECTION_SAMPLES) return samples.copyOfRange(safeStart, safeEnd)
        val window = LANGUAGE_DETECTION_WINDOW_SAMPLES
        val middle = safeStart + count / 2
        val starts = intArrayOf(
            safeStart,
            (middle - window / 2).coerceIn(safeStart, safeEnd - window),
            (safeEnd - window).coerceAtLeast(safeStart)
        )
        val out = FloatArray(window * starts.size)
        var dst = 0
        for (src in starts) {
            samples.copyInto(out, dst, src, src + window)
            dst += window
        }
        return out
    }

    private companion object {
        const val AUDIO_SAMPLE_RATE_HZ = 16_000
        const val LANGUAGE_DETECTION_SAMPLES = 12 * AUDIO_SAMPLE_RATE_HZ
        const val LANGUAGE_DETECTION_WINDOW_SAMPLES = 4 * AUDIO_SAMPLE_RATE_HZ
        const val LANGUAGE_DETECTION_RETRY_SAMPLES = 18 * AUDIO_SAMPLE_RATE_HZ
        const val LANGUAGE_CONFIDENCE_RETRY_THRESHOLD = 0.65f
        const val RESUME_OVERLAP_MS = 3_000L
        const val ROLLING_WINDOW_SEGMENTS = 12
        const val TRANSLATION_BATCH_SEGMENTS = 48
        const val WHISPER_ESCALATION_MEMORY_HEADROOM_BYTES = 192L * 1024L * 1024L
    }
}
