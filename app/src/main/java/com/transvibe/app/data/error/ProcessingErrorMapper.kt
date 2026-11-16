package com.transvibe.app.data.error

import com.transvibe.app.data.logging.AppLogger
import com.transvibe.app.domain.error.ProcessingError
import com.transvibe.app.domain.error.RecoveryAction
import com.transvibe.app.domain.model.AiResourceState
import com.transvibe.app.media.decoder.AudioDecoder
import com.transvibe.app.media.inspector.MediaSourceInspector
import com.transvibe.app.nativebridge.whisper.NativeBridgeException
import com.transvibe.app.nativebridge.whisper.NativeErrorCode
import kotlinx.coroutines.CancellationException
import java.io.FileNotFoundException
import java.io.IOException
import java.net.SocketTimeoutException
import java.net.UnknownHostException

/** Maps implementation failures into stable domain errors. No localized UI strings live here. */
object ProcessingErrorMapper {
    private const val TAG = "ProcessingError"

    fun fromThrowable(error: Throwable): ProcessingError {
        val mapped = when (error) {
            is CancellationException -> ProcessingError.Cancelled(error)
            is OutOfMemoryError -> ProcessingError.OutOfMemory(error)
            is SecurityException -> ProcessingError.SourceUnavailable(error)
            is FileNotFoundException -> ProcessingError.SourceUnavailable(error)
            is MediaSourceInspector.InspectionException.PermissionDenied -> ProcessingError.SourceUnavailable(error)
            is MediaSourceInspector.InspectionException.NoAudioTrack -> ProcessingError.UnsupportedMedia(error, retryable = false)
            is MediaSourceInspector.InspectionException.UnsupportedCodec -> ProcessingError.UnsupportedMedia(error)
            is MediaSourceInspector.InspectionException.InvalidVideo -> ProcessingError.SourceUnavailable(error)
            is AudioDecoder.AudioPipelineException.NoAudioTrack -> ProcessingError.UnsupportedMedia(error, retryable = false)
            is AudioDecoder.AudioPipelineException.UnsupportedCodec,
            is AudioDecoder.AudioPipelineException.UnsupportedPcm,
            is AudioDecoder.AudioPipelineException.UnknownAudioFormat -> ProcessingError.UnsupportedMedia(error)
            is AudioDecoder.AudioPipelineException.OpenSource -> ProcessingError.SourceUnavailable(error)
            is AudioDecoder.AudioPipelineException -> ProcessingError.AudioDecodeFailure(error)
            is NativeBridgeException -> native(error)
            is IOException -> ProcessingError.StorageFailure(error)
            else -> ProcessingError.Unknown(error)
        }
        AppLogger.error(TAG, mapped, "processing_failure")
        return mapped
    }

    fun fromDownloadThrowable(error: Throwable): ProcessingError {
        val message = error.message.orEmpty()
        val mapped = when {
            error is CancellationException -> ProcessingError.Cancelled(error)
            error is UnknownHostException || error is SocketTimeoutException -> ProcessingError.ModelLoadFailure(error)
            error is SecurityException -> ProcessingError.SourceUnavailable(error)
            message.contains("مساحة التخزين", true) || message.contains("storage", true) -> ProcessingError.StorageFailure(error)
            message.contains("libc++_shared.so", true) || message.contains("Whisper", true) -> ProcessingError.ModelLoadFailure(error, retryable = false)
            else -> ProcessingError.ModelLoadFailure(error)
        }
        AppLogger.error(TAG, mapped, "model_transfer_failure")
        return mapped
    }

    fun fromResource(state: AiResourceState): ProcessingError = when (state) {
        AiResourceState.MissingWhisperModel -> ProcessingError.ModelMissing()
        AiResourceState.MissingTranslationModel -> ProcessingError.TranslationPackMissing()
        is AiResourceState.MissingCapability -> ProcessingError.ModelLoadFailure(recovery = RecoveryAction.OPEN_AI_RESOURCES)
        is AiResourceState.CorruptedModel -> ProcessingError.ModelCorrupted()
        is AiResourceState.InsufficientStorage -> ProcessingError.StorageFailure()
        AiResourceState.NetworkUnavailable -> ProcessingError.ModelLoadFailure()
        is AiResourceState.Downloading -> ProcessingError.ModelMissing()
        is AiResourceState.Error -> ProcessingError.Unknown(IllegalStateException(state.message))
        AiResourceState.Ready -> ProcessingError.Unknown()
    }

    private fun native(error: NativeBridgeException): ProcessingError = when (error.code) {
        NativeErrorCode.MODEL_INVALID, NativeErrorCode.MODEL_NOT_FOUND, NativeErrorCode.MODEL_NOT_READABLE -> ProcessingError.ModelCorrupted(error)
        NativeErrorCode.INIT_FAILED -> ProcessingError.ModelLoadFailure(error)
        NativeErrorCode.INFERENCE_FAILED, NativeErrorCode.INVALID_RESULT -> ProcessingError.NativeFailure(error)
        NativeErrorCode.CANCELLED -> ProcessingError.Cancelled(error)
        NativeErrorCode.ALLOCATION_FAILED -> ProcessingError.OutOfMemory(error)
        NativeErrorCode.INVALID_HANDLE, NativeErrorCode.INVALID_AUDIO, NativeErrorCode.INVALID_ARGUMENT, NativeErrorCode.UNKNOWN -> ProcessingError.NativeFailure(error)
    }

}
