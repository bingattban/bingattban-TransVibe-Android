package com.transvibe.app.performance

import android.app.ActivityManager
import android.content.Context
import android.os.BatteryManager
import android.os.Build
import android.os.Debug
import android.os.PowerManager
import androidx.test.platform.app.InstrumentationRegistry
import com.transvibe.app.domain.runtime.ProcessingProfile
import org.json.JSONObject
import java.io.File

/**
 * Device benchmark harness. It intentionally records measurements only; it does not contain model
 * recommendation thresholds. Production recommendations must be derived from collected benchmark data.
 */
data class DeviceBenchmarkRecord(
    val deviceLabel: String,
    val modelId: String,
    val profile: ProcessingProfile,
    val videoDurationMs: Long,
    val coldStartupMs: Long,
    val modelLoadMs: Long,
    val firstSubtitleLatencyMs: Long,
    val averageChunkInferenceMs: Double,
    val averageTranslationMs: Double,
    val totalProcessingMs: Long,
    val peakRssKb: Long,
    val batteryDeltaPercent: Int?,
    val thermalStatusStart: Int?,
    val thermalStatusEnd: Int?,
    val crashedOrOom: Boolean
) {
    val realTimeFactor: Double
        get() = if (videoDurationMs > 0L) totalProcessingMs.toDouble() / videoDurationMs.toDouble() else Double.NaN
}

object DeviceBenchmarkProbe {
    fun deviceLabel(context: Context): String {
        val am = context.getSystemService(ActivityManager::class.java)
        val lowRam = am?.isLowRamDevice == true
        val memoryClass = am?.memoryClass ?: -1
        return listOf(
            Build.MANUFACTURER,
            Build.MODEL,
            "sdk${Build.VERSION.SDK_INT}",
            "mem${memoryClass}MB",
            if (lowRam) "lowram" else "normalram",
            "cpu${Runtime.getRuntime().availableProcessors()}",
            Build.SUPPORTED_ABIS.joinToString("+")
        ).joinToString("_").replace(Regex("[^A-Za-z0-9._+-]"), "_")
    }

    fun rssKb(): Long = Debug.getPss().toLong()

    fun batteryPercent(context: Context): Int? = context.getSystemService(BatteryManager::class.java)
        ?.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY)
        ?.takeIf { it in 0..100 }

    fun thermalStatus(context: Context): Int? = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
        context.getSystemService(PowerManager::class.java)?.currentThermalStatus
    } else null

    fun persist(record: DeviceBenchmarkRecord): File {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val dir = File(context.getExternalFilesDir(null) ?: context.filesDir, "benchmarks").apply { mkdirs() }
        val file = File(dir, "${record.deviceLabel}-${record.modelId}-${record.profile.name}-${System.currentTimeMillis()}.json")
        val json = JSONObject().apply {
            put("device", record.deviceLabel)
            put("modelId", record.modelId)
            put("profile", record.profile.name)
            put("videoDurationMs", record.videoDurationMs)
            put("coldStartupMs", record.coldStartupMs)
            put("modelLoadMs", record.modelLoadMs)
            put("firstSubtitleLatencyMs", record.firstSubtitleLatencyMs)
            put("averageChunkInferenceMs", record.averageChunkInferenceMs)
            put("averageTranslationMs", record.averageTranslationMs)
            put("totalProcessingMs", record.totalProcessingMs)
            put("rtf", record.realTimeFactor)
            put("peakRssKb", record.peakRssKb)
            put("batteryDeltaPercent", record.batteryDeltaPercent ?: JSONObject.NULL)
            put("thermalStatusStart", record.thermalStatusStart ?: JSONObject.NULL)
            put("thermalStatusEnd", record.thermalStatusEnd ?: JSONObject.NULL)
            put("crashedOrOom", record.crashedOrOom)
        }
        file.writeText(json.toString(2))
        return file
    }
}
