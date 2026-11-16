package com.transvibe.app.domain.runtime

import com.transvibe.app.domain.model.WhisperModel
import com.transvibe.app.domain.model.ModelPerformanceTier
import com.transvibe.app.domain.concurrency.AppDispatchers
import kotlin.math.max

/**
 * User-visible processing policy.
 *
 * FAST and BALANCED both use the minimum Whisper pass count needed for the requested output.
 * PRESERVE_ORIGINAL may perform a second Whisper pass for non-English source speech so the
 * original-language transcript can be retained alongside the English intermediate text.
 */
enum class ProcessingProfile(
    val preserveOriginalTranscript: Boolean,
    val maxForeignLanguageWhisperPasses: Int
) {
    FAST(preserveOriginalTranscript = false, maxForeignLanguageWhisperPasses = 1),
    BALANCED(preserveOriginalTranscript = false, maxForeignLanguageWhisperPasses = 1),
    PRESERVE_ORIGINAL(preserveOriginalTranscript = true, maxForeignLanguageWhisperPasses = 2);

    companion object {
        /** POWER_SAVE was a pre-Phase-22 UI mode. Keep old persisted jobs runnable. */
        fun fromPersisted(value: String): ProcessingProfile = when (value) {
            FAST.name -> FAST
            PRESERVE_ORIGINAL.name -> PRESERVE_ORIGINAL
            else -> BALANCED
        }
    }
}

/**
 * Bounded silence-aware PCM chunking policy.
 *
 * The decoder aims for [targetSeconds], may finish earlier at a stable silence after
 * [minimumSeconds], and never waits beyond [maximumSeconds]. Values are deliberately policy data
 * rather than hidden constants so they can be benchmark-calibrated later.
 */
data class AudioChunkingPolicy(
    val minimumSeconds: Int,
    val targetSeconds: Int,
    val maximumSeconds: Int,
    val boundaryWindowMs: Int = 160,
    val overlapMs: Int = 650
) {
    init {
        require(minimumSeconds in 8..60)
        require(targetSeconds in minimumSeconds..60)
        require(maximumSeconds in targetSeconds..60)
        require(boundaryWindowMs in 80..500)
        require(overlapMs in 0..2_000)
    }
}

data class ProcessingTuning(
    val whisperThreads: Int,
    val audioChunking: AudioChunkingPolicy,
    val channelCapacity: Int = 1
) {
    /** Compatibility/readability accessor retained for logs and existing tests. */
    val audioChunkSeconds: Int get() = audioChunking.targetSeconds
}

object ProcessingRuntime {
    /**
     * All heavy native inference is serialized through one dispatcher lane. Decoder/IO work may run
     * concurrently, but Whisper/SenseVoice/Moonshine must never compete for CPU at the same time.
     */
    val inferenceDispatcher = AppDispatchers.nativeInference

    const val MAX_AUDIO_PREFETCH_CHUNKS: Int = 2
    private const val HIGH_END_MIN_CORES: Int = 8
    private const val HIGH_END_PREFETCH_MIN_AVAILABLE_BYTES: Long = 2_500_000_000L

