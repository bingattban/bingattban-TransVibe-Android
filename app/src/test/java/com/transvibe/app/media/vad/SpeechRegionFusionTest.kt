package com.transvibe.app.media.vad

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SpeechRegionFusionTest {
    private val rate = 16000
    @Test fun speechPaddingIsClampedToAudioBounds() {
        val result = SpeechRegionFusion.fuse(
            neural = listOf(SpeechRegion(1200, 9000)), lightweight = emptyList(),
            totalSamples = 10000, sampleRateHz = rate
        )
        assertEquals(listOf(SpeechRegion(0, 10000)), result)
    }
    @Test fun closeRegionsAreMergedWithoutLosingOriginalOffset() {
        val a = SpeechRegion(5000, 12000)
        val b = SpeechRegion(15500, 24000)
        val result = SpeechRegionFusion.fuse(listOf(a,b), emptyList(), 40000, rate, 0,0,260)
        assertEquals(listOf(SpeechRegion(5000, 24000)),result)
    }
    @Test fun distantMusicLikeFalsePositivesFromLightweightAreNotRevived() {
        val result = SpeechRegionFusion.fuse(
            listOf(SpeechRegion(1000, 3000)), listOf(SpeechRegion(20000, 30000)),
            40000, rate, 0, 0, 0
        )
        assertEquals(listOf(SpeechRegion(1000, 3000)), result)
    }
    @Test fun quietSpeechFallsBackToLightweightWhenNeuralReturnsNothing() {
        val result = SpeechRegionFusion.fuse(emptyList(), listOf(SpeechRegion(14000, 16000)), 32000, rate, 0, 0, 0)
        assertEquals(listOf(SpeechRegion(14000, 16000)),result)
    }
    @Test fun policyCanDisableQuietSpeechRescueForStrictMusicFiltering() {
        val result = SpeechRegionFusion.fuse(emptyList(), listOf(SpeechRegion(14000, 16000)), 32000, rate, rescueQuietSpeech=false)
        assertTrue(result.isEmpty())
    }
    @Test fun silenceProducesNoRegions() {
        assertTrue(SpeechRegionFusion.fuse(emptyList(),emptyList(),32000,rate).isEmpty())
    }
}
