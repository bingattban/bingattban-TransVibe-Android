package com.transvibe.app.data.logging

import android.util.Log
import com.transvibe.app.BuildConfig
import com.transvibe.app.domain.error.ProcessingError

/** Structured diagnostics. Never include subtitle text or unredacted source URIs in fields. */
object AppLogger {
    data class Fields(
        val jobId: String? = null,
        val stage: String? = null,
        val modelId: String? = null,
        val sourceLanguage: String? = null,
        val targetLanguage: String? = null,
        val chunkIndex: Int? = null,
        val elapsedMs: Long? = null,
        val errorCode: String? = null,
        val transferState: String? = null
    ) {
        fun encoded(): String = buildList {
            jobId?.let { add("job_id=${AppLogger.safe(it)}") }
            stage?.let { add("stage=${AppLogger.safe(it)}") }
            modelId?.let { add("model_id=${AppLogger.safe(it)}") }
            sourceLanguage?.let { add("source_language=${AppLogger.safe(it)}") }
            targetLanguage?.let { add("target_language=${AppLogger.safe(it)}") }
            chunkIndex?.let { add("chunk_index=$it") }
            elapsedMs?.let { add("elapsed_ms=${it.coerceAtLeast(0)}") }
            errorCode?.let { add("error_code=${AppLogger.safe(it)}") }
            transferState?.let { add("transfer_state=${AppLogger.safe(it)}") }
        }.joinToString(" ")
    }

    fun event(tag: String, name: String, fields: Fields = Fields()) {
        if (!BuildConfig.DEBUG) return
        Log.d(tag, structured(name, fields))
    }

    fun d(tag: String, message: String) {
        if (BuildConfig.DEBUG) Log.d(tag, message)
    }

    fun e(tag: String, message: String, error: Throwable? = null) {
        if (!BuildConfig.DEBUG) return
        Log.e(tag, message, error)
    }

    fun error(tag: String, error: ProcessingError, event: String, fields: Fields = Fields()) {
        if (!BuildConfig.DEBUG) return
        val merged = fields.copy(errorCode = error.code.stableCode)
        // Diagnostics must never alter error mapping, including plain JVM tests.
        try {
            Log.e(tag, structured(event, merged), error.technicalCause)
        } catch (_: RuntimeException) {
            // Best-effort logging: preserve the original domain error.
        }
    }

    private fun structured(name: String, fields: Fields): String {
        val encoded = fields.encoded()
        return if (encoded.isBlank()) "event=${safe(name)}" else "event=${safe(name)} $encoded"
    }

    private fun safe(value: String): String = value
        .replace(Regex("[\\r\\n\\t ]+"), "_")
        .replace(Regex("[^A-Za-z0-9_.:@/+-]"), "_")
        .take(160)
}
