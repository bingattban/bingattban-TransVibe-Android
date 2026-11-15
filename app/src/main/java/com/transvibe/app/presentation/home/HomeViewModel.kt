package com.transvibe.app.presentation.home

import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.transvibe.app.domain.error.ProcessingError
import com.transvibe.app.domain.model.AppPreferences
import com.transvibe.app.domain.model.LanguageCode
import com.transvibe.app.domain.model.ProcessingCapabilityPlanner
import com.transvibe.app.domain.model.ProcessingResourceReadiness
import com.transvibe.app.domain.model.ProcessingResourceRequest
import com.transvibe.app.domain.model.AiCapability
import com.transvibe.app.domain.model.AiCapabilityState
import com.transvibe.app.domain.model.SelectedVideo
import com.transvibe.app.domain.repository.ModelRepository
import com.transvibe.app.domain.repository.VideoSelectionGateway
import com.transvibe.app.domain.repository.ProcessingPreflight
import com.transvibe.app.domain.repository.ProcessingTaskGateway
import com.transvibe.app.domain.repository.SettingsRepository
import com.transvibe.app.domain.repository.TranslationPackRepository
import com.transvibe.app.domain.asr.FastAsrModelRepository
import com.transvibe.app.data.logging.AppLogger
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

class HomeViewModel(
    private val models: ModelRepository,
    private val settings: SettingsRepository,
    private val translationPacks: TranslationPackRepository,
    private val fastAsrModels: FastAsrModelRepository,
    private val videoSelection: VideoSelectionGateway,
    private val preflight: ProcessingPreflight,
    private val processingTasks: ProcessingTaskGateway
) : ViewModel() {

    val resources = models.resourceSnapshot
    val selectedModelId = models.selectedModelId
    val modelRegistry = models.models
    val fastAsrModelList = fastAsrModels.models
    val availableFastAsrModels: StateFlow<List<com.transvibe.app.domain.asr.FastAsrModelInfo>> =
        combine(fastAsrModels.models.map { info -> fastAsrModels.state(info.id) }) { states ->
            fastAsrModels.models.filterIndexed { index, _ -> states[index].status == com.transvibe.app.domain.model.DownloadProgress.Status.INSTALLED }
        }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), fastAsrModels.models.filter { fastAsrModels.isInstalled(it.id) })
    private val _usingDefaultModel = MutableStateFlow(true)
    val usingDefaultModel: StateFlow<Boolean> = _usingDefaultModel.asStateFlow()
    val preferences: StateFlow<AppPreferences> = settings.preferences.stateIn(
        viewModelScope, SharingStarted.WhileSubscribed(5_000), AppPreferences()
    )

    val resourceRequest: StateFlow<ProcessingResourceRequest> = preferences
        .map(::requestFor)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), requestFor(AppPreferences()))

    private val _targetTranslationState = MutableStateFlow<AiCapabilityState>(AiCapabilityState.NotConfigured)

    /**
     * Home readiness is execution readiness, not merely the last AiResourceManager snapshot.
     * Translation packs live in ML Kit's repository, so their current state is merged here.
     * Conditional translation capabilities are also gated before starting to avoid a job that
     * reaches language detection and then fails because the target pack is absent.
     */
    val pipelineReadiness: StateFlow<ProcessingResourceReadiness> = combine(
        resources, resourceRequest, _targetTranslationState, preferences, availableFastAsrModels
    ) { snapshot, request, targetTranslationState, prefs, installedFastModels ->
        val plan = ProcessingCapabilityPlanner.plan(request)
        val selectedFastModel = prefs.preferredAsrModelId.takeIf { id ->
            id != "auto" && id != "whisper" && installedFastModels.any { it.id == id }
        }
        val capabilities = snapshot.capabilities.copy(
            targetLanguageTranslation = targetTranslationState,
            speechRecognition = if (selectedFastModel != null) AiCapabilityState.Ready else snapshot.capabilities.speechRecognition
        )
        val executionCapabilities = plan.required + plan.conditional
        val unresolved = executionCapabilities.mapNotNull { capability ->
            val state = capabilities.state(capability)
            if (state == AiCapabilityState.Ready) null else capability to state
        }.toMap()
        if (unresolved.isEmpty()) ProcessingResourceReadiness.Ready(plan)
        else ProcessingResourceReadiness.Waiting(plan, unresolved)
    }.stateIn(
        viewModelScope,
        SharingStarted.WhileSubscribed(5_000),
        ProcessingResourceReadiness.Waiting(
            ProcessingCapabilityPlanner.plan(resourceRequest.value),
            mapOf(AiCapability.SPEECH_RECOGNITION to AiCapabilityState.Checking)
        )
    )

    private val _selectedVideo = MutableStateFlow<SelectedVideo?>(null)
    val selectedVideo: StateFlow<SelectedVideo?> = _selectedVideo.asStateFlow()
    private val _preflightBusy = MutableStateFlow(false)
    val preflightBusy: StateFlow<Boolean> = _preflightBusy.asStateFlow()
    private val _message = MutableStateFlow<String?>(null)
    val message: StateFlow<String?> = _message.asStateFlow()
    private val _error = MutableStateFlow<ProcessingError?>(null)
    val error: StateFlow<ProcessingError?> = _error.asStateFlow()
    private val processingReadyChannel = Channel<Unit>(Channel.CONFLATED)
    val processingReady: Flow<Unit> = processingReadyChannel.receiveAsFlow()

    init {
        viewModelScope.launch {
            combine(preferences, resources, modelRegistry) { prefs, snapshot, registry -> Triple(prefs, snapshot, registry) }
                .collectLatest { (prefs, snapshot, registry) ->
                    if (_usingDefaultModel.value) {
                        resolveDefaultModelId(prefs, snapshot, registry)?.let { applyProcessingModel(it) }
                    }
                }
        }
        viewModelScope.launch {
            resourceRequest
                .map { it }
                .distinctUntilChanged()
                .collectLatest { request ->
                    refreshTargetTranslationPack(request, installIfMissing = true)
                }
        }
    }

    fun selectVideo(uri: Uri) { _selectedVideo.value = videoSelection.select(uri.toString()) }
    fun clearSelectedVideo() { _selectedVideo.value = null }
    fun setSourceLanguage(value: String) = viewModelScope.launch { settings.setVideoSpeechLanguage(value) }
    fun setTargetLanguage(value: String) = viewModelScope.launch { settings.setSubtitleTargetLanguage(value) }
    fun setProcessingQuality(value: String) = viewModelScope.launch { settings.setProcessingProfile(value) }

    fun useDefaultModelSelection() {
        _usingDefaultModel.value = true
        viewModelScope.launch {
            resolveDefaultModelId(preferences.value, resources.value, modelRegistry.value)?.let { applyProcessingModel(it) }
        }
    }

    fun selectProcessingModel(id: String) {
        _usingDefaultModel.value = false
        viewModelScope.launch { applyProcessingModel(id) }
    }

    private suspend fun applyProcessingModel(id: String) {
        if (fastAsrModels.models.any { it.id == id }) {
            settings.setPreferredAsrModelId(id)
        } else {
            if (selectedModelId.value != id) models.selectModel(id)
            settings.setPreferredAsrModelId("whisper")
        }
    }

    private fun resolveDefaultModelId(
        prefs: AppPreferences,
        snapshot: com.transvibe.app.domain.model.AiResourceSnapshot,
        registry: List<com.transvibe.app.domain.model.WhisperModel>
    ): String? {
        val requested = prefs.defaultProcessingModelId
        if (requested.isNotBlank()) {
            if (fastAsrModels.models.any { it.id == requested } && fastAsrModels.isInstalled(requested)) return requested
            if (registry.any { it.id == requested } && snapshot.modelState(requested) == com.transvibe.app.domain.model.ModelIntegrityState.READY) return requested
        }
        val readyWhisper = registry.filter { snapshot.modelState(it.id) == com.transvibe.app.domain.model.ModelIntegrityState.READY }
        val currentReady = selectedModelId.value.takeIf { id -> readyWhisper.any { it.id == id } }
        return currentReady
            ?: readyWhisper.firstOrNull { it.recommended }?.id
            ?: readyWhisper.minByOrNull { it.fileSizeBytes }?.id
            ?: fastAsrModels.models.firstOrNull { fastAsrModels.isInstalled(it.id) }?.id
    }


    fun requestProcessing() {
        if (_preflightBusy.value) return
        _preflightBusy.value = true
        viewModelScope.launch {
            val request = resourceRequest.value
            AppLogger.event(
                "ProcessingLaunch", "preflight_requested",
                AppLogger.Fields(
                    modelId = selectedModelId.value,
                    sourceLanguage = request.sourceLanguage,
                    targetLanguage = request.targetLanguage
                )
            )
            try {
                if (!refreshTargetTranslationPack(request, installIfMissing = true)) {
                    _error.value = ProcessingError.TranslationPackMissing()
                    AppLogger.event(
                        "ProcessingLaunch", "translation_pack_not_ready",
                        AppLogger.Fields(
                            modelId = selectedModelId.value,
                            sourceLanguage = request.sourceLanguage,
                            targetLanguage = request.targetLanguage,
                            errorCode = "translation_pack_missing"
                        )
                    )
                    return@launch
                }
                val error = preflight.validate(_selectedVideo.value, models.selectedModel(), request)
                if (error != null) {
                    _error.value = error
                    AppLogger.error(
                        "ProcessingLaunch", error, "preflight_failed",
                        AppLogger.Fields(
                            modelId = selectedModelId.value,
                            sourceLanguage = request.sourceLanguage,
                            targetLanguage = request.targetLanguage
                        )
                    )
                } else {
                    AppLogger.event(
                        "ProcessingLaunch", "preflight_passed",
                        AppLogger.Fields(
                            modelId = selectedModelId.value,
                            sourceLanguage = request.sourceLanguage,
                            targetLanguage = request.targetLanguage
                        )
                    )
                    processingReadyChannel.send(Unit)
                }
            } catch (ce: CancellationException) {
                throw ce
            } catch (t: Throwable) {
                val mapped = ProcessingError.Unknown(t)
                _error.value = mapped
                AppLogger.error("ProcessingLaunch", mapped, "preflight_unexpected_failure")
            } finally {
                _preflightBusy.value = false
            }
        }
    }

    fun startProcessing(onStarted: () -> Unit = {}) {
        val video = _selectedVideo.value ?: return
        val modelId = selectedModelId.value
        val prefs = preferences.value
        viewModelScope.launch {
            AppLogger.event(
                "ProcessingLaunch", "job_start_requested",
                AppLogger.Fields(modelId = modelId, sourceLanguage = prefs.videoSpeechLanguage, targetLanguage = prefs.subtitleTargetLanguage)
            )
            val result = processingTasks.start(video, modelId, prefs)
            if (result.isSuccess) {
                AppLogger.event(
                    "ProcessingLaunch", "job_start_dispatched",
                    AppLogger.Fields(modelId = modelId, sourceLanguage = prefs.videoSpeechLanguage, targetLanguage = prefs.subtitleTargetLanguage)
                )
                onStarted()
            } else {
                val mapped = ProcessingError.Unknown(result.exceptionOrNull())
                _error.value = mapped
                AppLogger.error(
                    "ProcessingLaunch", mapped, "job_start_failed",
                    AppLogger.Fields(modelId = modelId, sourceLanguage = prefs.videoSpeechLanguage, targetLanguage = prefs.subtitleTargetLanguage)
                )
            }
        }
    }


    private suspend fun refreshTargetTranslationPack(
        request: ProcessingResourceRequest,
        installIfMissing: Boolean
    ): Boolean {
        val plan = ProcessingCapabilityPlanner.plan(request)
        val needsTargetTranslation = AiCapability.TARGET_LANGUAGE_TRANSLATION in (plan.required + plan.conditional)
        if (!needsTargetTranslation) {
            _targetTranslationState.value = AiCapabilityState.Ready
            return true
        }

        val target = LanguageCode.of(request.targetLanguage)
        _targetTranslationState.value = AiCapabilityState.Checking
        return try {
            if (translationPacks.isReady(target)) {
                _targetTranslationState.value = AiCapabilityState.Ready
                true
            } else if (installIfMissing) {
                val result = translationPacks.download(target)
                val ready = result.isSuccess && translationPacks.isReady(target)
                _targetTranslationState.value = if (ready) {
                    AiCapabilityState.Ready
                } else {
                    AiCapabilityState.Missing("translation_pack:${target.value}")
                }
                if (!ready) {
                    result.exceptionOrNull()?.let { cause ->
                        AppLogger.error(
                            "ProcessingLaunch", ProcessingError.TranslationPackMissing(cause), "translation_pack_install_failed",
                            AppLogger.Fields(targetLanguage = target.value)
                        )
                    }
                }
                ready
            } else {
                _targetTranslationState.value = AiCapabilityState.Missing("translation_pack:${target.value}")
                false
            }
        } catch (ce: CancellationException) {
            throw ce
        } catch (t: Throwable) {
            _targetTranslationState.value = AiCapabilityState.Error("translation_pack:${target.value}")
            AppLogger.error(
                "ProcessingLaunch", ProcessingError.TranslationPackMissing(t), "translation_pack_check_failed",
                AppLogger.Fields(targetLanguage = target.value)
            )
            false
        }
    }

    fun dismissMessage() { _message.value = null }
    fun dismissError() { _error.value = null }

    private fun requestFor(prefs: AppPreferences) = ProcessingResourceRequest(
        sourceLanguage = prefs.videoSpeechLanguage,
        targetLanguage = prefs.subtitleTargetLanguage,
        vadRequested = true
    )
}
