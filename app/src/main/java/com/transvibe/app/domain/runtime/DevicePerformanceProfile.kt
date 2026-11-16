package com.transvibe.app.domain.runtime

import com.transvibe.app.domain.model.ModelPerformanceTier
import com.transvibe.app.domain.model.WhisperModel

/**
 * Locally measured, device-specific Whisper performance hints.
 *
 * This profile is advisory only. ProcessingRuntime still applies thermal, memory and model caps.
 */
data class DevicePerformanceProfile(
    val compatibilityKey: String,
    val optimalWhisperThreads: Int,
    val preferredChunkDurationSeconds: Int,
    val recommendedPrefetch: Int,
    val deviceMemoryClassMb: Int,
    val lowRamDevice: Boolean,
    val typicalRtf: Double,
    val thermalSafeThreadCount: Int,
    val calibratedAtEpochMs: Long
) {
    init {
        require(optimalWhisperThreads in 1..8)
        require(preferredChunkDurationSeconds in 10..45)
        require(recommendedPrefetch in 0..ProcessingRuntime.MAX_AUDIO_PREFETCH_CHUNKS)
        require(deviceMemoryClassMb > 0)
        require(typicalRtf.isFinite() && typicalRtf > 0.0)
        require(thermalSafeThreadCount in 1..optimalWhisperThreads)
    }
}

object DevicePerformanceCompatibility {
    /** Bump only when native inference/runtime behavior changes enough to invalidate measurements. */
    const val AI_RUNTIME_REVISION: Int = 2

    fun key(appVersionName: String, model: WhisperModel): String {
        val appMajor = appVersionName.substringBefore('.').filter(Char::isDigit).ifBlank { "0" }
        val architecture = buildString {
            append(model.type.name)
            append(':').append(model.variant)
            append(':').append(model.performanceTier.name)
            append(':').append(if (model.englishOnly) "en" else "multi")
            if (model.performanceTier == ModelPerformanceTier.VERY_HEAVY) {
                // Custom/large variants can differ materially even when their broad tier matches.
                append(':').append(model.fileSizeBytes / (16L * 1024L * 1024L))
            }
        }
        return "app=$appMajor|runtime=$AI_RUNTIME_REVISION|arch=$architecture"
    }
}

data class InferenceCalibrationObservation(
    val threads: Int,
    val realTimeFactor: Double
) {
    init {
        require(threads in 1..8)
        require(realTimeFactor.isFinite() && realTimeFactor > 0.0)
    }
}

/**
 * Zero-extra-inference calibration: candidate thread counts are sampled on normal Whisper calls.
 * No audio is recognized twice merely for benchmarking.
 */
class DevicePerformanceCalibrationSession(
    private val compatibilityKey: String,
    processors: Int,
    private val memoryClassMb: Int,
    private val lowRamDevice: Boolean,
    private val maxThreads: Int = processors,
    private val maxPrefetch: Int = ProcessingRuntime.MAX_AUDIO_PREFETCH_CHUNKS
) {
    private val candidates: List<Int> = when {
        lowRamDevice && processors >= 2 -> listOf(minOf(2, maxThreads.coerceAtLeast(1)))
        processors >= 4 -> listOf(2, 3, 4).filter { it <= minOf(processors, maxThreads.coerceAtLeast(1)) }.ifEmpty { listOf(1) }
        processors >= 2 -> listOf(minOf(2, maxThreads.coerceAtLeast(1)))
        else -> listOf(1)
    }
    private val observations = LinkedHashMap<Int, Double>()

    fun nextThreadCount(fallback: Int): Int = candidates.firstOrNull { it !in observations } ?: fallback

    fun record(threads: Int, elapsedMs: Long, audioDurationMs: Long): DevicePerformanceProfile? {
        if (threads !in candidates || elapsedMs <= 0L || audioDurationMs < MIN_OBSERVATION_AUDIO_MS) return null
        val rtf = elapsedMs.toDouble() / audioDurationMs.toDouble()
        if (!rtf.isFinite() || rtf <= 0.0 || rtf > MAX_REASONABLE_RTF) return null
        observations.putIfAbsent(threads, rtf)
        if (observations.keys.containsAll(candidates).not()) return null

        val bestRtf = observations.values.minOrNull() ?: return null
        // Prefer the smaller thread count when it is effectively tied with the fastest result.
        val selected = observations.entries
            .filter { (_, value) -> value <= bestRtf * NEAR_BEST_FACTOR }
            .minByOrNull { it.key }
            ?: observations.minBy { it.value }
        val typicalRtf = selected.value
        val preferredChunk = when {
            lowRamDevice -> 15
            typicalRtf <= 0.50 -> 35
            typicalRtf <= 1.00 -> 30
            else -> 20
        }
        val recommendedPrefetch = when {
            lowRamDevice -> 0
            memoryClassMb >= 384 && typicalRtf <= 0.75 -> maxPrefetch
            else -> 1
        }.coerceIn(0, maxPrefetch)
        val thermalSafe = minOf(2, selected.key).coerceAtLeast(1)
        return DevicePerformanceProfile(
            compatibilityKey = compatibilityKey,
            optimalWhisperThreads = selected.key,
            preferredChunkDurationSeconds = preferredChunk,
            recommendedPrefetch = recommendedPrefetch,
            deviceMemoryClassMb = memoryClassMb,
            lowRamDevice = lowRamDevice,
            typicalRtf = typicalRtf,
            thermalSafeThreadCount = thermalSafe,
            calibratedAtEpochMs = System.currentTimeMillis()
        )
    }

    fun observationCount(): Int = observations.size

    companion object {
        private const val MIN_OBSERVATION_AUDIO_MS = 1_000L
        private const val MAX_REASONABLE_RTF = 100.0
        private const val NEAR_BEST_FACTOR = 1.05
    }
}

/** Heavy CPU Whisper models are not benchmark-calibrated during a user's video:
 * a normal region can take many minutes, and testing extra thread counts can make it slower.
 */
object DevicePerformanceCalibrationPolicy {
    fun shouldCalibrate(model: WhisperModel, baselineThreads: Int, thermallyConstrained: Boolean, lowMemory: Boolean): Boolean =
        model.performanceTier in setOf(ModelPerformanceTier.LIGHT, ModelPerformanceTier.BALANCED) &&
            baselineThreads >= 2 && !thermallyConstrained && !lowMemory
}
