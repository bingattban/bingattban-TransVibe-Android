package com.transvibe.app.domain.whisper

import com.transvibe.app.domain.model.SubtitleSegment
import com.transvibe.app.nativebridge.whisper.WhisperNative
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class WhisperCascadePolicyTest {
    @Test
    fun blankTextEscalatesWithoutInventedProbabilityThresholds() {
        val assessment = WhisperCascadePolicy.assess("", emptyList())
        assertTrue(WhisperCascadePolicy.shouldEscalate(assessment, WhisperCascadeThresholds()))
    }

    @Test
    fun usableTextIsAcceptedWhenBenchmarkThresholdsAreUnset() {
        val evidence = listOf(
            WhisperNative.RecognitionSegment(
                segment = SubtitleSegment(0, 0, 1000, "hello world"),
                averageTokenProbability = 0.42f,
                avgLogProb = -1.1f,
                noSpeechProbability = 0.08f
            )
        )
        val assessment = WhisperCascadePolicy.assess("hello world", evidence)
        assertFalse(WhisperCascadePolicy.shouldEscalate(assessment, WhisperCascadeThresholds()))
    }

    @Test
    fun configuredBenchmarkThresholdCanTriggerEscalation() {
        val evidence = listOf(
            WhisperNative.RecognitionSegment(
                segment = SubtitleSegment(0, 0, 1000, "hello world"),
                averageTokenProbability = 0.42f
            )
        )
        val assessment = WhisperCascadePolicy.assess("hello world", evidence)
        assertTrue(
            WhisperCascadePolicy.shouldEscalate(
                assessment,
                WhisperCascadeThresholds(minAverageTokenProbability = 0.50f)
            )
        )
    }
}
