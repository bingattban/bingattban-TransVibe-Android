package com.transvibe.app.media.vad

import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.sqrt

/** A speech interval inside one PCM chunk. End index is exclusive. */
data class SpeechRegion(
    val startSample: Int,
    val endSample: Int
) {
    init {
        require(startSample >= 0)
        require(endSample > startSample)
    }
}

/**
 * UI-independent local VAD contract.
 *
 * Implementations inspect bounded PCM chunks and return only the intervals worth sending to
 * speech recognition. Implementations must cooperate with coroutine cancellation.
 */
interface VoiceActivityDetector {
    suspend fun detectSpeechRegions(samples: FloatArray, sampleRateHz: Int): List<SpeechRegion>
}

/**
 * Lightweight two-stage feature VAD for the 16 kHz mono PCM produced by AudioDecoder.
 *
 * Stage 1 is the original strict speech-like frame test. Stage 2 only revisits uncertain frames
 * using a bounded adaptive noise floor and requires temporal support; it is not a second neural
 * model and therefore keeps CPU/RAM cost small. This improves quiet-speech recovery without
 * blindly accepting isolated breaths, music transients or street-noise spikes.
 */
class LightweightVoiceActivityDetector(
    private val frameMs: Int = 20,
    private val minimumSpeechMs: Int = 120,
    private val hangoverMs: Int = 180,
    private val paddingMs: Int = 120,
    private val minimumRms: Double = 0.0015,
    private val minimumCrestFactor: Double = 1.65,
    private val minZeroCrossingRate: Double = 0.008,
    private val maxZeroCrossingRate: Double = 0.38,
    private val minDifferenceRatio: Double = 0.012,
    private val maxDifferenceRatio: Double = 0.75
) : VoiceActivityDetector {

    private data class FrameFeatures(
        val rms: Double,
        val crestFactor: Double,
        val zcr: Double,
        val differenceRatio: Double
    )

    override suspend fun detectSpeechRegions(samples: FloatArray, sampleRateHz: Int): List<SpeechRegion> {
        require(sampleRateHz in 8_000..48_000) { "Unsupported VAD sample rate: $sampleRateHz" }
        if (samples.isEmpty()) return emptyList()

        val frameSamples = (sampleRateHz * frameMs / 1_000).coerceAtLeast(1)
        val frameCount = ceil(samples.size / frameSamples.toDouble()).toInt()
        val features = ArrayList<FrameFeatures>(frameCount)
        val strict = BooleanArray(frameCount)

        for (frame in 0 until frameCount) {
            if (frame and 0x0F == 0) currentCoroutineContext().ensureActive()
            val start = frame * frameSamples
            val end = minOf(samples.size, start + frameSamples)
            val f = frameFeatures(samples, start, end)
            features += f
            strict[frame] = isStrictSpeech(f)
        }

        val adaptiveRms = adaptiveRmsThreshold(features)
        val candidate = strict.copyOf()
        for (frame in 0 until frameCount) {
            if (strict[frame]) continue
            val f = features[frame]
            if (!isUncertainSpeech(f, adaptiveRms)) continue

            // Rescue only temporally supported uncertain speech. This rejects isolated breathing,
            // impacts and short noise spikes while retaining sustained low-volume speech.
            val from = maxOf(0, frame - 3)
            val to = minOf(frameCount - 1, frame + 3)
            var support = 0
            var strictSupport = 0
            for (i in from..to) {
                if (strict[i]) strictSupport++
                if (strict[i] || isUncertainSpeech(features[i], adaptiveRms)) support++
            }
            candidate[frame] = strictSupport > 0 || support >= 4
        }

        // Remove isolated hits while retaining quiet speech that persists across frames.
        val smoothed = BooleanArray(frameCount)
        for (frame in 0 until frameCount) {
            val from = maxOf(0, frame - 2)
            val to = minOf(frameCount - 1, frame + 2)
            var votes = 0
            for (i in from..to) if (candidate[i]) votes++
            smoothed[frame] = votes >= 2
        }

        val minimumFrames = ceil(minimumSpeechMs / frameMs.toDouble()).toInt().coerceAtLeast(1)
        val hangoverFrames = ceil(hangoverMs / frameMs.toDouble()).toInt().coerceAtLeast(0)
        val paddingSamples = sampleRateHz * paddingMs / 1_000
        val regions = ArrayList<SpeechRegion>()

        var runStart = -1
        var lastSpeech = -1
        var frame = 0
        while (frame < frameCount) {
            if (frame and 0x1F == 0) currentCoroutineContext().ensureActive()
            if (smoothed[frame]) {
                if (runStart < 0) runStart = frame
                lastSpeech = frame
            }
            val shouldClose = runStart >= 0 && (
                frame == frameCount - 1 || (lastSpeech >= 0 && frame - lastSpeech > hangoverFrames)
            )
            if (shouldClose) {
                val speechFrameCount = lastSpeech - runStart + 1
                if (speechFrameCount >= minimumFrames) {
                    val rawStart = runStart * frameSamples
                    val rawEnd = minOf(samples.size, (lastSpeech + 1) * frameSamples)
                    val paddedStart = maxOf(0, rawStart - paddingSamples)
                    val paddedEnd = minOf(samples.size, rawEnd + paddingSamples)
                    appendMerged(regions, SpeechRegion(paddedStart, paddedEnd), sampleRateHz)
                }
                runStart = -1
                lastSpeech = -1
            }
            frame++
        }
        return regions
    }

    private fun frameFeatures(samples: FloatArray, start: Int, end: Int): FrameFeatures {
        val length = end - start
        if (length < 2) return FrameFeatures(0.0, 0.0, 0.0, 0.0)

        var sumSquares = 0.0
        var diffSquares = 0.0
        var zeroCrossings = 0
        var previous = samples[start].toDouble()
        var peak = abs(previous)
        sumSquares += previous * previous

        for (i in start + 1 until end) {
            val value = samples[i].toDouble()
            sumSquares += value * value
            peak = maxOf(peak, abs(value))
            val diff = value - previous
            diffSquares += diff * diff
            if ((value >= 0.0) != (previous >= 0.0)) zeroCrossings++
            previous = value
        }

        val rms = sqrt(sumSquares / length)
        val crestFactor = peak / rms.coerceAtLeast(1e-9)
        val zcr = zeroCrossings.toDouble() / (length - 1).coerceAtLeast(1)
        val differenceRms = sqrt(diffSquares / (length - 1).coerceAtLeast(1))
        val differenceRatio = differenceRms / rms.coerceAtLeast(1e-9)
        return FrameFeatures(rms, crestFactor, zcr, differenceRatio)
    }

    private fun isStrictSpeech(f: FrameFeatures): Boolean =
        f.rms >= minimumRms &&
            f.crestFactor >= minimumCrestFactor &&
            f.zcr in minZeroCrossingRate..maxZeroCrossingRate &&
            f.differenceRatio in minDifferenceRatio..maxDifferenceRatio

    private fun isUncertainSpeech(f: FrameFeatures, adaptiveRms: Double): Boolean =
        f.rms >= adaptiveRms &&
            f.crestFactor >= minimumCrestFactor * 0.86 &&
            f.zcr in (minZeroCrossingRate * 0.70)..(maxZeroCrossingRate * 1.05).coerceAtMost(0.48) &&
            f.differenceRatio in (minDifferenceRatio * 0.72)..(maxDifferenceRatio * 1.08).coerceAtMost(0.88)

    /**
     * Lower-quintile RMS is a cheap local noise-floor proxy. The result is intentionally bounded:
     * it may relax the old absolute gate for clean quiet speech, but never raises it enough to make
     * ordinary speech disappear in a noisy chunk.
     */
    private fun adaptiveRmsThreshold(features: List<FrameFeatures>): Double {
        if (features.isEmpty()) return minimumRms
        val sorted = features.asSequence().map { it.rms }.sorted().toList()
        val floorIndex = ((sorted.size - 1) * 0.20).toInt().coerceIn(0, sorted.lastIndex)
        val noiseFloor = sorted[floorIndex]
        val relaxedAbsolute = minimumRms * 0.45
        val relative = noiseFloor * 1.75
        return maxOf(relaxedAbsolute, minOf(minimumRms, relative))
    }

    private fun appendMerged(regions: MutableList<SpeechRegion>, next: SpeechRegion, sampleRateHz: Int) {
        val mergeGapSamples = sampleRateHz * hangoverMs / 1_000
        val previous = regions.lastOrNull()
        if (previous != null && next.startSample - previous.endSample <= mergeGapSamples) {
            regions[regions.lastIndex] = SpeechRegion(previous.startSample, maxOf(previous.endSample, next.endSample))
        } else {
            regions += next
        }
    }
}
