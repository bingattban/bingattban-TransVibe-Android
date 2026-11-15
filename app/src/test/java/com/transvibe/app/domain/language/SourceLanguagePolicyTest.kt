package com.transvibe.app.domain.language

import org.junit.Assert.*
import org.junit.Test

class SourceLanguagePolicyTest {
    @Test fun legacyAutoMigratesToAutoVideo() {
        assertEquals(SourceLanguageMode.AUTO_VIDEO, SourceLanguagePolicy.parse("auto").mode)
    }

    @Test fun manualLanguageIsRetained() {
        val p = SourceLanguagePolicy.parse("manual:ar")
        assertEquals(SourceLanguageMode.MANUAL, p.mode)
        assertEquals("ar", p.manualLanguage)
    }

    @Test fun adaptiveRequiresRepeatedLowConfidence() {
        val monitor = AdaptiveLanguageMonitor(lowQualityStreakRequired = 3, redetectionCooldownRegions = 2)
        val weak = RecognitionQuality(isLowConfidence = true)
        assertFalse(monitor.record(weak))
        assertFalse(monitor.record(weak))
        assertTrue(monitor.record(weak))
        assertFalse(monitor.record(weak))
    }

    @Test fun goodRecognitionResetsWeakEvidence() {
        val monitor = AdaptiveLanguageMonitor(lowQualityStreakRequired = 3, redetectionCooldownRegions = 2)
        val weak = RecognitionQuality(isLowConfidence = true)
        val good = RecognitionQuality(isLowConfidence = false)
        assertFalse(monitor.record(weak))
        assertFalse(monitor.record(weak))
        assertFalse(monitor.record(good))
        assertFalse(monitor.record(weak))
    }
    @Test fun arabicEnglishAndThirdLanguageManualPoliciesAreStable() {
        assertEquals("ar", SourceLanguagePolicy.parse("manual:ar").manualLanguage)
        assertEquals("en", SourceLanguagePolicy.parse("manual:en").manualLanguage)
        assertEquals("es", SourceLanguagePolicy.parse("manual:es").manualLanguage)
    }

    @Test fun mixedLanguageAdaptiveModeAllowsBoundedRedetection() {
        val policy = SourceLanguagePolicy.parse("auto_adaptive")
        assertTrue(policy.requiresInitialDetection)
        assertTrue(policy.allowsRedetection)
        val monitor = AdaptiveLanguageMonitor(lowQualityStreakRequired = 2, redetectionCooldownRegions = 2)
        val weak = RecognitionQuality(isLowConfidence = true)
        assertFalse(monitor.record(weak))
        assertTrue(monitor.record(weak))
        monitor.resetAfterLanguageChange()
        assertFalse(monitor.record(weak))
    }

    @Test fun sixManualLanguageChoicesSkipDetection() {
        for (language in listOf("ar", "en", "fr", "ru", "de", "es")) {
            val policy = SourceLanguagePolicy.parse("manual:$language")
            assertEquals(SourceLanguageMode.MANUAL, policy.mode)
            assertEquals(language, policy.manualLanguage)
            assertFalse(policy.requiresInitialDetection)
        }
    }

}
