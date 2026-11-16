package com.transvibe.app.performance

import android.os.SystemClock
import android.util.Log
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.transvibe.app.MainActivity
import com.transvibe.app.nativebridge.whisper.NativeBridgeException
import com.transvibe.app.nativebridge.whisper.NativeErrorCode
import com.transvibe.app.nativebridge.whisper.WhisperNative
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Free emulator smoke tests: run actual installed application code, no paid cloud
 * devices, model weights, microphone recording or user videos.
 *
 * Timing is diagnostic ONLY. An x86_64 emulator translating an ARM64 APK is NOT
 * comparable to an ARM64 phone for Whisper ASR performance.
 */
@RunWith(AndroidJUnit4::class)
class AndroidEmulatorSmokeTest {
    @Test
    fun mainActivityLaunchesWithoutAUserModel() {
        val started = SystemClock.elapsedRealtime()
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            scenario.onActivity { activity ->
                assertTrue("Activity should not be finishing", !activity.isFinishing)
                assertTrue("Activity should not be destroyed", !activity.isDestroyed)
            }
        }
        Log.i("TransVibeEmulatorSmoke", "launchMs=${SystemClock.elapsedRealtime() - started} (diagnostic only)")
    }

    @Test
    fun prebuiltNativeBridgeLoadsAndRejectsMissingModelSafely() {
        assertTrue("The installed native JNI binary must load", WhisperNative.isLibraryAvailable())
        try {
            WhisperNative.createContext("/data/local/tmp/transvibe-no-model-smoke-test.bin")
            fail("Nonexistent model must not create a native inference context")
        } catch (error: NativeBridgeException) {
            assertEquals("JNI must return typed missing-model error", NativeErrorCode.MODEL_NOT_FOUND, error.code)
        }
    }
}
