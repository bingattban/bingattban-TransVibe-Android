package com.transvibe.app.domain.asr

import com.transvibe.app.domain.runtime.ProcessingProfile
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AdaptiveAsrRouterTest {
    private val cleanSignal = AudioSignalProfile(4_000, 0.08f, 0f, 0.08f, 0.7f)
    private val healthy = AsrResourceSnapshot(false, false, 2L * 1024 * 1024 * 1024)

    @Test fun autoFastEnglishPrefersMoonshine() {
        val route = AdaptiveAsrRouter().choose(
            FastAsrRouteRequest("en", preferredModelId = "auto", processingProfile = ProcessingProfile.FAST,
                signal = cleanSignal, resources = healthy, senseVoiceInstalled = true, moonshineInstalled = true)
        )
        assertEquals(FastAsrEngine.MOONSHINE, route)
    }

    @Test fun degradedAudioBypassesFastAsr() {
        val degraded = cleanSignal.copy(rms = 0.001f)
        val route = AdaptiveAsrRouter().choose(
            FastAsrRouteRequest("en", preferredModelId = "auto", processingProfile = ProcessingProfile.BALANCED,
                signal = degraded, resources = healthy, senseVoiceInstalled = true, moonshineInstalled = true)
        )
        assertNull(route)
    }

    @Test fun mismatchedSenseVoiceLanguageIsRejected() {
        val assessment = AdaptiveAsrRouter().assessFastResult(
            FastAsrEngine.SENSEVOICE, "hello world", "en", "ja", cleanSignal
        )
        assertFalse(assessment.accepted)
        assertTrue(assessment.languageMismatch)
    }

    @Test fun repeatedHallucinationIsRejected() {
        val assessment = AdaptiveAsrRouter().assessFastResult(
            FastAsrEngine.MOONSHINE, "hello hello hello hello hello hello hello", "en", null, cleanSignal
        )
        assertFalse(assessment.accepted)
        assertTrue(assessment.suspiciousRepetition)
    }
}
