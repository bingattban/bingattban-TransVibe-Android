package com.transvibe.app.domain.runtime

import com.transvibe.app.domain.model.OfficialWhisperModels
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class DevicePerformanceCalibrationTest {
    @Test
    fun calibrationUsesNormalInferenceCandidatesWithoutExtraCandidateExpansion() {
        val session = DevicePerformanceCalibrationSession(
            compatibilityKey = "test",
            processors = 8,
            memoryClassMb = 512,
            lowRamDevice = false
        )
        assertEquals(2, session.nextThreadCount(3))
        assertNull(session.record(2, elapsedMs = 600, audioDurationMs = 2_000))
        assertEquals(3, session.nextThreadCount(3))
        assertNull(session.record(3, elapsedMs = 500, audioDurationMs = 2_000))
        assertEquals(4, session.nextThreadCount(3))
        val profile = session.record(4, elapsedMs = 490, audioDurationMs = 2_000)
        requireNotNull(profile)
        // 3 threads is within 5% of the fastest 4-thread observation, so prefer fewer threads.
        assertEquals(3, profile.optimalWhisperThreads)
        assertTrue(profile.typicalRtf > 0.0)
    }

    @Test
    fun heavyModelsAreNotCalibratedOnUserVideos() {
        val heavy = OfficialWhisperModels.whisperModels.first { it.id == "medium" }
        val turbo = OfficialWhisperModels.whisperModels.first { it.id == "large-v3-turbo" }
        assertTrue(!DevicePerformanceCalibrationPolicy.shouldCalibrate(heavy, 2, false, false))
        assertTrue(!DevicePerformanceCalibrationPolicy.shouldCalibrate(turbo, 2, false, false))
    }

    @Test
    fun modelThreadCapPreventsUnsafeCandidateTesting() {
        val session = DevicePerformanceCalibrationSession(
            compatibilityKey = "limited", processors = 8, memoryClassMb = 512,
            lowRamDevice = false, maxThreads = 2
        )
        assertEquals(2, session.nextThreadCount(2))
        val profile = session.record(2, elapsedMs = 50_000, audioDurationMs = 2_000)
        requireNotNull(profile)
        assertEquals(2, profile.optimalWhisperThreads)
        assertEquals(2, session.nextThreadCount(2))
    }

    @Test
    fun shortRegionsDoNotPolluteCalibration() {
        val session = DevicePerformanceCalibrationSession("test", 8, 512, false)
        assertNull(session.record(2, elapsedMs = 100, audioDurationMs = 500))
        assertEquals(0, session.observationCount())
    }

    @Test
    fun calibratedRuntimeUsesMeasuredThreadsButThermalSafetyStillWins() {
        val model = OfficialWhisperModels.recommended
        val profile = DevicePerformanceProfile(
            compatibilityKey = "test",
            optimalWhisperThreads = 4,
            preferredChunkDurationSeconds = 35,
            recommendedPrefetch = 2,
            deviceMemoryClassMb = 512,
            lowRamDevice = false,
            typicalRtf = 0.35,
            thermalSafeThreadCount = 2,
            calibratedAtEpochMs = 1L
        )
        val normal = ProcessingRuntime.tuning(
            model = model,
            profile = ProcessingProfile.FAST,
            processors = 8,
            availableMemoryBytes = 4_000_000_000L,
            devicePerformanceProfile = profile
        )
        val thermal = ProcessingRuntime.tuning(
            model = model,
            profile = ProcessingProfile.FAST,
            processors = 8,
            thermallyConstrained = true,
            availableMemoryBytes = 4_000_000_000L,
            devicePerformanceProfile = profile
        )
        assertEquals(4, normal.whisperThreads)
        assertEquals(2, normal.channelCapacity)
        assertEquals(35, normal.audioChunkSeconds)
        assertEquals(2, thermal.whisperThreads)
        assertEquals(0, thermal.channelCapacity)
        assertTrue(thermal.audioChunkSeconds < normal.audioChunkSeconds)
    }

    @Test
    fun balancedModeNeverUsesTwoChunkPrefetchFromCalibration() {
        val profile = DevicePerformanceProfile("test", 3, 30, 2, 512, false, 0.4, 2, 1L)
        val tuning = ProcessingRuntime.tuning(
            OfficialWhisperModels.recommended,
            ProcessingProfile.BALANCED,
            processors = 8,
            devicePerformanceProfile = profile
        )
        assertEquals(1, tuning.channelCapacity)
    }

    @Test
    fun modelSpecificCalibrationCannotLeakBetweenDifferentWhisperModelIdentities() {
        val model = OfficialWhisperModels.recommended
        val variant = model.copy(id = "tiny-user-variant")
        val replacedWeights = model.copy(sha256 = "f".repeat(64))
        val current = DevicePerformanceCompatibility.key("1.0.2", model)
        assertNotEquals(current, DevicePerformanceCompatibility.key("1.0.2", variant))
        assertNotEquals(current, DevicePerformanceCompatibility.key("1.0.2", replacedWeights))
        assertEquals(current, DevicePerformanceCompatibility.key("1.0.9", model))
    }

    @Test
    fun compatibilityKeyChangesOnMajorOrArchitectureChange() {
        val model = OfficialWhisperModels.recommended
        val v1 = DevicePerformanceCompatibility.key("1.9.7", model)
        val v1Patch = DevicePerformanceCompatibility.key("1.10.0", model)
        val v2 = DevicePerformanceCompatibility.key("2.0.0", model)
        assertEquals(v1, v1Patch)
        assertNotEquals(v1, v2)
    }
}
