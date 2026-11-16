package com.transvibe.app

import android.app.Application
import com.transvibe.app.data.download.ModelDownloadManager
import com.transvibe.app.data.asr.AdditionalMoonshineCatalog
import com.transvibe.app.data.asr.AdditionalMoonshineModelRepository
import com.transvibe.app.data.asr.MoonshineModelRepository
import com.transvibe.app.data.asr.SenseVoiceModelRepository
import com.transvibe.app.data.asr.SenseVoiceModelStore
import com.transvibe.app.data.asr.CombinedFastAsrModelRepository
import com.transvibe.app.data.asr.MoonshineModelStore
import com.transvibe.app.data.media.FfmpegAudioExtractor
import com.transvibe.app.nativebridge.sherpa.SherpaOfflineAsrRepository
import com.transvibe.app.application.usecase.GenerateArabicSubtitlesUseCase
import com.transvibe.app.data.media.VideoSelectionRepository
import com.transvibe.app.data.model.AiResourceManager
import com.transvibe.app.data.model.DefaultAiModelRegistry
import com.transvibe.app.data.model.ModelManagementRepository
import com.transvibe.app.data.model.ModelStore
import com.transvibe.app.data.model.TranslationPackDataRepository
import com.transvibe.app.data.model.UserWhisperModelImporter
import com.transvibe.app.data.network.NetworkMonitor
import com.transvibe.app.data.persistence.AppPreferencesRepository
import com.transvibe.app.data.persistence.LegacySessionMigrator
import com.transvibe.app.data.persistence.RoomProcessingHistoryRepository
import com.transvibe.app.data.persistence.RoomProcessingRepository
import com.transvibe.app.data.persistence.TransVibeDatabase
import com.transvibe.app.data.player.PlayerRepository
import com.transvibe.app.data.processing.ProcessVideoPreflight
import com.transvibe.app.data.processing.ProcessingTaskController
import com.transvibe.app.data.runtime.DevicePerformanceProfileStore
import com.transvibe.app.data.runtime.PerformanceMetricsStore
import com.transvibe.app.data.storage.StorageRepository
import com.transvibe.app.domain.repository.ModelRepository
import com.transvibe.app.domain.repository.ProcessingRepository
import com.transvibe.app.domain.repository.SessionRepository
import com.transvibe.app.domain.repository.SettingsRepository
import com.transvibe.app.domain.repository.TranslationPackRepository
import com.transvibe.app.media.inspector.MediaSourceInspector
import com.transvibe.app.media.vad.LightweightVoiceActivityDetector
import com.transvibe.app.media.vad.SileroVoiceActivityDetector
import com.transvibe.app.nativebridge.whisper.NativeWhisperModelBinaryValidator
import com.transvibe.app.nativebridge.whisper.WhisperRuntimeManager
import com.transvibe.app.nativebridge.moonshine.MoonshineRuntimeManager
import com.transvibe.app.nativebridge.moonshine.AdditionalMoonshineRuntimeManager
import com.transvibe.app.nativebridge.sensevoice.SenseVoiceRuntimeManager
import com.transvibe.app.presentation.navigation.TransVibeViewModelFactory
import com.transvibe.app.service.ProcessingServiceRuntimeRegistry
import com.transvibe.app.service.SubtitleProcessingDependencies
import com.transvibe.app.service.SubtitleProcessingService
import com.transvibe.app.translation.DefaultTranslationEngineFactory
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * Application composition root.
 *
 * Owns application-scoped dependencies, constructs the object graph lazily, and injects
 * Android-owned components explicitly. Consumers never receive AppGraph itself.
 */
