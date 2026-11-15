package com.transvibe.app.media.decoder

import kotlin.math.abs

/**
 * Allocation-light boundary policy used by [AudioDecoder]. It works on local RMS windows supplied
 * by the caller so the decoder can inspect its accumulator without copying PCM first.
 */
class SilenceAwareChunkBoundarySelector {
    fun select(
        sampleCount: Int,
        minimumSamples: Int,
        targetSamples: Int,
        maximumSamples: Int,
        boundaryWindowSamples: Int,
        sampleRateHz: Int,
        force: Boolean,
        rms: (start: Int, end: Int) -> Double
    ): Int? {
        if (sampleCount < minimumSamples) return null
        val availableMax = minOf(sampleCount, maximumSamples)
        val searchStart = minimumSamples.coerceAtMost(availableMax)
        val searchEnd = availableMax
        if (searchEnd <= searchStart) return if (force) availableMax else null

        val frame = (sampleRateHz * 20 / 1_000).coerceAtLeast(1)
        val window = boundaryWindowSamples.coerceAtLeast(frame)
        val energies = ArrayList<Pair<Int, Double>>()
        var center = searchStart
        while (center <= searchEnd) {
            val from = (center - window / 2).coerceAtLeast(0)
            val to = (center + window / 2).coerceAtMost(sampleCount)
            energies += center to rms(from, to)
            center += frame
        }
        if (energies.isEmpty()) return if (force) searchEnd else null

        val sorted = energies.map { it.second }.sorted()
        val floor = sorted[((sorted.size - 1) * 0.20).toInt().coerceIn(0, sorted.lastIndex)]
        val median = sorted[sorted.size / 2]
        val silenceThreshold = minOf(0.0065, maxOf(0.0007, floor * 1.65, median * 0.28))
        val silent = energies.filter { it.second <= silenceThreshold }
        if (silent.isNotEmpty()) {
            return silent.minByOrNull { (index, _) ->
                abs(index - targetSamples) + if (index > targetSamples) frame / 3 else 0
            }?.first
        }
        if (!force) return null
        return energies.minByOrNull { it.second }?.first ?: searchEnd
    }
}
