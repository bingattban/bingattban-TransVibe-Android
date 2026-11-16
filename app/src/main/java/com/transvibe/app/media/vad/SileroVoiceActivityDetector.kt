package com.transvibe.app.media.vad

import android.app.Application
import com.k2fsa.sherpa.onnx.SileroVadModelConfig
import com.k2fsa.sherpa.onnx.Vad
import com.k2fsa.sherpa.onnx.VadModelConfig
import com.transvibe.app.data.logging.AppLogger
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext

/**
 * Neural VAD with conservative quiet-speech protection. Never downloads a model or blocks the UI.
 * Place the official sherpa-compatible `silero_vad.onnx` in app/src/main/assets/vad/.
 * The existing lightweight detector remains a fully working fallback when the asset is absent.
 *
 * A single native Vad is reused sequentially (one mutex-free JVM monitor protects its JNI pointer).
 * reset() isolates chunks; close() calls release() for native C++ resource cleanup.
 */
class SileroVoiceActivityDetector(
    private val app: Application,
    private val fallback: VoiceActivityDetector,
    private val protectQuietSpeech: Boolean = true,
    private val prePaddingMs: Int = 170,
    private val postPaddingMs: Int = 230,
    private val mergeGapMs: Int = 260
) : VoiceActivityDetector, AutoCloseable {
    private val modelAssetPath = "vad/silero_vad.onnx"
    private val nativeLock = Any()
    private var nativeVad: Vad? = null
    private var permanentlyUnavailable = false
    private var reportedMissing = false

    override suspend fun detectSpeechRegions(samples: FloatArray, sampleRateHz: Int): List<SpeechRegion> =
        withContext(Dispatchers.Default) {
            currentCoroutineContext().ensureActive()
            if (samples.isEmpty()) return@withContext emptyList()
            if (sampleRateHz != 16_000) {
                return@withContext fallback.detectSpeechRegions(samples, sampleRateHz)
            }
            val fallbackRegions = fallback.detectSpeechRegions(samples, sampleRateHz)
            if (!modelAvailable()) return@withContext fallbackRegions
            val activeJob = currentCoroutineContext()
            val neural = try {
                synchronized(nativeLock) {
                    val detector = nativeVad ?: Vad(
                        assetManager = app.assets,
                        config = VadModelConfig(
                            sileroVadModelConfig = SileroVadModelConfig(
                                model = modelAssetPath,
                                threshold = 0.42f,
                                minSilenceDuration = 0.22f,
                                minSpeechDuration = 0.16f,
                                windowSize = 512,
                                maxSpeechDuration = 30.0f
                            ),
                            sampleRate = 16_000,
                            numThreads = 1,
                            provider = "cpu",
                            debug = false
                        )
                    ).also { nativeVad = it }
                    detector.reset()
                    try {
                        val step = 512
                        val window = FloatArray(step)
                        var index = 0
                        while (index < samples.size) {
                            if (index % (step * 64) == 0) activeJob.ensureActive()
                            // Reuse a single 512-sample buffer; zero-pad only EOF.
                            val remaining = minOf(step, samples.size - index)
                            System.arraycopy(samples, index, window, 0, remaining)
                            if (remaining < step) java.util.Arrays.fill(window, remaining, step, 0f)
                            detector.acceptWaveform(window)
                            index += remaining
                        }
                        detector.flush()
                        val regions = ArrayList<SpeechRegion>()
                        while (!detector.empty()) {
                            val segment = detector.front()
                            val start = segment.start.coerceIn(0, samples.size)
                            val end = (segment.start.toLong() + segment.samples.size)
                                .coerceIn(0L, samples.size.toLong()).toInt()
                            if (end > start) regions.add(SpeechRegion(start, end))
                            detector.pop()
                        }
                        regions
                    } finally {
                        detector.reset()
                    }
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                // A defective ONNX model or native configuration must not break video processing.
                synchronized(nativeLock) {
                    nativeVad?.release()
                    nativeVad = null
                    permanentlyUnavailable = true
                }
                AppLogger.e("SileroVAD", "event=silero_failed fallback=lightweight reason=${error.javaClass.simpleName}")
                return@withContext fallbackRegions
            }
            currentCoroutineContext().ensureActive()
            val regions = SpeechRegionFusion.fuse(
                neural = neural,
                lightweight = fallbackRegions,
                totalSamples = samples.size,
                sampleRateHz = sampleRateHz,
                prePaddingMs = prePaddingMs,
                postPaddingMs = postPaddingMs,
                mergeGapMs = mergeGapMs,
                rescueQuietSpeech = protectQuietSpeech
            )
            AppLogger.d("SileroVAD", "engine=silero neural=${neural.size} lightweight=${fallbackRegions.size} selected=${regions.size} speech_samples=${regions.sumOf { it.endSample - it.startSample }} total_samples=${samples.size}")
            regions
        }

    private fun modelAvailable(): Boolean {
        if (permanentlyUnavailable) return false
        return try {
            app.assets.open(modelAssetPath).use { }
            true
        } catch (_: java.io.IOException) {
            if (!reportedMissing) {
                reportedMissing = true
                AppLogger.d("SileroVAD", "event=model_missing fallback=lightweight expected=assets/$modelAssetPath")
            }
            false
        }
    }

    override fun close() {
        synchronized(nativeLock) {
            nativeVad?.release()
            nativeVad = null
        }
    }
}

/** Pure, testable timing policy; never alters input audio or the video's absolute timestamps. */
object SpeechRegionFusion {
    fun fuse(
        neural: List<SpeechRegion>,
        lightweight: List<SpeechRegion>,
        totalSamples: Int,
        sampleRateHz: Int,
        prePaddingMs: Int = 170,
        postPaddingMs: Int = 230,
        mergeGapMs: Int = 260,
        rescueQuietSpeech: Boolean = true
    ): List<SpeechRegion> {
        if (totalSamples <= 0) return emptyList()
        require(sampleRateHz > 0)
        val prefix = (sampleRateHz.toLong() * prePaddingMs / 1000).toInt()
        val suffix = (sampleRateHz.toLong() * postPaddingMs / 1000).toInt()
        val gap = (sampleRateHz.toLong() * mergeGapMs / 1000).toInt()
        val protected = ArrayList<SpeechRegion>(neural.size + lightweight.size)
        protected.addAll(neural)
        if (rescueQuietSpeech) {
            if (neural.isEmpty()) {
                // Prefer recall over losing a quiet voice; the lightweight VAD rejects simple tones.
                protected.addAll(lightweight)
            } else {
                // Rescue only near actual neural speech; don't restore long music-only regions.
                val proximity = sampleRateHz / 3
                for (candidate in lightweight) {
                    if (neural.any { candidate.startSample <= it.endSample + proximity &&
                            candidate.endSample >= it.startSample - proximity }) protected.add(candidate)
                }
            }
        }
        val ordered = protected.mapNotNull { region ->
            val start = (region.startSample - prefix).coerceAtLeast(0)
            val end = (region.endSample.toLong() + suffix).coerceAtMost(totalSamples.toLong()).toInt()
            if (end > start) SpeechRegion(start, end) else null
        }.sortedBy { it.startSample }
        val merged = ArrayList<SpeechRegion>()
        for (region in ordered) {
            val prev = merged.lastOrNull()
            if (prev != null && region.startSample.toLong() <= prev.endSample.toLong() + gap) {
                merged[merged.lastIndex] = SpeechRegion(prev.startSample, maxOf(prev.endSample, region.endSample))
            } else merged.add(region)
        }
        return merged
    }
}
