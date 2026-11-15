package com.transvibe.app.nativebridge.whisper

import android.test.AndroidTestCase

@Suppress("DEPRECATION")
class WhisperNativeBridgeInstrumentationTest : AndroidTestCase() {
    fun testUnicodeAndMetricTransferAcrossJni() {
        val cases = listOf(
            "مرحبا بالعالم",
            "Hello world",
            "مرحبا Hello",
            "🎬🔥",
            "𐍈 𝄞 𠀋",
            "العربية English हिन्दी 中文 🎧"
        )
        for (text in cases) {
            val result = WhisperNative.bridgeSelfTestForTests(text)
            assertEquals(text, result.text)
            assertEquals(10L, result.startMs)
            assertEquals(20L, result.endMs)
            assertEquals(0.75f, result.confidence)
            assertNull(result.avgLogProb)
            assertEquals(0.20f, result.noSpeechProbability)
            assertEquals(3, result.tokenCount)
        }
    }

    fun testEmptyInputRejectedBeforeNativeInference() {
        assertNativeCode(NativeErrorCode.INVALID_AUDIO) {
            WhisperNative.transcribeDetailed(1234L, FloatArray(0), "en", false, 1)
        }
    }

    fun testOversizedInputRejectedBeforeNativeInference() {
        assertNativeCode(NativeErrorCode.INVALID_AUDIO) {
            WhisperNative.transcribeDetailed(1234L, FloatArray(WhisperNative.MAX_AUDIO_SAMPLES + 1), "en", false, 1)
        }
    }

    fun testInvalidAudioRangeRejectedBeforeNativeInference() {
        assertNativeCode(NativeErrorCode.INVALID_ARGUMENT) {
            WhisperNative.transcribeDetailed(
                1234L, FloatArray(1_000), 900, 200, "en", false, 1
            )
        }
    }

    fun testInvalidHandleIsStructured() {
        assertNativeCode(NativeErrorCode.INVALID_HANDLE) {
            WhisperNative.releaseContext(Long.MAX_VALUE)
        }
    }

    fun testDoubleReleaseStyleInvalidationRemainsDeterministic() {
        repeat(2) {
            assertNativeCode(NativeErrorCode.INVALID_HANDLE) {
                WhisperNative.releaseContext(42424242L)
            }
        }
    }

    fun testCancelWithInvalidHandleIsStructured() {
        assertNativeCode(NativeErrorCode.INVALID_HANDLE) {
            WhisperNative.cancel(42424242L)
        }
    }

    private fun assertNativeCode(expected: NativeErrorCode, block: () -> Unit) {
        try {
            block()
            fail("Expected NativeBridgeException($expected)")
        } catch (e: NativeBridgeException) {
            assertEquals(expected, e.code)
        }
    }
}
