package com.transvibe.app.nativebridge.moonshine

import com.k2fsa.sherpa.onnx.OfflineModelConfig
import com.k2fsa.sherpa.onnx.OfflineMoonshineModelConfig
import com.k2fsa.sherpa.onnx.OfflineRecognizer
import com.k2fsa.sherpa.onnx.OfflineRecognizerConfig
import com.transvibe.app.data.asr.MoonshineModelStore
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Application-scoped Moonshine v2 runtime.
 * One recognizer is reused across chunks to avoid repeatedly loading ONNX weights.
 */
class MoonshineRuntimeManager(
    private val store: MoonshineModelStore
) {
    private val mutex = Mutex()
    private var recognizer: OfflineRecognizer? = null
    private var recognizerThreads: Int = 0

    fun isInstalled(): Boolean = store.quickInstalled()

    suspend fun transcribe(samples: FloatArray, threads: Int): String = mutex.withLock {
        require(store.quickInstalled()) { "Moonshine Tiny English is not installed" }
        val active = ensureRecognizer(threads.coerceIn(1, 4))
        // Moonshine v2 ONNX can fail inside encoder_attn/Add at 10+ seconds
        // (broadcast mismatch; also seen in the device log at 12 seconds).
        // Keep windows under that threshold while retaining Whisper fallback.
        // Revisit only after a verified fixed model/runtime becomes available.
        val maxSamples = 8 * 16_000
        if (samples.size <= maxSamples) {
            decode(active, samples).also { check(it.isNotBlank()) { "Moonshine returned an empty transcript" } }
        } else {
            // Never return a partial transcript. Moonshine v2 can reject an individual
            // decoder window (ONNX broadcast/shape error); concatenating only the windows
            // that happened to succeed produces fluent-looking but incorrect subtitles.
            val parts = ArrayList<String>()
            var start = 0
            while (start < samples.size) {
                val end = minOf(start + maxSamples, samples.size)
                val part = decode(active, samples.copyOfRange(start, end))
                check(part.isNotBlank()) { "Moonshine returned an empty transcript for window ${start / 16000}-${end / 16000}s" }
                parts += part
                start = end
            }
            parts.joinToString(" ").trim()
        }
    }

    private fun decode(active: OfflineRecognizer, samples: FloatArray): String {
        val stream = active.createStream()
        return try {
            stream.acceptWaveform(samples, sampleRate = 16_000)
            active.decode(stream)
            active.getResult(stream).text.trim()
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
        recognizer = null
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
