package com.transvibe.app.nativebridge.whisper

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class WhisperNativeBridgeInstrumentationTest {
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    private val context get() = instrumentation.targetContext
    @Test
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

    @Test
    fun testEmptyInputRejectedBeforeNativeInference() {
        assertNativeCode(NativeErrorCode.INVALID_AUDIO) {
            WhisperNative.transcribeDetailed(1234L, FloatArray(0), "en", false, 1)
        }
    }

    @Test
    fun testOversizedInputRejectedBeforeNativeInference() {
        assertNativeCode(NativeErrorCode.INVALID_AUDIO) {
            WhisperNative.transcribeDetailed(1234L, FloatArray(WhisperNative.MAX_AUDIO_SAMPLES + 1), "en", false, 1)
        }
    }

    @Test
    fun testInvalidAudioRangeRejectedBeforeNativeInference() {
        assertNativeCode(NativeErrorCode.INVALID_ARGUMENT) {
            WhisperNative.transcribeDetailed(
                1234L, FloatArray(1_000), 900, 200, "en", false, 1
            )
        }
    }

    @Test
    fun testInvalidHandleIsStructured() {
        assertNativeCode(NativeErrorCode.INVALID_HANDLE) {
            WhisperNative.releaseContext(Long.MAX_VALUE)
        }
    }

    @Test
    fun testDoubleReleaseStyleInvalidationRemainsDeterministic() {
        repeat(2) {
            assertNativeCode(NativeErrorCode.INVALID_HANDLE) {
                WhisperNative.releaseContext(42424242L)
            }
        }
    }

    @Test
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
