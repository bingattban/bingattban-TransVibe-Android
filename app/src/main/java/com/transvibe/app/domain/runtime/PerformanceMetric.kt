package com.transvibe.app.domain.runtime

data class PerformanceMetric(
    val timestampMs: Long,
    val engine: String,
    val model: String,
    val language: String,
    val deviceClass: String,
    val threads: Int,
    val audioDurationMs: Long,
    val processingDurationMs: Long,
    val rtf: Double,
    val fallbackCount: Int,
    val averageSegmentDurationMs: Double,
    val thermalLevel: String,
    val peakMemoryBytes: Long
) {
    init {
        require(audioDurationMs > 0)
        require(processingDurationMs >= 0)
        require(rtf.isFinite() && rtf >= 0.0)
        require(threads >= 0)
        require(fallbackCount >= 0)
        require(averageSegmentDurationMs >= 0.0)
        require(peakMemoryBytes >= 0)
    }
}
