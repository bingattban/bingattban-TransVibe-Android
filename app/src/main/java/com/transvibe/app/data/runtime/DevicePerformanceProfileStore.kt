package com.transvibe.app.data.runtime

import android.content.Context
import com.transvibe.app.domain.runtime.DevicePerformanceProfile

/** Small primitive-only cache; intentionally avoids JSON parsing in the processing hot path. */
class DevicePerformanceProfileStore(context: Context) {
    private val prefs = context.getSharedPreferences("transvibe_device_performance", Context.MODE_PRIVATE)

    fun load(compatibilityKey: String): DevicePerformanceProfile? {
        if (prefs.getString(KEY_COMPATIBILITY, null) != compatibilityKey) return null
        return runCatching {
            DevicePerformanceProfile(
                compatibilityKey = compatibilityKey,
                optimalWhisperThreads = prefs.getInt(KEY_THREADS, 0),
                preferredChunkDurationSeconds = prefs.getInt(KEY_CHUNK_SECONDS, 0),
                recommendedPrefetch = prefs.getInt(KEY_PREFETCH, -1),
                deviceMemoryClassMb = prefs.getInt(KEY_MEMORY_CLASS, 0),
                lowRamDevice = prefs.getBoolean(KEY_LOW_RAM, false),
                typicalRtf = java.lang.Double.longBitsToDouble(prefs.getLong(KEY_RTF_BITS, 0L)),
                thermalSafeThreadCount = prefs.getInt(KEY_THERMAL_THREADS, 0),
                calibratedAtEpochMs = prefs.getLong(KEY_CALIBRATED_AT, 0L)
            )
        }.getOrNull()
    }

    fun save(profile: DevicePerformanceProfile) {
        prefs.edit()
            .putString(KEY_COMPATIBILITY, profile.compatibilityKey)
            .putInt(KEY_THREADS, profile.optimalWhisperThreads)
            .putInt(KEY_CHUNK_SECONDS, profile.preferredChunkDurationSeconds)
            .putInt(KEY_PREFETCH, profile.recommendedPrefetch)
            .putInt(KEY_MEMORY_CLASS, profile.deviceMemoryClassMb)
            .putBoolean(KEY_LOW_RAM, profile.lowRamDevice)
            .putLong(KEY_RTF_BITS, java.lang.Double.doubleToRawLongBits(profile.typicalRtf))
            .putInt(KEY_THERMAL_THREADS, profile.thermalSafeThreadCount)
            .putLong(KEY_CALIBRATED_AT, profile.calibratedAtEpochMs)
            .apply()
    }

    private companion object {
        const val KEY_COMPATIBILITY = "compatibility"
        const val KEY_THREADS = "threads"
        const val KEY_CHUNK_SECONDS = "chunk_seconds"
        const val KEY_PREFETCH = "prefetch"
        const val KEY_MEMORY_CLASS = "memory_class_mb"
        const val KEY_LOW_RAM = "low_ram"
        const val KEY_RTF_BITS = "rtf_bits"
        const val KEY_THERMAL_THREADS = "thermal_threads"
        const val KEY_CALIBRATED_AT = "calibrated_at"
    }
}
