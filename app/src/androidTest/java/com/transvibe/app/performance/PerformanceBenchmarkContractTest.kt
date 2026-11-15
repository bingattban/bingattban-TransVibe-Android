package com.transvibe.app.performance

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.transvibe.app.domain.runtime.ProcessingProfile
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class PerformanceBenchmarkContractTest {
    @Test
    fun rtfUsesTotalProcessingTimeDividedByVideoDuration() {
        val record = DeviceBenchmarkRecord(
            deviceLabel = "fixture",
            modelId = "tiny",
            profile = ProcessingProfile.FAST,
            videoDurationMs = 120_000,
            coldStartupMs = 200,
            modelLoadMs = 500,
            firstSubtitleLatencyMs = 1_000,
            averageChunkInferenceMs = 250.0,
            averageTranslationMs = 30.0,
            totalProcessingMs = 60_000,
            peakRssKb = 500_000,
            batteryDeltaPercent = 2,
            thermalStatusStart = 0,
            thermalStatusEnd = 1,
            crashedOrOom = false
        )
        assertEquals(0.5, record.realTimeFactor, 0.000001)
        assertTrue(record.peakRssKb > 0)
    }
}
