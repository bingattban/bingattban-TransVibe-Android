package com.transvibe.app.domain.whisper

import com.transvibe.app.domain.model.SubtitleSegment
import com.transvibe.app.nativebridge.whisper.WhisperNative
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class RecognitionQualityEvaluatorTest {
    private fun evidence(
        tokenProbability: Float? = 0.8f,
        avgLogProb: Float? = null,
        noSpeech: Float? = 0.1f,
        tokenCount: Int = 8
    ) = listOf(
        WhisperNative.RecognitionSegment(
            segment = SubtitleSegment(0, 0, 1_000, "hello world"),
            averageTokenProbability = tokenProbability,
            avgLogProb = avgLogProb,
            noSpeechProbability = noSpeech,
            tokenCount = tokenCount
        )
    )

    @Test fun defaultPolicyDoesNotInventProbabilityThresholds() {
        val assessment = RecognitionQualityEvaluator.evaluate(
            RecognitionQualityInput("hello world", evidence(tokenProbability = 0.12f), 0.2f, 1_000, RecognitionEngineTier.WHISPER_PRIMARY),
            RecognitionQualityPolicy()
        )
        assertEquals(RecognitionQualityAction.ACCEPT, assessment.action)
    }

    @Test fun calibratedTokenProbabilityCanEscalatePrimaryWhisper() {
        val assessment = RecognitionQualityEvaluator.evaluate(
            RecognitionQualityInput("hello world", evidence(tokenProbability = 0.42f), 0.9f, 1_000, RecognitionEngineTier.WHISPER_PRIMARY),
            RecognitionQualityPolicy(minAverageTokenProbability = 0.50f)
        )
        assertEquals(RecognitionQualityAction.ESCALATE_TO_STRONGER_MODEL, assessment.action)
        assertTrue("token_probability" in assessment.reasons)
    }

    @Test fun calibratedNoSpeechPolicyCanIgnoreBlankRecognition() {
        val assessment = RecognitionQualityEvaluator.evaluate(
            RecognitionQualityInput("", evidence(noSpeech = 0.92f, tokenCount = 0), 0.9f, 800, RecognitionEngineTier.WHISPER_PRIMARY),
            RecognitionQualityPolicy(noSpeechIgnoreProbability = 0.90f)
        )
        assertEquals(RecognitionQualityAction.IGNORE_AS_NO_SPEECH, assessment.action)
    }

    @Test fun languageConfidenceIsConfigurableNotHardcoded() {
        val assessment = RecognitionQualityEvaluator.evaluate(
            RecognitionQualityInput("hello", evidence(), 0.55f, 1_000, RecognitionEngineTier.WHISPER_PRIMARY),
            RecognitionQualityPolicy(minLanguageConfidence = 0.60f)
        )
        assertEquals(RecognitionQualityAction.ESCALATE_TO_STRONGER_MODEL, assessment.action)
        assertTrue("language_confidence" in assessment.reasons)
    }

    @Test fun tokenWeightedProbabilityIsUsed() {
        val items = listOf(
            WhisperNative.RecognitionSegment(SubtitleSegment(0, 0, 500, "a"), 0.2f, tokenCount = 1),
            WhisperNative.RecognitionSegment(SubtitleSegment(1, 500, 1_000, "b"), 0.8f, tokenCount = 3)
        )
        val assessment = RecognitionQualityEvaluator.evaluate(
            RecognitionQualityInput("a b", items, null, 1_000, RecognitionEngineTier.WHISPER_PRIMARY),
            RecognitionQualityPolicy()
        )
        assertEquals(0.65f, assessment.averageTokenProbability!!, 0.0001f)
        assertEquals(4, assessment.tokenCount)
    }

    @Test fun calibratedFastAsrFailureEscalatesToWhisper() {
        val assessment = RecognitionQualityEvaluator.evaluate(
            RecognitionQualityInput("hello", evidence(tokenProbability = 0.2f), 0.9f, 900, RecognitionEngineTier.FAST_ASR),
            RecognitionQualityPolicy(minAverageTokenProbability = 0.5f)
        )
        assertEquals(RecognitionQualityAction.ESCALATE_TO_WHISPER, assessment.action)
    }

    @Test fun strongestWhisperReturnsRetryWhenCalibratedPolicyStillFails() {
        val assessment = RecognitionQualityEvaluator.evaluate(
            RecognitionQualityInput("hello", evidence(tokenProbability = 0.2f), 0.9f, 900, RecognitionEngineTier.WHISPER_STRONGER),
            RecognitionQualityPolicy(minAverageTokenProbability = 0.5f)
        )
        assertEquals(RecognitionQualityAction.RETRY, assessment.action)
    }

    @Test fun repetitionScoreCanBeCalibratedLater() {
        val score = RecognitionQualityEvaluator.repetitionScore("go go now go go now")
        val assessment = RecognitionQualityEvaluator.evaluate(
            RecognitionQualityInput("go go now go go now", evidence(), null, 2_000, RecognitionEngineTier.WHISPER_PRIMARY),
            RecognitionQualityPolicy(maxRepetitionScore = (score - 0.01f).coerceAtLeast(0f))
        )
        assertEquals(RecognitionQualityAction.ESCALATE_TO_STRONGER_MODEL, assessment.action)
    }
}
