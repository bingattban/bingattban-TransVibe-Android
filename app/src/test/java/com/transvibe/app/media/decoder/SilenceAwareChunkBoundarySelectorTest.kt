package com.transvibe.app.media.decoder

import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.PI
import kotlin.math.sin
import kotlin.math.sqrt

class SilenceAwareChunkBoundarySelectorTest {
    private val rate = 1_000 // Small synthetic rate keeps tests cheap; selector is rate-agnostic.
    private val selector = SilenceAwareChunkBoundarySelector()

    @Test
    fun waitsBeforeMinimumBoundary() {
        val samples = tone(seconds = 9, amplitude = 0.08)
        assertNull(select(samples, minSec = 10, targetSec = 15, maxSec = 18, force = false))
    }

    @Test
    fun choosesNearbySilenceInsteadOfFixedTarget() {
        val samples = tone(seconds = 32, amplitude = 0.08)
        // Stable 300 ms pause around 27 s, before a 30 s target.
        for (i in 26_850 until 27_150) samples[i] = 0f
        val boundary = select(samples, minSec = 20, targetSec = 30, maxSec = 35, force = false)
        requireNotNull(boundary)
        assertTrue(boundary in 26_700..27_300)
        assertTrue(boundary != 30_000)
    }

    @Test
    fun continuousSpeechWaitsUntilForcedMaximumThenUsesEnergyValley() {
        val samples = tone(seconds = 35, amplitude = 0.08)
        val beforeMax = select(samples.copyOf(30_000), minSec = 20, targetSec = 30, maxSec = 35, force = false)
        assertNull(beforeMax)

        // A quieter syllabic valley near 33 s is preferable to an arbitrary 35 s cut.
        for (i in 32_850 until 33_150) samples[i] *= 0.12f
        val forced = select(samples, minSec = 20, targetSec = 30, maxSec = 35, force = true)
        requireNotNull(forced)
        assertTrue(forced in 32_700..33_300)
    }

    @Test
    fun energySearchScansOnlyNewPcmSamplesInsteadOfRepeatingEntireOverlappingWindows() {
        val samples = tone(seconds = 35, amplitude = 0.08)
        var sampleReads = 0
        val boundary = selector.select(
            sampleCount = samples.size,
            minimumSamples = 20 * rate,
            targetSamples = 30 * rate,
            maximumSamples = 35 * rate,
            boundaryWindowSamples = 160,
            sampleRateHz = rate,
            force = true,
            rms = { from, to ->
                sampleReads += to - from
                var energy = 0.0
                for (i in from until to) energy += samples[i].toDouble() * samples[i]
                sqrt(energy / (to - from).coerceAtLeast(1))
            }
        )
        requireNotNull(boundary)
        assertTrue(boundary in 20_000..35_000)
        // The old algorithm re-read 160 samples for every 20-sample window,
        // roughly 120k sample reads. Incremental energy reuses overlapping data.
        assertTrue("redundant RMS reads: $sampleReads", sampleReads < 45_000)
    }

    private fun select(
        samples: FloatArray,
        minSec: Int,
        targetSec: Int,
        maxSec: Int,
        force: Boolean
    ): Int? = selector.select(
        sampleCount = samples.size,
        minimumSamples = minSec * rate,
        targetSamples = targetSec * rate,
        maximumSamples = maxSec * rate,
        boundaryWindowSamples = 160,
        sampleRateHz = rate,
        force = force,
        rms = { start, end ->
            if (end <= start) 0.0 else {
                var sum = 0.0
                for (i in start until end) sum += samples[i] * samples[i]
                sqrt(sum / (end - start))
            }
        }
    )

    private fun tone(seconds: Int, amplitude: Double): FloatArray = FloatArray(seconds * rate) { i ->
        (amplitude * sin(2.0 * PI * 73.0 * i / rate)).toFloat()
    }
}
