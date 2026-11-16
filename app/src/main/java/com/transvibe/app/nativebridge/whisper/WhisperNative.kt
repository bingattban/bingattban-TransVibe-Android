package com.transvibe.app.nativebridge.whisper

import androidx.annotation.Keep

import com.transvibe.app.domain.model.SubtitleSegment

enum class NativeErrorCode {
    INVALID_HANDLE,
    MODEL_INVALID,
    MODEL_NOT_FOUND,
    MODEL_NOT_READABLE,
    INVALID_AUDIO,
    INVALID_ARGUMENT,
    ALLOCATION_FAILED,
    INIT_FAILED,
    INFERENCE_FAILED,
    INVALID_RESULT,
    CANCELLED,
    UNKNOWN
}

class NativeBridgeException(
    val code: NativeErrorCode,
    internalMessage: String,
    cause: Throwable? = null
) : RuntimeException(internalMessage, cause)

@Keep
object WhisperNative {
    @Volatile private var loadAttempted = false
    @Volatile private var loadFailure: Throwable? = null

    private fun ensureLoaded() {
        if (!loadAttempted) synchronized(this) {
            if (!loadAttempted) {
                loadFailure = runCatching { System.loadLibrary("transvibe_native") }.exceptionOrNull()
                loadAttempted = true
            }
        }
        loadFailure?.let {
            throw NativeBridgeException(
                NativeErrorCode.INIT_FAILED,
                "Precompiled native library libtransvibe_native.so is unavailable for arm64-v8a",
                it
            )
        }
    }

    fun isLibraryAvailable(): Boolean = runCatching { ensureLoaded(); true }.getOrDefault(false)

    private external fun nativeCreateContext(modelPath: String): Long
    /** Optional: not all prebuilt native libraries expose backend introspection. */
    private external fun nativeBackendForContext(handle: Long): String
    @Volatile private var backendQueryUnsupported = false

    fun runtimeBackend(handle: Long): String {
        if (backendQueryUnsupported) return "UNVERIFIED_REBUILD_GPU_LIBRARY"
        return try {
            ensureLoaded()
            nativeBackendForContext(handle).ifBlank { "UNVERIFIED" }
        } catch (_: UnsatisfiedLinkError) {
            backendQueryUnsupported = true
            "UNVERIFIED_REBUILD_GPU_LIBRARY"
        } catch (_: NoSuchMethodError) {
            backendQueryUnsupported = true
            "UNVERIFIED_REBUILD_GPU_LIBRARY"
        } catch (_: IllegalStateException) {
            "UNVERIFIED"
        }
    }

    private external fun nativeDetectLanguageDetailed(
        handle: Long,
        audioSamples: FloatArray,
        offset: Int,
        sampleCount: Int,
        threads: Int
    ): WhisperLanguageDetectionResult
    private external fun nativeIsMultilingual(handle: Long): Boolean
    private external fun nativeModelTypeReadable(handle: Long): String
    private external fun nativeTranscribeStructured(
        handle: Long,
        audioSamples: FloatArray,
        offset: Int,
        sampleCount: Int,
        language: String?,
        translate: Boolean,
        threads: Int,
        prompt: String?
    ): Array<WhisperSegmentResult>
    private external fun nativeBridgeSelfTest(text: String): WhisperSegmentResult
    private external fun nativeCancel(handle: Long)
    private external fun nativeReleaseContext(handle: Long)

    fun createContext(modelPath: String): Long = nativeCall { nativeCreateContext(modelPath) }

    fun detectLanguage(handle: Long, audioSamples: FloatArray, threads: Int): String =
        detectLanguageDetailed(handle, audioSamples, threads).language

    fun detectLanguageDetailed(handle: Long, audioSamples: FloatArray, threads: Int): WhisperLanguageDetectionResult =
        detectLanguageDetailed(handle, audioSamples, 0, audioSamples.size, threads)

    fun detectLanguageDetailed(
        handle: Long,
        audioSamples: FloatArray,
        offset: Int,
        sampleCount: Int,
        threads: Int
    ): WhisperLanguageDetectionResult {
        validateAudioRange(audioSamples, offset, sampleCount)
        return nativeCall { nativeDetectLanguageDetailed(handle, audioSamples, offset, sampleCount, threads) }
    }

    data class ModelInspection(val multilingual: Boolean, val modelType: String)

    fun inspectModel(modelPath: String): ModelInspection {
        val handle = createContext(modelPath)
        try {
            return ModelInspection(
                multilingual = nativeCall { nativeIsMultilingual(handle) },
                modelType = nativeCall { nativeModelTypeReadable(handle) }
            )
        } finally {
            runCatching { releaseContext(handle) }
        }
    }

