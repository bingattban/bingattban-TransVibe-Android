package com.transvibe.app.nativebridge.sensevoice

import com.k2fsa.sherpa.onnx.OfflineModelConfig
import com.k2fsa.sherpa.onnx.OfflineRecognizer
import com.k2fsa.sherpa.onnx.OfflineRecognizerConfig
import com.k2fsa.sherpa.onnx.OfflineSenseVoiceModelConfig
import com.transvibe.app.data.asr.SenseVoiceModelStore
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

data class SenseVoiceRecognition(val text: String, val language: String)

class SenseVoiceRuntimeManager(private val store: SenseVoiceModelStore) {
    private val mutex = Mutex()
    private var recognizer: OfflineRecognizer? = null
    private var recognizerThreads = 0
    private var recognizerLanguage = ""
    fun isInstalled(): Boolean = store.quickInstalled()

    suspend fun transcribe(samples: FloatArray, threads: Int, language: String = ""): SenseVoiceRecognition = mutex.withLock {
        require(store.quickInstalled()) { "SenseVoice-Small is not installed" }
        val active = ensureRecognizer(threads.coerceIn(1, 4), language)
        val stream = active.createStream()
        try {
            stream.acceptWaveform(samples, sampleRate = 16_000)
            active.decode(stream)
            val result = active.getResult(stream)
            SenseVoiceRecognition(result.text.trim(), result.lang.trim().lowercase())
        } finally { stream.release() }
    }

    suspend fun release() = mutex.withLock { recognizer?.release(); recognizer=null; recognizerThreads=0; recognizerLanguage="" }

    private fun ensureRecognizer(threads: Int, language: String): OfflineRecognizer {
        val current = recognizer
        if (current != null && recognizerThreads == threads && recognizerLanguage == language) return current
        current?.release()
        recognizer = null
        val modelConfig = OfflineModelConfig(
            senseVoice = OfflineSenseVoiceModelConfig(
                model = store.modelFile.absolutePath,
                language = language,
                useInverseTextNormalization = true
            ),
            tokens = store.tokensFile.absolutePath,
            numThreads = threads,
            debug = false,
            provider = "cpu"
        )
        return OfflineRecognizer(config = OfflineRecognizerConfig(modelConfig = modelConfig, decodingMethod = "greedy_search")).also {
            recognizer = it; recognizerThreads = threads; recognizerLanguage = language
        }
    }
}
