package com.transvibe.app.service

import com.transvibe.app.data.model.AiResourceManager
import com.transvibe.app.data.runtime.DevicePerformanceProfileStore
import com.transvibe.app.data.runtime.PerformanceMetricsStore
import com.transvibe.app.domain.registry.AiModelRegistry
import com.transvibe.app.domain.repository.ProcessingRepository
import com.transvibe.app.domain.repository.SettingsRepository
import com.transvibe.app.media.inspector.MediaSourceInspector
import com.transvibe.app.media.vad.VoiceActivityDetector
import com.transvibe.app.nativebridge.moonshine.MoonshineRuntimeManager
import com.transvibe.app.nativebridge.moonshine.AdditionalMoonshineRuntimeManager
import com.transvibe.app.nativebridge.sensevoice.SenseVoiceRuntimeManager
import com.transvibe.app.nativebridge.whisper.WhisperRuntimeManager
import com.transvibe.app.translation.TranslationEngineFactory
import kotlinx.coroutines.CoroutineScope

internal data class SubtitleProcessingDependencies(
    val applicationScope: CoroutineScope,
    val processingRepository: ProcessingRepository,
    val resources: AiResourceManager,
    val mediaInspector: MediaSourceInspector,
    val translationEngines: TranslationEngineFactory,
    val voiceActivityDetector: VoiceActivityDetector,
    val modelRegistry: AiModelRegistry,
    val whisperRuntimeManager: WhisperRuntimeManager,
    val moonshineRuntimeManager: MoonshineRuntimeManager,
    val moonshineBaseArabicRuntimeManager: AdditionalMoonshineRuntimeManager,
    val senseVoiceRuntimeManager: SenseVoiceRuntimeManager,
    val settingsRepository: SettingsRepository,
    val devicePerformanceProfileStore: DevicePerformanceProfileStore,
    val performanceMetricsStore: PerformanceMetricsStore
)
