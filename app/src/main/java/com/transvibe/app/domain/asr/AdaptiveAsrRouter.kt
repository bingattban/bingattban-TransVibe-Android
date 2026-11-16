package com.transvibe.app.domain.asr

import com.transvibe.app.domain.runtime.ProcessingProfile
import com.transvibe.app.domain.runtime.ThermalState
import kotlin.math.abs
import kotlin.math.sqrt

enum class FastAsrEngine { SENSEVOICE, MOONSHINE }

data class AudioSignalProfile(
    val durationMs: Long,
    val rms: Float,
    val clippingFraction: Float,
    val zeroCrossingRate: Float,
    val speechDensity: Float
) {
    val severelyDegraded: Boolean
        get() = rms < 0.0035f || clippingFraction > 0.18f || zeroCrossingRate > 0.42f

    companion object {
        fun from(samples: FloatArray, sampleRate: Int, speechDensity: Float): AudioSignalProfile =
            fromRange(samples, 0, samples.size, sampleRate, speechDensity)

        fun fromRange(
            samples: FloatArray,
            start: Int,
            endExclusive: Int,
            sampleRate: Int,
            speechDensity: Float
        ): AudioSignalProfile {
            val safeStart = start.coerceIn(0, samples.size)
            val safeEnd = endExclusive.coerceIn(safeStart, samples.size)
            val count = safeEnd - safeStart
            if (count <= 0 || sampleRate <= 0) {
                return AudioSignalProfile(0L, 0f, 0f, 0f, speechDensity.coerceIn(0f, 1f))
            }
            var sumSquares = 0.0
            var clipped = 0
            var crossings = 0
            var previous = samples[safeStart]
            for (index in safeStart until safeEnd) {
                val value = samples[index].coerceIn(-1f, 1f)
                sumSquares += value.toDouble() * value.toDouble()
                if (abs(value) >= 0.985f) clipped++
                if (index > safeStart && (value >= 0f) != (previous >= 0f)) crossings++
                previous = value
            }
            return AudioSignalProfile(
                durationMs = count * 1_000L / sampleRate,
                rms = sqrt(sumSquares / count).toFloat(),
                clippingFraction = clipped.toFloat() / count,
                zeroCrossingRate = crossings.toFloat() / count.coerceAtLeast(1),
                speechDensity = speechDensity.coerceIn(0f, 1f)
            )
        }
    }
}

data class AsrResourceSnapshot(
    val thermallyConstrained: Boolean,
    val lowMemory: Boolean,
    val availableMemoryBytes: Long,
    val thermalState: ThermalState = if (thermallyConstrained) ThermalState.THERMAL_SAFE else ThermalState.NORMAL
)

data class FastAsrRouteRequest(
    val language: String,
    val languageConfidence: Float? = null,
    val preferredModelId: String,
    val processingProfile: ProcessingProfile,
    val signal: AudioSignalProfile,
    val resources: AsrResourceSnapshot,
    val senseVoiceInstalled: Boolean,
    val moonshineInstalled: Boolean
)

data class FastAsrQualityAssessment(
    val accepted: Boolean,
    val reason: String,
    val suspiciousRepetition: Boolean = false,
    val languageMismatch: Boolean = false
)

/**
 * Stateful but bounded policy for the optional fast-ASR front-end. It never disables Whisper:
 * any rejected/failed fast recognition falls through to Whisper and Whisper's own cascade.
 */