    data class RecognitionSegment(
        val segment: SubtitleSegment,
        val averageTokenProbability: Float? = null,
        val avgLogProb: Float? = null,
        val noSpeechProbability: Float? = null,
        val tokenCount: Int = 0
    )

    fun transcribe(
        handle: Long,
        samples: FloatArray,
        language: String?,
        translate: Boolean,
        threads: Int,
        prompt: String? = null
    ): List<SubtitleSegment> = transcribeDetailed(handle, samples, language, translate, threads, prompt).map { it.segment }

    fun transcribeDetailed(
        handle: Long,
        samples: FloatArray,
        language: String?,
        translate: Boolean,
        threads: Int,
        prompt: String? = null
    ): List<RecognitionSegment> =
        transcribeDetailed(handle, samples, 0, samples.size, language, translate, threads, prompt)

    fun transcribeDetailed(
        handle: Long,
        samples: FloatArray,
        offset: Int,
        sampleCount: Int,
        language: String?,
        translate: Boolean,
        threads: Int,
        prompt: String? = null
    ): List<RecognitionSegment> {
        validateAudioRange(samples, offset, sampleCount)
        val boundedPrompt = prompt?.replace(Regex("\\s+"), " ")?.trim()?.take(512)?.takeIf { it.isNotBlank() }
        val nativeResults = nativeCall {
            nativeTranscribeStructured(handle, samples, offset, sampleCount, language, translate, threads, boundedPrompt)
        }
        return toRecognitionSegments(nativeResults)
    }

    fun transcribeRange(
        handle: Long,
        samples: FloatArray,
        offset: Int,
        sampleCount: Int,
        language: String?,
        translate: Boolean,
        threads: Int,
        prompt: String? = null
    ): List<SubtitleSegment> = transcribeDetailed(
        handle, samples, offset, sampleCount, language, translate, threads, prompt
    ).map { it.segment }

    internal fun toRecognitionSegments(nativeResults: Array<WhisperSegmentResult>): List<RecognitionSegment> =
        nativeResults.mapIndexed { index, result ->
            val start = result.startMs.coerceAtLeast(0L)
            val end = result.endMs.coerceAtLeast(start + 1L)
            RecognitionSegment(
                segment = SubtitleSegment(index.toLong(), start, end, result.text),
                averageTokenProbability = result.confidence,
                avgLogProb = result.avgLogProb,
                noSpeechProbability = result.noSpeechProbability,
                tokenCount = result.tokenCount
            )
        }

    internal fun bridgeSelfTestForTests(text: String): WhisperSegmentResult = nativeCall { nativeBridgeSelfTest(text) }

    fun cancel(handle: Long) { nativeCall<Unit> { nativeCancel(handle) } }
    fun releaseContext(handle: Long) { nativeCall<Unit> { nativeReleaseContext(handle) } }

    private fun validateAudioRange(samples: FloatArray, offset: Int, sampleCount: Int) {
        if (sampleCount <= 0) throw NativeBridgeException(NativeErrorCode.INVALID_AUDIO, "Audio buffer is empty")
        if (offset < 0 || sampleCount < 0 || offset > samples.size - sampleCount) {
            throw NativeBridgeException(NativeErrorCode.INVALID_ARGUMENT, "Audio range is outside the source buffer")
        }
        if (sampleCount > MAX_AUDIO_SAMPLES) {
            throw NativeBridgeException(NativeErrorCode.INVALID_AUDIO, "Audio buffer exceeds bounded chunk limit")
        }
    }

    private inline fun <T> nativeCall(block: () -> T): T = try {
        ensureLoaded()
        block()
    } catch (e: IllegalStateException) {
        throw parse(e)
    } catch (e: OutOfMemoryError) {
        throw e
    } catch (t: Throwable) {
        throw NativeBridgeException(NativeErrorCode.UNKNOWN, "Unexpected JNI bridge failure", t)
    }

    private fun parse(error: IllegalStateException): NativeBridgeException {
        val raw = error.message.orEmpty()
        if (!raw.startsWith(PREFIX)) return NativeBridgeException(NativeErrorCode.UNKNOWN, raw.ifBlank { "Unknown native failure" }, error)
        val parts = raw.split('|', limit = 3)
        val code = parts.getOrNull(1)?.let { runCatching { NativeErrorCode.valueOf(it) }.getOrNull() } ?: NativeErrorCode.UNKNOWN
        val detail = parts.getOrNull(2).orEmpty().ifBlank { "Native operation failed" }
        return NativeBridgeException(code, detail, error)
    }

    private const val PREFIX = "TVE|"
    internal const val MAX_AUDIO_SAMPLES = 70 * 16_000
}