    fun tuning(
        model: WhisperModel,
        profile: ProcessingProfile = ProcessingProfile.BALANCED,
        processors: Int = Runtime.getRuntime().availableProcessors(),
        thermallyConstrained: Boolean = false,
        thermalState: ThermalState = if (thermallyConstrained) ThermalState.THERMAL_SAFE else ThermalState.NORMAL,
        lowMemory: Boolean = false,
        availableMemoryBytes: Long = 0L,
        devicePerformanceProfile: DevicePerformanceProfile? = null
    ): ProcessingTuning {
        val cores = processors.coerceAtLeast(1)
        val reserve = when {
            cores >= 8 -> 3
            cores >= 4 -> 2
            else -> 1
        }
        val sustainable = max(1, cores - reserve)
        val modelCap = when (model.performanceTier) {
            ModelPerformanceTier.LIGHT -> 4
            ModelPerformanceTier.BALANCED -> 3
            ModelPerformanceTier.HEAVY, ModelPerformanceTier.VERY_HEAVY -> 2
        }
        val profileCap = when (profile) {
            ProcessingProfile.FAST -> 6
            ProcessingProfile.BALANCED, ProcessingProfile.PRESERVE_ORIGINAL -> 4
        }
        val effectiveThermalState = if (thermallyConstrained && thermalState == ThermalState.NORMAL) ThermalState.THERMAL_SAFE else thermalState
        val effectiveCap = when {
            lowMemory || effectiveThermalState == ThermalState.THERMAL_SAFE -> 2
            effectiveThermalState == ThermalState.SUSTAINED -> max(1, profileCap - 1)
            else -> profileCap
        }
        val calibratedThreads = devicePerformanceProfile?.let { profileData ->
            when (effectiveThermalState) {
                ThermalState.NORMAL -> profileData.optimalWhisperThreads
                ThermalState.SUSTAINED -> (profileData.optimalWhisperThreads - 1).coerceAtLeast(1)
                ThermalState.THERMAL_SAFE -> profileData.thermalSafeThreadCount
            }
        }
        val threads = if (calibratedThreads != null) {
            calibratedThreads.coerceAtMost(modelCap).coerceAtMost(effectiveCap).coerceAtLeast(1)
        } else {
            sustainable.coerceAtMost(modelCap).coerceAtMost(effectiveCap).coerceAtLeast(1)
        }
        val chunking = when {
            lowMemory -> AudioChunkingPolicy(minimumSeconds = 10, targetSeconds = 15, maximumSeconds = 18)
            effectiveThermalState == ThermalState.THERMAL_SAFE -> AudioChunkingPolicy(minimumSeconds = 12, targetSeconds = 20, maximumSeconds = 22)
            effectiveThermalState == ThermalState.SUSTAINED -> AudioChunkingPolicy(minimumSeconds = 18, targetSeconds = 26, maximumSeconds = 31)
            devicePerformanceProfile != null -> {
                val target = devicePerformanceProfile.preferredChunkDurationSeconds.coerceIn(15, 40)
                AudioChunkingPolicy(
                    minimumSeconds = (target - 8).coerceAtLeast(10),
                    targetSeconds = target,
                    maximumSeconds = (target + 5).coerceAtMost(45)
                )
            }
            profile == ProcessingProfile.FAST && cores >= 8 ->
                AudioChunkingPolicy(minimumSeconds = 30, targetSeconds = 40, maximumSeconds = 45)
            profile == ProcessingProfile.FAST ->
                AudioChunkingPolicy(minimumSeconds = 24, targetSeconds = 35, maximumSeconds = 40)
            else -> AudioChunkingPolicy(minimumSeconds = 20, targetSeconds = 30, maximumSeconds = 35)
        }
        val channelCapacity = when {
            effectiveThermalState == ThermalState.THERMAL_SAFE || lowMemory -> 0
            effectiveThermalState == ThermalState.SUSTAINED -> 1
            devicePerformanceProfile != null -> devicePerformanceProfile.recommendedPrefetch
                .coerceIn(0, MAX_AUDIO_PREFETCH_CHUNKS)
                .let { calibrated ->
                    val profileBounded = if (profile == ProcessingProfile.FAST) calibrated else calibrated.coerceAtMost(1)
                    if (model.performanceTier == ModelPerformanceTier.VERY_HEAVY) profileBounded.coerceAtMost(1) else profileBounded
                }
            profile == ProcessingProfile.FAST &&
                cores >= HIGH_END_MIN_CORES &&
                availableMemoryBytes >= HIGH_END_PREFETCH_MIN_AVAILABLE_BYTES &&
                model.performanceTier != ModelPerformanceTier.VERY_HEAVY -> 2
            else -> 1
        }
        return ProcessingTuning(
            whisperThreads = threads,
            audioChunking = chunking,
            channelCapacity = channelCapacity
        )
    }
}