internal class AppGraph(private val app: Application) {
    private val applicationScope: CoroutineScope by lazy {
        CoroutineScope(SupervisorJob() + Dispatchers.IO)
    }
    private val modelBinaryValidator by lazy { NativeWhisperModelBinaryValidator() }
    private val modelRegistry by lazy { DefaultAiModelRegistry(app) }
    private val modelStore by lazy { ModelStore(app, modelBinaryValidator) }
    private val userModelImporter by lazy { UserWhisperModelImporter(app, modelStore, modelRegistry) }
    private val whisperRuntimeManager by lazy { WhisperRuntimeManager(modelStore) }
    private val moonshineModelStore by lazy { MoonshineModelStore(app) }
    private val moonshineRuntimeManager by lazy { MoonshineRuntimeManager(moonshineModelStore) }
    private val moonshineBaseArabicRuntimeManager by lazy {
        AdditionalMoonshineRuntimeManager(AdditionalMoonshineCatalog.baseArabicStore(app))
    }
    private val senseVoiceModelStore by lazy { SenseVoiceModelStore(app) }
    private val senseVoiceRuntimeManager by lazy { SenseVoiceRuntimeManager(senseVoiceModelStore) }
    private val networkMonitor by lazy { NetworkMonitor(app) }
    private val moonshineModelRepository by lazy { MoonshineModelRepository(moonshineModelStore, networkMonitor, applicationScope) }
    private val senseVoiceModelRepository by lazy { SenseVoiceModelRepository(senseVoiceModelStore, networkMonitor, applicationScope) }
    private val moonshineBaseArabicRepository by lazy {
        AdditionalMoonshineModelRepository(
            AdditionalMoonshineCatalog.baseArabicInfo,
            AdditionalMoonshineCatalog.baseArabicStore(app),
            applicationScope
        )
    }
    private val fastAsrModelRepository by lazy {
        CombinedFastAsrModelRepository(
            listOf(moonshineModelRepository, moonshineBaseArabicRepository, senseVoiceModelRepository)
        )
    }
    private val translationEngines by lazy { DefaultTranslationEngineFactory() }
    // Optional explicit FFmpegKit/sherpa-onnx use case; normal streaming route is untouched.
    val generateArabicSubtitlesUseCase: GenerateArabicSubtitlesUseCase by lazy {
        GenerateArabicSubtitlesUseCase(FfmpegAudioExtractor(app), SherpaOfflineAsrRepository(), translationEngines, voiceActivityDetector)
    }
    private val resources by lazy { AiResourceManager(app, modelStore, modelRegistry, translationEngines, applicationScope) }
    private val mediaInspector by lazy { MediaSourceInspector(app) }
    private val voiceActivityDetectorHolder = lazy { SileroVoiceActivityDetector(app, LightweightVoiceActivityDetector()) }
    private val voiceActivityDetector by voiceActivityDetectorHolder
    internal fun releaseVad() { if (voiceActivityDetectorHolder.isInitialized()) voiceActivityDetector.close() }
    private val processVideoPreflight by lazy {
        ProcessVideoPreflight(resources, mediaInspector) { FfmpegAudioExtractor(app).isAvailable() }
    }
    private val downloads by lazy {
        ModelDownloadManager(app, modelStore, resources, networkMonitor, modelBinaryValidator, applicationScope)
    }
    private val modelRepository: ModelRepository by lazy { ModelManagementRepository(resources, downloads, modelRegistry, userModelImporter, applicationScope) }
    private val settingsRepository: SettingsRepository by lazy { AppPreferencesRepository(app) }
    private val devicePerformanceProfileStore by lazy { DevicePerformanceProfileStore(app) }
    private val performanceMetricsStore by lazy { PerformanceMetricsStore(app) }
    private val translationPackRepository: TranslationPackRepository by lazy {
        TranslationPackDataRepository(networkMonitor, settingsRepository, applicationScope)
    }
    private val videoSelectionRepository by lazy { VideoSelectionRepository(app) }
    private val playerRepository by lazy { PlayerRepository(app, processingRepository) }
    private val storageRepository by lazy { StorageRepository(app) }
    private val database by lazy { TransVibeDatabase.create(app) }
    private val processingDao by lazy { database.processingDao() }
    private val legacySessionMigrator by lazy { LegacySessionMigrator(app, processingDao) }
    private val sessionRepository: SessionRepository by lazy {
        RoomProcessingHistoryRepository(processingDao, legacySessionMigrator)
    }
    private val processingRepository: ProcessingRepository by lazy {
        RoomProcessingRepository(processingDao, sessionRepository)
    }
    private val processingTaskController by lazy { ProcessingTaskController(app, processingRepository) }

    val viewModelFactory: TransVibeViewModelFactory by lazy {
        TransVibeViewModelFactory(
            modelRepository = modelRepository,
            processingRepository = processingRepository,
            sessionRepository = sessionRepository,
            settingsRepository = settingsRepository,
            translationPackRepository = translationPackRepository,
            fastAsrModelRepository = fastAsrModelRepository,
            videoSelectionRepository = videoSelectionRepository,
            processVideoPreflight = processVideoPreflight,
            processingTaskController = processingTaskController,
            playerRepository = playerRepository,
            storageRepository = storageRepository
        )
    }

    @Volatile private var processingMaintenanceStarted = false

    private fun startProcessingMaintenanceIfNeeded() {
        if (processingMaintenanceStarted) return
        synchronized(this) {
            if (processingMaintenanceStarted) return
            processingMaintenanceStarted = true
            applicationScope.launch {
                // Deferred until processing is actually needed; cold start does not scan legacy sessions.
                processingRepository.reconcileRunningJobs(ProcessingServiceRuntimeRegistry::isActive)
            }
        }
    }

    fun inject(service: SubtitleProcessingService) {
        startProcessingMaintenanceIfNeeded()
        service.inject(
            SubtitleProcessingDependencies(
                applicationScope = applicationScope,
                processingRepository = processingRepository,
                resources = resources,
                mediaInspector = mediaInspector,
                translationEngines = translationEngines,
                voiceActivityDetector = voiceActivityDetector,
                modelRegistry = modelRegistry,
                whisperRuntimeManager = whisperRuntimeManager,
                moonshineRuntimeManager = moonshineRuntimeManager,
                moonshineBaseArabicRuntimeManager = moonshineBaseArabicRuntimeManager,
                senseVoiceRuntimeManager = senseVoiceRuntimeManager,
                settingsRepository = settingsRepository,
                devicePerformanceProfileStore = devicePerformanceProfileStore,
            performanceMetricsStore = performanceMetricsStore
            )
        )
    }
}
