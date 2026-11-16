package com.transvibe.app.data.media

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Tests-first contract for zero-copy SAF input with a safe legacy copy fallback. */
class FfmpegInputPolicyTest {
    @Test fun contentUriUsesValidSafInputWithoutCopy() {
        val argument = FfmpegInputPolicy.directInput("content", null, "saf:123.mp4")
        assertEquals("saf:123.mp4", argument)
        assertFalse(FfmpegInputPolicy.needsContentCopy("content", argument))
    }

    @Test fun missingSafInputFallsBackToStreamingCopy() {
        assertNull(FfmpegInputPolicy.directInput("content", null, null))
        assertTrue(FfmpegInputPolicy.needsContentCopy("content", null))
    }

    @Test fun invalidSafInputCannotBePassedToFfmpeg() {
        listOf("", "saf:", "file:///private/file", "http://example.test",
            "saf:123\nunsafe", "saf:123\runsafe").forEach { candidate ->
            assertNull(FfmpegInputPolicy.directInput("content", null, candidate))
        }
    }

    @Test fun localFilesUseDirectFilePaths() {
        assertEquals("/data/video.mp4", FfmpegInputPolicy.directInput("file", "/data/video.mp4", "saf:unexpected"))
        assertEquals("/data/video.mp4", FfmpegInputPolicy.directInput(null, "/data/video.mp4", null))
        assertFalse(FfmpegInputPolicy.needsContentCopy("file", "/data/video.mp4"))
    }

    @Test fun unsupportedSchemesAreNeverInterpretedAsLocalPaths() {
        assertNull(FfmpegInputPolicy.directInput("https", "/data/video.mp4", "saf:123"))
        assertNull(FfmpegInputPolicy.directInput("content", "/private/path", "invalid"))
        assertNull(FfmpegInputPolicy.directInput("file", "  ", null))
    }

    @Test fun failedSafCanRetryByCopyButNeverLoopsOrRetriesLocalFiles() {
        assertTrue(FfmpegInputPolicy.retryWithCopy("content", true))
        assertFalse(FfmpegInputPolicy.retryWithCopy("content", false))
        assertFalse(FfmpegInputPolicy.retryWithCopy("file", true))
        assertFalse(FfmpegInputPolicy.retryWithCopy("https", true))
    }
}
