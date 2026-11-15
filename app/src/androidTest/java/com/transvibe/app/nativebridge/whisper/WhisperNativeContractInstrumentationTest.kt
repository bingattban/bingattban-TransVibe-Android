package com.transvibe.app.nativebridge.whisper

import android.test.AndroidTestCase
import java.io.File
import java.lang.reflect.InvocationTargetException
import androidx.test.platform.app.InstrumentationRegistry

/**
 * Real ARM64 JNI registration smoke tests. Run on an Android device with the actual .so.
 * Deliberately detects the legacy missing nativeBackendForContext entry point.
 * Does not require, create, overwrite, or delete any downloaded model weights.
 */
@Suppress("DEPRECATION")
class WhisperNativeContractInstrumentationTest : AndroidTestCase() {
    private val invalidHandle = -1L

    fun testNativeLibraryLoadsAndBridgeSelfTestWorks() {
        assertTrue("ARM64 Whisper JNI library must load", WhisperNative.isLibraryAvailable())
        assertEquals("مرحبا 🎬", WhisperNative.bridgeSelfTestForTests("مرحبا 🎬").text)
    }

    fun testCreateContextBindingReturnsStructuredErrorWithoutModelWeights() {
        // The file is not created. App's models directory is never modified.
        val missingPath = File(context.cacheDir, "transvibe-jni-contract-no-such-model-${System.nanoTime()}.bin")
        assertEquals(false, missingPath.exists())
        assertNativeCode(NativeErrorCode.MODEL_NOT_FOUND) {
            WhisperNative.createContext(missingPath.absolutePath)
        }
    }

    fun testBackendInspectionBindingExistsAndRejectsInvalidHandle() {
        // runtimeBackend currently hides an UnsatisfiedLinkError as UNVERIFIED.
        // Inspect the native method directly so the test catches the missing binding.
        assertRawNativeError("nativeBackendForContext", NativeErrorCode.INVALID_HANDLE)
    }

    fun testModelMetadataBindingsRejectInvalidHandle() {
        assertRawNativeError("nativeIsMultilingual", NativeErrorCode.INVALID_HANDLE)
        assertRawNativeError("nativeModelTypeReadable", NativeErrorCode.INVALID_HANDLE)
    }

    fun testLanguageDetectionBindingRejectsInvalidHandle() {
        assertNativeCode(NativeErrorCode.INVALID_HANDLE) {
            WhisperNative.detectLanguageDetailed(invalidHandle, FloatArray(1_600), threads = 1)
        }
    }

    fun testTranscriptionBindingRejectsInvalidHandle() {
        assertNativeCode(NativeErrorCode.INVALID_HANDLE) {
            WhisperNative.transcribeDetailed(
                handle = invalidHandle,
                samples = FloatArray(1600),
                language = "en",
                translate = false,
                threads = 1
            )
        }
    }

    fun testCancelAndReleaseBindingsRejectInvalidHandle() {
        assertNativeCode(NativeErrorCode.INVALID_HANDLE) { WhisperNative.cancel(invalidHandle) }
        assertNativeCode(NativeErrorCode.INVALID_HANDLE) { WhisperNative.releaseContext(invalidHandle) }
    }

    /**
     * Optional full context/real-backend smoke test. Provide the instrumentation argument
     * transvibeTestWhisperModelPath pointing at an ALREADY INSTALLED compatible Tiny model.
     * Do not create or delete that file; a missing argument skips this additional test only.
     */
    fun testInstalledModelBackendIfConfigured() {
        val path = InstrumentationRegistry.getArguments()
            .getString("transvibeTestWhisperModelPath")
            ?.takeIf { it.isNotBlank() } ?: return
        assertTrue("Configured test model must be readable", File(path).isFile)
        val handle = WhisperNative.createContext(path)
        try {
            val backend = WhisperNative.runtimeBackend(handle)
            assertTrue("Native backend must identify CPU or GPU, not $backend",
                backend.startsWith("CPU|") || backend.startsWith("GPU|"))
        } finally {
            WhisperNative.releaseContext(handle)
        }
    }

    private fun assertRawNativeError(name: String, expected: NativeErrorCode) {
        assertTrue("Library must be loadable before querying JNI registration", WhisperNative.isLibraryAvailable())
        val method = WhisperNative::class.java.getDeclaredMethod(name, java.lang.Long.TYPE)
        method.isAccessible = true
        try {
            method.invoke(WhisperNative, invalidHandle)
            fail("Expected a structured native error for $name")
        } catch (e: InvocationTargetException) {
            val original = e.targetException
            if (original is UnsatisfiedLinkError) {
                fail("Missing JNI binding: $name (${original.message})")
            }
            assertTrue("Expected IllegalStateException for $name; got $original", original is IllegalStateException)
            assertTrue(
                "Expected TVE|${expected.name}| for $name; got ${original.message}",
                original.message.orEmpty().startsWith("TVE|${expected.name}|")
            )
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
