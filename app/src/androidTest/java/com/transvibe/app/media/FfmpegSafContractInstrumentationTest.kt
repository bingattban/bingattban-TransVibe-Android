package com.transvibe.app.media

import android.content.Context
import android.net.Uri
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Requires an installed test APK and the original FFmpegKit AAR.
 * This verifies the zero-copy API contract, not provider access or real decoding.
 */
@RunWith(AndroidJUnit4::class)
class FfmpegSafContractInstrumentationTest {
    @Test fun verifiedFfmpegKitAarExposesSafReadProtocol() {
        val config = Class.forName("com.arthenica.ffmpegkit.FFmpegKitConfig")
        val method = config.getMethod("getSafParameterForRead", Context::class.java, Uri::class.java)
        assertNotNull(method)
        assertEquals(String::class.java, method.returnType)
    }
}
