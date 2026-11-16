package com.transvibe.app.nativebridge.sherpa

import com.k2fsa.sherpa.onnx.OfflineModelConfig
import com.k2fsa.sherpa.onnx.OfflineMoonshineModelConfig
import com.k2fsa.sherpa.onnx.OfflineRecognizer
import com.k2fsa.sherpa.onnx.OfflineRecognizerConfig
import com.k2fsa.sherpa.onnx.OfflineSenseVoiceModelConfig
import com.k2fsa.sherpa.onnx.OfflineWhisperModelConfig
import com.transvibe.app.domain.concurrency.AppDispatchers
import java.io.File
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/** Model files must have been installed/verified by their model store before use. */
internal sealed interface SherpaModelFiles {
    val tokens: File

    data class Whisper(val encoder: File, val decoder: File, override val tokens: File) : SherpaModelFiles
    data class Moonshine(val encoder: File, val mergedDecoder: File, override val tokens: File,
        val supportedLanguage: String) : SherpaModelFiles
    data class SenseVoice(val model: File, override val tokens: File) : SherpaModelFiles
}

/** Isolated inference session: bounded PCM chunks, one recognizer, one native stream per call. */
internal class SherpaOfflineAsrRepository {
    suspend fun open(
        files: SherpaModelFiles,
        sourceLanguage: String = "ar",
        threads: Int = 2
    ): Session = withContext(AppDispatchers.nativeInference) {
        val lang = sourceLanguage.trim().lowercase().ifBlank { "ar" }
        require(threads in 1..8) { "عدد خيوط المعالجة غير صالح." }
        val modelConfig = when (files) {
            is SherpaModelFiles.Whisper -> {
                validateFiles(files.tokens, files.encoder, files.decoder)
                OfflineModelConfig(
                    whisper = OfflineWhisperModelConfig(
                        encoder = files.encoder.absolutePath, decoder = files.decoder.absolutePath,
                        language = lang.takeUnless { it == "auto" } ?: "", task = "transcribe"
                    ), tokens = files.tokens.absolutePath, modelType = "whisper",
                    numThreads = threads, debug = false, provider = "cpu"
                )
            }
            is SherpaModelFiles.Moonshine -> {
                require(files.supportedLanguage == lang) { "نموذج Moonshine المحدد لا يدعم لغة الصوت $lang." }
                validateFiles(files.tokens, files.encoder, files.mergedDecoder)
                OfflineModelConfig(
                    moonshine = OfflineMoonshineModelConfig(
                        encoder = files.encoder.absolutePath, mergedDecoder = files.mergedDecoder.absolutePath
                    ), tokens = files.tokens.absolutePath, numThreads = threads, debug = false, provider = "cpu"
                )
            }
            is SherpaModelFiles.SenseVoice -> {
                require(lang in setOf("auto", "zh", "en", "ja", "ko", "yue")) {
                    "SenseVoice-Small لا يدعم العربية؛ استخدم Whisper متعدد اللغات أو Moonshine Arabic."
                }
                validateFiles(files.tokens, files.model)
                OfflineModelConfig(
                    senseVoice = OfflineSenseVoiceModelConfig(
                        model = files.model.absolutePath, language = if (lang == "auto") "" else lang,
                        useInverseTextNormalization = true
                    ), tokens = files.tokens.absolutePath, numThreads = threads, debug = false, provider = "cpu"
                )
            }
        }
        val native = OfflineRecognizer(config = OfflineRecognizerConfig(modelConfig = modelConfig, decodingMethod = "greedy_search"))
        Session(native)
    }

    private fun validateFiles(vararg files: File) = files.forEach {
        require(it.isFile && it.length() > 0L) { "ملف نموذج التعرف غير موجود أو فارغ: ${it.name}" }
    }

    internal class Session(private val recognizer: OfflineRecognizer) {
        private val mutex = Mutex()
        private var closed = false

        suspend fun recognize(samples: FloatArray): String = withContext(AppDispatchers.nativeInference) {
            currentCoroutineContext().ensureActive()
            require(samples.isNotEmpty() && samples.size <= 30 * 16_000) { "يجب أن يتراوح طول المقطع بين 0 و30 ثانية." }
            mutex.withLock {
                check(!closed) { "تم إغلاق نموذج التعرف." }
                val stream = recognizer.createStream()
                try {
                    stream.acceptWaveform(samples, sampleRate = 16_000)
                    recognizer.decode(stream)
                    recognizer.getResult(stream).text.trim()
                } finally {
                    stream.release()
                }
            }.also { currentCoroutineContext().ensureActive() }
        }

        suspend fun release() = withContext(AppDispatchers.nativeInference) {
            mutex.withLock {
                if (!closed) {
                    closed = true
                    recognizer.release()
                }
            }
        }
    }
}
