package com.transvibe.app.domain.runtime

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PerformanceMetricsCollectorTest {
    @Test
    fun recordsRtfAndNeverStoresText() {
        val collector = PerformanceMetricsCollector(
            model = "whisper-small",
            languageProvider = { "en" },
            deviceClass = "normal_256mb",
            threadsProvider = { 3 },
            thermalProvider = { "0" }
        )
        collector.record("whisper", 60_000, 30_000, 5_000.0)
        val metric = collector.snapshot().single()
        assertEquals(0.5, metric.rtf, 0.000001)
        assertEquals(60_000, metric.audioDurationMs)
        assertEquals(30_000, metric.processingDurationMs)
        assertTrue(metric.model.contains("whisper"))
    }

    @Test
    fun fallbackCountIsCumulativeTechnicalMetadata() {
        val collector = PerformanceMetricsCollector(
            model = "whisper-small",
            languageProvider = { "en" },
            deviceClass = "high_512mb",
            threadsProvider = { 4 },
            thermalProvider = { "1" }
        )
        collector.recordFallback()
        collector.record("sensevoice", 10_000, 5_000)
        collector.recordFallback()
        collector.record("whisper", 10_000, 4_000)
        assertEquals(1, collector.snapshot()[0].fallbackCount)
        assertEquals(2, collector.snapshot()[1].fallbackCount)
    }
}
