package com.transvibe.app.domain.runtime

import com.transvibe.app.domain.model.OfficialWhisperModels
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ProcessingRuntimeTest {
    @Test
    fun balancedNeverConsumesEveryCoreOnTypicalPhone() {
        val tuning = ProcessingRuntime.tuning(OfficialWhisperModels.recommended, ProcessingProfile.BALANCED, processors = 8)
        assertTrue(tuning.whisperThreads in 1..4)
        assertTrue(tuning.whisperThreads < 8)
        assertEquals(30, tuning.audioChunkSeconds)
        assertEquals(1, tuning.channelCapacity)
    }

    @Test
    fun lowCoreDeviceStillGetsOneInferenceThread() {
        val tuning = ProcessingRuntime.tuning(OfficialWhisperModels.recommended, ProcessingProfile.BALANCED, processors = 1)
        assertEquals(1, tuning.whisperThreads)
    }

    @Test
    fun thermalConstraintIsMoreConservativeThanFast() {
        val fast = ProcessingRuntime.tuning(OfficialWhisperModels.recommended, ProcessingProfile.FAST, processors = 8)
        val constrained = ProcessingRuntime.tuning(
            OfficialWhisperModels.recommended,
            ProcessingProfile.FAST,
            processors = 8,
            thermallyConstrained = true
        )
        assertTrue(constrained.whisperThreads <= fast.whisperThreads)
        assertTrue(constrained.audioChunkSeconds < fast.audioChunkSeconds)
        assertEquals(0, constrained.channelCapacity)
    }

    @Test
    fun fastAndBalancedDoNotRequestOriginalLanguageSecondPass() {
        assertFalse(ProcessingProfile.FAST.preserveOriginalTranscript)
        assertFalse(ProcessingProfile.BALANCED.preserveOriginalTranscript)
        assertEquals(1, ProcessingProfile.FAST.maxForeignLanguageWhisperPasses)
        assertEquals(1, ProcessingProfile.BALANCED.maxForeignLanguageWhisperPasses)
    }

    @Test
    fun preserveOriginalMakesAdditionalInferenceCostExplicit() {
        assertTrue(ProcessingProfile.PRESERVE_ORIGINAL.preserveOriginalTranscript)
        assertEquals(2, ProcessingProfile.PRESERVE_ORIGINAL.maxForeignLanguageWhisperPasses)
    }

    @Test
    fun legacyPowerSavePreferenceMigratesToBalanced() {
        assertEquals(ProcessingProfile.BALANCED, ProcessingProfile.fromPersisted("POWER_SAVE"))
    }
    @Test
    fun lowMemoryUsesShorterBoundedChunksAndNoPrefetch() {
        val normal = ProcessingRuntime.tuning(OfficialWhisperModels.recommended, ProcessingProfile.BALANCED, processors = 8)
        val constrained = ProcessingRuntime.tuning(
            OfficialWhisperModels.recommended,
            ProcessingProfile.BALANCED,
            processors = 8,
            lowMemory = true
        )
        assertTrue(constrained.audioChunking.targetSeconds < normal.audioChunking.targetSeconds)
        assertTrue(constrained.audioChunking.minimumSeconds <= constrained.audioChunking.targetSeconds)
        assertTrue(constrained.audioChunking.targetSeconds <= constrained.audioChunking.maximumSeconds)
        assertEquals(0, constrained.channelCapacity)
    }

    @Test
    fun fastHighCoreDeviceAllowsLongerButBoundedSilenceAwareChunks() {
        val tuning = ProcessingRuntime.tuning(OfficialWhisperModels.recommended, ProcessingProfile.FAST, processors = 8)
        assertEquals(30, tuning.audioChunking.minimumSeconds)
        assertEquals(40, tuning.audioChunking.targetSeconds)
        assertEquals(45, tuning.audioChunking.maximumSeconds)
        assertTrue(tuning.audioChunking.overlapMs > 0)
    }

    @Test
    fun highEndFastDeviceMayPrefetchTwoChunksButNeverMore() {
        val tuning = ProcessingRuntime.tuning(
            OfficialWhisperModels.recommended,
            ProcessingProfile.FAST,
            processors = 8,
            availableMemoryBytes = 3_000_000_000L
        )
        assertEquals(2, tuning.channelCapacity)
        assertTrue(tuning.channelCapacity <= ProcessingRuntime.MAX_AUDIO_PREFETCH_CHUNKS)
    }

    @Test
    fun normalMemoryOrBalancedModeKeepsSingleChunkPrefetch() {
        val normalMemory = ProcessingRuntime.tuning(
            OfficialWhisperModels.recommended,
            ProcessingProfile.FAST,
            processors = 8,
            availableMemoryBytes = 1_000_000_000L
        )
        val balanced = ProcessingRuntime.tuning(
            OfficialWhisperModels.recommended,
            ProcessingProfile.BALANCED,
            processors = 8,
            availableMemoryBytes = 4_000_000_000L
        )
        assertEquals(1, normalMemory.channelCapacity)
        assertEquals(1, balanced.channelCapacity)
    }

    @Test
    fun thermalOrLowMemoryAlwaysDisablesPrefetchEvenOnHighEndDevice() {
        val thermal = ProcessingRuntime.tuning(
            OfficialWhisperModels.recommended,
            ProcessingProfile.FAST,
            processors = 12,
            thermallyConstrained = true,
            availableMemoryBytes = 6_000_000_000L
        )
        val lowMemory = ProcessingRuntime.tuning(
            OfficialWhisperModels.recommended,
            ProcessingProfile.FAST,
            processors = 12,
            lowMemory = true,
            availableMemoryBytes = 6_000_000_000L
        )
        assertEquals(0, thermal.channelCapacity)
        assertEquals(0, lowMemory.channelCapacity)
    }

}
