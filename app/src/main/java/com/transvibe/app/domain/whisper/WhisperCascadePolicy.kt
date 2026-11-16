package com.transvibe.app.domain.whisper

import com.transvibe.app.nativebridge.whisper.WhisperNative

/**
 * Confidence thresholds are intentionally nullable until device/media benchmarks establish production values.
 * Structural failures still escalate safely without pretending an unbenchmarked probability is authoritative.
 */
data class WhisperCascadeThresholds(
    val minAverageTokenProbability: Float? = null,
    val minAverageLogProbability: Float? = null,
    val maxNoSpeechProbability: Float? = null
)


object WhisperCascadeConfig {
    @Volatile
    var thresholds: WhisperCascadeThresholds = WhisperCascadeThresholds()
        private set

    fun configure(value: WhisperCascadeThresholds) {
        thresholds = value
    }
}

data class WhisperCascadeAssessment(
    val hasUsableText: Boolean,
    val suspiciousRepetition: Boolean,
    val averageTokenProbability: Float?,
    val averageLogProbability: Float?,
    val noSpeechProbability: Float?
)

object WhisperCascadePolicy {
    fun assess(text: String, evidence: List<WhisperNative.RecognitionSegment>): WhisperCascadeAssessment {
        val probabilities = evidence.mapNotNull { it.averageTokenProbability }
        val logProbabilities = evidence.mapNotNull { it.avgLogProb }
        val noSpeech = evidence.mapNotNull { it.noSpeechProbability }
        return WhisperCascadeAssessment(
            hasUsableText = text.isNotBlank(),
            suspiciousRepetition = suspicious(text),
            averageTokenProbability = probabilities.takeIf { it.isNotEmpty() }?.average()?.toFloat(),
            averageLogProbability = logProbabilities.takeIf { it.isNotEmpty() }?.average()?.toFloat(),
            noSpeechProbability = noSpeech.maxOrNull()
        )
    }

    fun shouldEscalate(assessment: WhisperCascadeAssessment, thresholds: WhisperCascadeThresholds): Boolean {
        if (!assessment.hasUsableText || assessment.suspiciousRepetition) return true
        thresholds.minAverageTokenProbability?.let { if (assessment.averageTokenProbability != null && assessment.averageTokenProbability < it) return true }
        thresholds.minAverageLogProbability?.let { if (assessment.averageLogProbability != null && assessment.averageLogProbability < it) return true }
        thresholds.maxNoSpeechProbability?.let { if (assessment.noSpeechProbability != null && assessment.noSpeechProbability > it) return true }
        return false
    }

    private fun suspicious(text: String): Boolean {
        val normalized = text.lowercase().replace(Regex("[^\\p{L}\\p{N}]+"), " ").trim()
        val words = normalized.split(Regex("\\s+")).filter { it.isNotBlank() }
        if (words.size < 6) return false
        val maxRepeat = words.groupingBy { it }.eachCount().values.maxOrNull() ?: 0
        return maxRepeat.toDouble() / words.size >= 0.55
    }
}
