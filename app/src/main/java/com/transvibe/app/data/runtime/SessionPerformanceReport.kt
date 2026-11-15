package com.transvibe.app.data.runtime

import android.content.Context
import org.json.JSONObject
import java.io.File

/** Optional observability data; processing keeps working even if reporting fails. */
data class SessionPerformanceReport(
    val jobId: String,
    val videoName: String,
    val modelId: String,
    val sourceLanguage: String,
    val targetLanguage: String,
    val segmentCount: Int,
    val elapsedMs: Long,
    val extractionPipelineMs: Long,
    val languageDetectionMs: Long,
    val recognitionMs: Long,
    val translationMs: Long,
    val savingMs: Long,
    val vadMs: Long,
    val gpuBackend: String,
    val srtLocation: String,
    val vttLocation: String,
    val exportFailure: String = ""
) {
    fun toJson(): JSONObject = JSONObject().apply {
        put("jobId", jobId); put("videoName", videoName); put("modelId", modelId)
        put("sourceLanguage", sourceLanguage); put("targetLanguage", targetLanguage)
        put("segmentCount", segmentCount); put("elapsedMs", elapsedMs)
        put("extractionPipelineMs", extractionPipelineMs); put("languageDetectionMs", languageDetectionMs)
        put("recognitionMs", recognitionMs); put("translationMs", translationMs)
        put("savingMs", savingMs); put("vadMs", vadMs); put("gpuBackend", gpuBackend)
        put("srtLocation", srtLocation); put("vttLocation", vttLocation); put("exportFailure", exportFailure)
    }

    companion object {
        fun fromJson(o: JSONObject): SessionPerformanceReport = SessionPerformanceReport(
            jobId = o.optString("jobId"), videoName = o.optString("videoName"),
            modelId = o.optString("modelId"), sourceLanguage = o.optString("sourceLanguage"),
            targetLanguage = o.optString("targetLanguage"), segmentCount = o.optInt("segmentCount"),
            elapsedMs = o.optLong("elapsedMs"), extractionPipelineMs = o.optLong("extractionPipelineMs"),
            languageDetectionMs = o.optLong("languageDetectionMs"), recognitionMs = o.optLong("recognitionMs"),
            translationMs = o.optLong("translationMs"), savingMs = o.optLong("savingMs"),
            vadMs = o.optLong("vadMs"), gpuBackend = o.optString("gpuBackend", "UNVERIFIED"),
            srtLocation = o.optString("srtLocation"), vttLocation = o.optString("vttLocation"),
            exportFailure = o.optString("exportFailure")
        )
    }
}

object SessionPerformanceStore {
    private fun file(context: Context, jobId: String): File =
        File(File(context.filesDir, "sessions"), "${jobId.replace(Regex("[^a-zA-Z0-9-]"), "_")}/performance.json")

    fun save(context: Context, report: SessionPerformanceReport) {
        val destination = file(context, report.jobId)
        check(destination.parentFile?.isDirectory == true || destination.parentFile?.mkdirs() == true)
        val tmp = File(destination.parentFile, "performance.tmp")
        tmp.writeText(report.toJson().toString(), Charsets.UTF_8)
        if (!tmp.renameTo(destination)) {
            tmp.copyTo(destination, overwrite = true)
            tmp.delete()
        }
    }

    fun load(context: Context, jobId: String): SessionPerformanceReport? =
        runCatching { SessionPerformanceReport.fromJson(JSONObject(file(context, jobId).readText(Charsets.UTF_8))) }.getOrNull()
}
