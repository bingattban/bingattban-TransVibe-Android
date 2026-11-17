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
        // Consecutive windows overlap heavily (e.g. 160ms wide every 20ms).
        // Reuse the common energy instead of scanning those same PCM frames
        // eight times on every call into the chunk-boundary selector.
        var previousFrom = -1
        var previousTo = -1
        var windowEnergy = 0.0
        fun squaredEnergy(from: Int, to: Int): Double {
            if (to <= from) return 0.0
            val value = rms(from, to)
            return value * value * (to - from)
        }
        var center = searchStart
        while (center <= searchEnd) {
            val from = (center - window / 2).coerceAtLeast(0)
            val to = (center + window / 2).coerceAtMost(sampleCount)
            windowEnergy = if (previousFrom >= 0 && from >= previousFrom &&
                from <= previousTo && to >= previousTo
            ) {
                val removed = squaredEnergy(previousFrom, from)
                val appended = squaredEnergy(previousTo, to)
                (windowEnergy - removed + appended).coerceAtLeast(0.0)
            } else {
                squaredEnergy(from, to)
            }
            energies += center to kotlin.math.sqrt(windowEnergy / (to - from).coerceAtLeast(1))
            previousFrom = from
            previousTo = to
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