class AdaptiveAsrRouter(
    private val failureStreakToBackoff: Int = 2,
    private val backoffRegions: Int = 3,
    private val minLanguageConfidenceForFastAsr: Float? = null
) {
    private data class EngineState(var failureStreak: Int = 0, var backoffRemaining: Int = 0)
    private val states = FastAsrEngine.entries.associateWith { EngineState() }.toMutableMap()

    fun choose(request: FastAsrRouteRequest): FastAsrEngine? {
        val language = request.language.lowercase()
        val explicitSenseVoice = request.preferredModelId == OfficialFastAsrModels.senseVoiceSmall.id
        val explicitMoonshine = request.preferredModelId == OfficialFastAsrModels.moonshineTinyEnglish.id || request.preferredModelId == "moonshine-base-ar"
        val auto = request.preferredModelId == "auto"

        if (!auto && !explicitSenseVoice && !explicitMoonshine) return null
        if (request.signal.severelyDegraded) return null
        minLanguageConfidenceForFastAsr?.let { threshold ->
            if (request.languageConfidence != null && request.languageConfidence < threshold) return null
        }

        fun available(engine: FastAsrEngine): Boolean {
            val installed = when (engine) {
                FastAsrEngine.SENSEVOICE -> request.senseVoiceInstalled
                FastAsrEngine.MOONSHINE -> request.moonshineInstalled
            }
            if (!installed) return false
            // Moonshine models are language-specific. Never route an English
            // region to Base Arabic, or an Arabic region to Tiny English.
            if (engine == FastAsrEngine.MOONSHINE) {
                val supportedLanguage = if (request.preferredModelId == "moonshine-base-ar") "ar" else "en"
                if (language != supportedLanguage) return false
            }
            val state = states.getValue(engine)
            if (state.backoffRemaining > 0) {
                state.backoffRemaining--
                return false
            }
            val estimatedRam = when (engine) {
                FastAsrEngine.SENSEVOICE -> OfficialFastAsrModels.senseVoiceSmall.estimatedRamBytes
                FastAsrEngine.MOONSHINE -> if (request.preferredModelId == "moonshine-base-ar") 650L * 1024L * 1024L else OfficialFastAsrModels.moonshineTinyEnglish.estimatedRamBytes
            }
            if (request.resources.lowMemory) return false
            val safetyBudget = estimatedRam + MEMORY_HEADROOM_BYTES
            return request.resources.availableMemoryBytes <= 0L || request.resources.availableMemoryBytes >= safetyBudget
        }

        if (explicitSenseVoice) {
            return FastAsrEngine.SENSEVOICE.takeIf {
                language in OfficialFastAsrModels.senseVoiceSmall.supportedLanguages && available(it)
            }
        }
        if (explicitMoonshine) {
            return FastAsrEngine.MOONSHINE.takeIf { language == (if (request.preferredModelId == "moonshine-base-ar") "ar" else "en") && available(it) }
        }

        // AUTO: English prefers the smaller Moonshine runtime when speed/thermal pressure matters.
        // Balanced/PRESERVE may prefer SenseVoice when memory is healthy. Both always retain Whisper fallback.
        if (language == "en") {
            val preferMoonshine = request.processingProfile == ProcessingProfile.FAST || request.resources.thermallyConstrained
            val order = if (preferMoonshine) {
                listOf(FastAsrEngine.MOONSHINE, FastAsrEngine.SENSEVOICE)
            } else {
                listOf(FastAsrEngine.SENSEVOICE, FastAsrEngine.MOONSHINE)
            }
            return order.firstOrNull(::available)
        }

        if (language in OfficialFastAsrModels.senseVoiceSmall.supportedLanguages && available(FastAsrEngine.SENSEVOICE)) {
            return FastAsrEngine.SENSEVOICE
        }
        return null
    }

    fun assessFastResult(
        engine: FastAsrEngine,
        text: String,
        expectedLanguage: String,
        reportedLanguage: String?,
        signal: AudioSignalProfile
    ): FastAsrQualityAssessment {
        val normalized = text.replace(Regex("\\s+"), " ").trim()
        if (normalized.isBlank()) return reject(engine, "empty")
        if (containsInvalidControls(normalized)) return reject(engine, "invalid_controls")
        val suspicious = suspiciousRepetition(normalized)
        if (suspicious) return reject(engine, "repetition", suspicious = true)
        if (implausiblyDense(normalized, signal.durationMs)) return reject(engine, "text_density")

        val mismatch = reportedLanguage
            ?.let(::normalizeLanguageTag)
            ?.takeIf { it.isNotBlank() && it != "auto" && it != "unknown" }
            ?.let { !languagesCompatible(expectedLanguage.lowercase(), it) }
            ?: false
        if (mismatch) return reject(engine, "language_mismatch", languageMismatch = true)

        record(engine, accepted = true)
        return FastAsrQualityAssessment(true, "accepted")
    }

    fun recordFailure(engine: FastAsrEngine) = record(engine, accepted = false)

    private fun reject(
        engine: FastAsrEngine,
        reason: String,
        suspicious: Boolean = false,
        languageMismatch: Boolean = false
    ): FastAsrQualityAssessment {
        record(engine, accepted = false)
        return FastAsrQualityAssessment(false, reason, suspicious, languageMismatch)
    }

    private fun record(engine: FastAsrEngine, accepted: Boolean) {
        val state = states.getValue(engine)
        if (accepted) {
            state.failureStreak = 0
            return
        }
        state.failureStreak++
        if (state.failureStreak >= failureStreakToBackoff) {
            state.failureStreak = 0
            state.backoffRemaining = backoffRegions
        }
    }

    private fun suspiciousRepetition(text: String): Boolean {
        val words = text.lowercase().replace(Regex("[^\\p{L}\\p{N}]+"), " ").trim()
            .split(Regex("\\s+")).filter { it.isNotBlank() }
        if (words.size < 6) return false
        val maxRepeat = words.groupingBy { it }.eachCount().values.maxOrNull() ?: 0
        if (maxRepeat.toDouble() / words.size >= 0.55) return true
        if (words.size >= 8) {
            val pairs = words.windowed(2).map { it.joinToString(" ") }
            val maxPairRepeat = pairs.groupingBy { it }.eachCount().values.maxOrNull() ?: 0
            if (maxPairRepeat >= 3) return true
        }
        return false
    }

    private fun implausiblyDense(text: String, durationMs: Long): Boolean {
        if (durationMs < 1_500L) return false
        val visibleChars = text.count { !it.isWhitespace() }
        val charsPerSecond = visibleChars * 1_000.0 / durationMs.coerceAtLeast(1L)
        return charsPerSecond > 32.0
    }

    private fun containsInvalidControls(text: String): Boolean = text.any {
        Character.isISOControl(it) && it != '\n' && it != '\t'
    }

    private fun normalizeLanguageTag(value: String): String = value
        .trim()
        .lowercase()
        .removePrefix("<|")
        .removeSuffix("|>")
        .substringBefore('-')
        .substringBefore('_')
        .trim()

    private fun languagesCompatible(expected: String, reported: String): Boolean {
        val normalizedExpected = normalizeLanguageTag(expected)
        val normalizedReported = normalizeLanguageTag(reported)
        if (normalizedExpected == normalizedReported) return true
        return (normalizedExpected in setOf("zh", "yue") && normalizedReported in setOf("zh", "yue", "cmn"))
    }

    private companion object {
        const val MEMORY_HEADROOM_BYTES = 160L * 1024L * 1024L
    }
}
