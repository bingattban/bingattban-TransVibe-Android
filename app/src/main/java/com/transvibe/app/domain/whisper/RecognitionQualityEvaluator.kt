package com.transvibe.app.domain.whisper

import com.transvibe.app.nativebridge.whisper.WhisperNative
import kotlin.math.max

/**
 * Recognition quality decisions are deliberately policy-driven. Numeric thresholds default to null
 * until calibrated against representative TransVibe media/device benchmarks.
 */
enum class RecognitionQualityAction {
    ACCEPT,
    RETRY,
    ESCALATE_TO_WHISPER,
    ESCALATE_TO_STRONGER_MODEL,
    IGNORE_AS_NO_SPEECH
}

enum class RecognitionEngineTier { FAST_ASR, WHISPER_PRIMARY, WHISPER_STRONGER }

data class RecognitionQualityPolicy(
    val minAverageTokenProbability: Float? = null,
    val minAverageLogProbability: Float? = null,
    val maxNoSpeechProbability: Float? = null,
    val noSpeechIgnoreProbability: Float? = null,
    val maxRepetitionScore: Float? = null,
    val minLanguageConfidence: Float? = null,
    val minTokenCount: Int? = null
) {
    init {
        minAverageTokenProbability?.let { require(it in 0f..1f) }
        maxNoSpeechProbability?.let { require(it in 0f..1f) }
        noSpeechIgnoreProbability?.let { require(it in 0f..1f) }
        maxRepetitionScore?.let { require(it in 0f..1f) }
        minLanguageConfidence?.let { require(it in 0f..1f) }
        minTokenCount?.let { require(it >= 0) }
    }
}

object RecognitionQualityConfig {
    @Volatile
    var policy: RecognitionQualityPolicy = RecognitionQualityPolicy()
        private set

    /** Intended for benchmark/calibration tooling and controlled product configuration. */
    fun configure(value: RecognitionQualityPolicy) {
        policy = value
    }
}

data class RecognitionQualityInput(
    val transcript: String,
    val evidence: List<WhisperNative.RecognitionSegment>,
    val languageConfidence: Float?,
    val speechDurationMs: Long,
    val engineTier: RecognitionEngineTier
)

data class RecognitionQualityAssessment(
    val action: RecognitionQualityAction,
    val hasUsableText: Boolean,
    val averageTokenProbability: Float?,
    val averageLogProbability: Float?,
    val noSpeechProbability: Float?,
    val repetitionScore: Float,
    val languageConfidence: Float?,
    val tokenCount: Int,
    val transcriptLength: Int,
    val speechDurationMs: Long,
    val reasons: Set<String>
) {
    val isUncertain: Boolean
        get() = action != RecognitionQualityAction.ACCEPT &&
            action != RecognitionQualityAction.IGNORE_AS_NO_SPEECH
}

object RecognitionQualityEvaluator {
    fun evaluate(
        input: RecognitionQualityInput,
        policy: RecognitionQualityPolicy = RecognitionQualityConfig.policy
    ): RecognitionQualityAssessment {
        val text = input.transcript.replace(Regex("\\s+"), " ").trim()
        val tokenCount = input.evidence.sumOf { max(0, it.tokenCount) }
        val averageTokenProbability = weightedAverage(
            input.evidence.mapNotNull { segment ->
                segment.averageTokenProbability?.let { probability -> probability to max(1, segment.tokenCount) }
            }
        )
        val averageLogProbability = weightedAverage(
            input.evidence.mapNotNull { segment ->
                segment.avgLogProb?.let { probability -> probability to max(1, segment.tokenCount) }
            }
        )
        val noSpeechProbability = input.evidence.mapNotNull { it.noSpeechProbability }.maxOrNull()
        val repetitionScore = repetitionScore(text)
        val reasons = linkedSetOf<String>()

        if (text.isBlank()) reasons += "empty_transcript"
        if (pathologicalRepetition(text)) reasons += "pathological_repetition"
        policy.minAverageTokenProbability?.let { threshold ->
            if (averageTokenProbability != null && averageTokenProbability < threshold) reasons += "token_probability"
        }
        policy.minAverageLogProbability?.let { threshold ->
            if (averageLogProbability != null && averageLogProbability < threshold) reasons += "average_log_probability"
        }
        policy.maxNoSpeechProbability?.let { threshold ->
            if (noSpeechProbability != null && noSpeechProbability > threshold) reasons += "no_speech_probability"
        }
        policy.maxRepetitionScore?.let { threshold ->
            if (repetitionScore > threshold) reasons += "repetition_score"
        }
        policy.minLanguageConfidence?.let { threshold ->
            if (input.languageConfidence != null && input.languageConfidence < threshold) reasons += "language_confidence"
        }
        policy.minTokenCount?.let { threshold ->
            if (tokenCount in 1 until threshold) reasons += "token_count"
        }

        val likelyNoSpeech = text.isBlank() && policy.noSpeechIgnoreProbability?.let { threshold ->
            noSpeechProbability != null && noSpeechProbability >= threshold
        } == true

        val action = when {
            likelyNoSpeech -> RecognitionQualityAction.IGNORE_AS_NO_SPEECH
            reasons.isEmpty() -> RecognitionQualityAction.ACCEPT
            input.engineTier == RecognitionEngineTier.FAST_ASR -> RecognitionQualityAction.ESCALATE_TO_WHISPER
            input.engineTier == RecognitionEngineTier.WHISPER_PRIMARY -> RecognitionQualityAction.ESCALATE_TO_STRONGER_MODEL
            else -> RecognitionQualityAction.RETRY
        }

        return RecognitionQualityAssessment(
            action = action,
            hasUsableText = text.isNotBlank(),
            averageTokenProbability = averageTokenProbability,
            averageLogProbability = averageLogProbability,
            noSpeechProbability = noSpeechProbability,
            repetitionScore = repetitionScore,
            languageConfidence = input.languageConfidence,
            tokenCount = tokenCount,
            transcriptLength = text.length,
            speechDurationMs = input.speechDurationMs.coerceAtLeast(0L),
            reasons = reasons
        )
    }

    private fun weightedAverage(values: List<Pair<Float, Int>>): Float? {
        if (values.isEmpty()) return null
        var weighted = 0.0
        var weight = 0L
        values.forEach { (value, count) ->
            if (value.isFinite()) {
                weighted += value.toDouble() * count
                weight += count.toLong()
            }
        }
        return if (weight == 0L) null else (weighted / weight).toFloat()
    }

    /** 0..1 concentration score; exposed for calibration rather than coupled to a fixed threshold. */
    internal fun repetitionScore(text: String): Float {
        val words = normalizedWords(text)
        if (words.size < 2) return 0f
        val unigramMax = (words.groupingBy { it }.eachCount().values.maxOrNull() ?: 1).toFloat() / words.size
        val pairScore = if (words.size >= 4) {
            val pairs = words.windowed(2).map { it.joinToString("\u0000") }
            (pairs.groupingBy { it }.eachCount().values.maxOrNull() ?: 1).toFloat() / pairs.size
        } else 0f
        return max(unigramMax, pairScore).coerceIn(0f, 1f)
    }

    /** Threshold-free guard for the degenerate case where the entire transcript is one repeated token. */
    private fun pathologicalRepetition(text: String): Boolean {
        val words = normalizedWords(text)
        return words.size > 1 && words.distinct().size == 1
    }

    private fun normalizedWords(text: String): List<String> = text
        .lowercase()
        .replace(Regex("[^\\p{L}\\p{N}]+"), " ")
        .trim()
        .split(Regex("\\s+"))
        .filter { it.isNotBlank() }
}
