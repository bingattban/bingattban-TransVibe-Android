package com.transvibe.app.media.decoder

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin

/**
 * Streaming windowed-sinc resampler with bounded state. Precalculating 4,096 filter
 * phases removes per-output-sample sin/cos while keeping the existing 24-tap Hann
 * low-pass and timestamps. Recalculate only when the input sample rate changes.
 * Coefficients are immutable for each active source rate; no global mutable cache.
 */
internal class StreamingSincResampler(
    private val targetRate: Int,
    private val radius: Int = 12
) {
    private var sourceRate = 0
    private var step = 1.0
    private var position = 0.0
    private var buffer = FloatArray(4096)
    private var size = 0
    private val taps = radius * 2
    private var coefficients = FloatArray(0)

    init {
        require(targetRate > 0 && radius >= 2)
    }

    fun process(input: FloatArray, rate: Int): FloatArray {
        if (input.isEmpty()) return FloatArray(0)
        require(rate > 0)
        if (sourceRate != rate) reset(rate)
        if (rate == targetRate) return input
        append(input)
        return produce(flush = false)
    }

    fun finish(): FloatArray {
        if (sourceRate <= 0 || sourceRate == targetRate || size == 0) {
            size = 0
            position = 0.0
            return FloatArray(0)
        }
        append(FloatArray(radius + 2))
        val out = produce(flush = true)
        size = 0
        position = 0.0
        return out
    }

    private fun reset(rate: Int) {
        sourceRate = rate
        step = rate.toDouble() / targetRate.toDouble()
        size = 0
        ensure(radius + 1)
        repeat(radius) { buffer[size++] = 0f }
        position = radius.toDouble()
        if (rate != targetRate) rebuildKernel(rate)
    }

    private fun rebuildKernel(rate: Int) {
        val cutoff = 0.47 * min(1.0, targetRate.toDouble() / rate.toDouble())
        coefficients = FloatArray(PHASE_COUNT * taps)
        for (phase in 0 until PHASE_COUNT) {
            val frac = phase.toDouble() / PHASE_COUNT
            val base = phase * taps
            for (index in 0 until taps) {
                val tap = index - radius + 1
                val x = tap.toDouble() - frac
                val normalized = x / radius.toDouble()
                if (abs(normalized) >= 1.0) continue
                val window = 0.5 * (1.0 + cos(PI * normalized))
                val arg = 2.0 * cutoff * x
                val sinc = if (abs(arg) < 1e-10) 1.0 else sin(PI * arg) / (PI * arg)
                coefficients[base + index] = (2.0 * cutoff * sinc * window).toFloat()
            }
        }
    }

    private fun produce(flush: Boolean): FloatArray {
        if (size == 0) return FloatArray(0)
        val estimated = max(16, ((size - position - radius).coerceAtLeast(0.0) / step + 4).toInt())
        var out = FloatArray(estimated)
        var count = 0
        while (position + radius < size || (flush && position < size - radius)) {
            val center = position.toInt() // always nonnegative
            val frac = position - center
            val phase = (frac * PHASE_COUNT).toInt().coerceIn(0, PHASE_COUNT - 1)
            val base = phase * taps
            var sum = 0.0
            var weightSum = 0.0
            for (index in 0 until taps) {
                val sourceIndex = center + index - radius + 1
                if (sourceIndex !in 0 until size) continue
                val weight = coefficients[base + index].toDouble()
                sum += buffer[sourceIndex] * weight
                weightSum += weight
            }
            if (count == out.size) out = out.copyOf(out.size * 2)
            out[count++] = if (abs(weightSum) > 1e-12) (sum / weightSum).coerceIn(-1.0, 1.0).toFloat() else 0f
            position += step
        }
        val drop = (position.toInt() - radius).coerceAtLeast(0)
        if (drop > 0) {
            buffer.copyInto(buffer, 0, drop, size)
            size -= drop
            position -= drop
        }
        return out.copyOf(count)
    }

    private fun append(values: FloatArray) {
        ensure(size + values.size)
        values.copyInto(buffer, size)
        size += values.size
    }

    private fun ensure(required: Int) {
        if (required > buffer.size) buffer = buffer.copyOf(max(required, buffer.size * 2))
    }

    private companion object { const val PHASE_COUNT = 4096 }
}
