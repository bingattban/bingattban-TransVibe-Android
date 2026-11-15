package com.transvibe.app.nativebridge.moonshine

import android.os.SystemClock
import android.util.Log
import com.k2fsa.sherpa.onnx.OfflineModelConfig
import com.k2fsa.sherpa.onnx.OfflineMoonshineModelConfig
import com.k2fsa.sherpa.onnx.OfflineRecognizer
import com.k2fsa.sherpa.onnx.OfflineRecognizerConfig
import com.transvibe.app.data.asr.AdditionalMoonshineModelStore
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Application-scoped Moonshine v2 runtime.
 * One recognizer is reused across chunks to avoid repeatedly loading ONNX weights.
 */
internal class AdditionalMoonshineRuntimeManager(
    private val store: AdditionalMoonshineModelStore
) {
    private val mutex = Mutex()
    private var recognizer: OfflineRecognizer? = null
    private var recognizerThreads: Int = 0

    fun isInstalled(): Boolean = store.isInstalled()

    suspend fun transcribe(samples: FloatArray, threads: Int): String = mutex.withLock {
        // Full SHA-256 integrity verification reads the entire model from disk.
        // Verify when constructing a recognizer, not once for every VAD region.
        // If the model is removed, the next recognizer construction rechecks it.
        currentCoroutineContext().ensureActive()
        val active = ensureRecognizer(threads.coerceIn(1, 4))
        // Moonshine v2 is most reliable on short utterances. VAD regions can still
        // grow beyond 20 s, so keep inference windows bounded and reuse one recognizer.
        // Keep the Arabic ONNX decoder input bounded. This is a conservative
        // compatibility experiment, not a fix for incompatible model weights.
        val maxSamples = 6 * 16_000
        if (samples.size <= maxSamples) {
            decode(active, samples).also { check(it.isNotBlank()) { "Moonshine returned an empty transcript" } }
        } else {
            // Never return a partial transcript. Moonshine v2 can reject an individual
            // decoder window (ONNX broadcast/shape error); concatenating only the windows
            // that happened to succeed produces fluent-looking but incorrect subtitles.
            val parts = ArrayList<String>()
            var start = 0
            while (start < samples.size) {
                currentCoroutineContext().ensureActive()
                val end = minOf(start + maxSamples, samples.size)
                val part = decode(active, samples.copyOfRange(start, end))
                check(part.isNotBlank()) { "Moonshine returned an empty transcript for window ${start / 16000}-${end / 16000}s" }
                parts += part
                start = end
                currentCoroutineContext().ensureActive()
            }
            parts.joinToString(" ").trim()
        }
    }

    private fun decode(active: OfflineRecognizer, samples: FloatArray): String {
        val stream = active.createStream()
        return try {
            stream.acceptWaveform(samples, sampleRate = 16_000)
            val startedAt = SystemClock.elapsedRealtime()
            try {
                active.decode(stream)
                val result = active.getResult(stream).text.trim()
                Log.i("TransVibeMoonshine", "decode durationMs=${SystemClock.elapsedRealtime() - startedAt} audioMs=${samples.size / 16} chars=${result.length}")
                result
            } catch (failure: Exception) {
                Log.e("TransVibeMoonshine", "ONNX decode failed: audioMs=${samples.size / 16}, threads=$recognizerThreads, elapsedMs=${SystemClock.elapsedRealtime() - startedAt}", failure)
                throw IllegalStateException(
                    "Moonshine ONNX decode failed for ${samples.size} samples at 16 kHz " +
                        "(${samples.size / 16000.0}s); verify model/runtime compatibility.",
                    failure
                )
            }
        } finally {
            stream.release()
        }
    }

    suspend fun release() = mutex.withLock {
        recognizer?.release()
        recognizer = null
        recognizerThreads = 0
    }

    private fun ensureRecognizer(threads: Int): OfflineRecognizer {
        val current = recognizer
        if (current != null && recognizerThreads == threads) return current
        current?.release()
        // A failed replacement must never leave a released recognizer cached.
        recognizer = null
        recognizerThreads = 0
        require(store.isInstalled()) { "Additional Moonshine model is not installed or failed integrity verification" }
        val modelConfig = OfflineModelConfig(
            moonshine = OfflineMoonshineModelConfig(
                encoder = store.encoderFile.absolutePath,
                mergedDecoder = store.decoderFile.absolutePath
            ),
            tokens = store.tokensFile.absolutePath,
            numThreads = threads,
            debug = false,
            provider = "cpu"
        )
        return OfflineRecognizer(
            config = OfflineRecognizerConfig(
                modelConfig = modelConfig,
                decodingMethod = "greedy_search"
            )
        ).also {
            recognizer = it
            recognizerThreads = threads
        }
    }
}
