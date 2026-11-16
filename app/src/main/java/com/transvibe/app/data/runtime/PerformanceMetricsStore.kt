package com.transvibe.app.data.runtime

import android.content.Context
import com.transvibe.app.domain.runtime.PerformanceMetric
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.util.Locale

/** Local, content-free performance history. It stores technical metrics only; no subtitle text. */
class PerformanceMetricsStore(context: Context) {
    private val file = File(context.filesDir, "transvibe_performance_metrics.csv")
    private val lock = Any()

    suspend fun append(metrics: List<PerformanceMetric>) {
        if (metrics.isEmpty()) return
        withContext(Dispatchers.IO) {
            synchronized(lock) {
            val existing = if (file.isFile) file.readLines() else emptyList()
            val header = "timestamp,engine,model,language,deviceClass,threads,audioMs,processingMs,rtf,fallbackCount,avgSegmentMs,thermal,peakMemoryBytes"
            val lines = ArrayList<String>(existing.size + metrics.size + 1)
            lines += if (existing.firstOrNull() == header) existing else listOf(header)
            if (existing.firstOrNull() == header) lines.addAll(existing.drop(1)) else lines.addAll(existing)
            metrics.forEach { lines += it.toCsvLine() }
            val body = lines.drop(1)
            val retained = listOf(header) + if (body.size > MAX_LINES) body.takeLast(MAX_LINES) else body
            file.parentFile?.mkdirs()
            file.writeText(retained.joinToString("\n") + "\n")
            }
        }
    }

    private fun PerformanceMetric.toCsvLine(): String = listOf(
        timestampMs,
        engine,
        model,
        language,
        deviceClass,
        threads,
        audioDurationMs,
        processingDurationMs,
        String.format(Locale.US, "%.6f", rtf),
        fallbackCount,
        String.format(Locale.US, "%.2f", averageSegmentDurationMs),
        thermalLevel,
        peakMemoryBytes
    ).joinToString(",") { escape(it.toString()) }

    private fun escape(value: String): String = if (value.any { it == ',' || it == '"' || it == '\n' }) "\"${value.replace("\"", "\"\"")}\"" else value

    private companion object { const val MAX_LINES = 500 }
}
