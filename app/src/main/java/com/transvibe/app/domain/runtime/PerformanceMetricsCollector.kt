package com.transvibe.app.domain.runtime

/** Per-job collector. It stores technical timing only and never receives transcript text. */
class PerformanceMetricsCollector(
    private val model: String,
    private val languageProvider: () -> String,
    private val deviceClass: String,
    private val threadsProvider: () -> Int,
    private val thermalProvider: () -> String
) {
    private val metrics = ArrayList<PerformanceMetric>(32)
    private var fallbackCount = 0
    private var peakMemoryBytes = 0L

    fun recordFallback() { fallbackCount++ }

    fun record(
        engine: String,
        audioDurationMs: Long,
        processingDurationMs: Long,
        averageSegmentDurationMs: Double = 0.0,
        modelOverride: String? = null,
        languageOverride: String? = null,
        threadsOverride: Int? = null
    ) {
        val audioMs = audioDurationMs.coerceAtLeast(1L)
        val processingMs = processingDurationMs.coerceAtLeast(0L)
        val usedMemory = (Runtime.getRuntime().totalMemory() - Runtime.getRuntime().freeMemory()).coerceAtLeast(0L)
        peakMemoryBytes = maxOf(peakMemoryBytes, usedMemory)
        metrics += PerformanceMetric(
            timestampMs = System.currentTimeMillis(),
            engine = engine,
            model = modelOverride ?: model,
            language = languageOverride ?: languageProvider(),
            deviceClass = deviceClass,
            threads = threadsOverride ?: threadsProvider(),
            audioDurationMs = audioMs,
            processingDurationMs = processingMs,
            rtf = processingMs.toDouble() / audioMs.toDouble(),
            fallbackCount = fallbackCount,
            averageSegmentDurationMs = averageSegmentDurationMs.coerceAtLeast(0.0),
            thermalLevel = thermalProvider(),
            peakMemoryBytes = peakMemoryBytes
        )
    }

    fun snapshot(): List<PerformanceMetric> = metrics.toList()
}
