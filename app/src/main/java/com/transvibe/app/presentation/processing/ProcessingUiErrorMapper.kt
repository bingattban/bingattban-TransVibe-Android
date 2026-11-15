package com.transvibe.app.presentation.processing

import androidx.annotation.StringRes
import com.transvibe.app.R
import com.transvibe.app.domain.error.ErrorMessageKey
import com.transvibe.app.domain.error.ProcessingError
import com.transvibe.app.domain.error.RecoveryAction

data class ProcessingUiError(
    @param:StringRes val title: Int,
    @param:StringRes val explanation: Int,
    @param:StringRes val nextStep: Int,
    val retryable: Boolean,
    val opensResources: Boolean = false
)

/** The UI consumes only domain error keys. Raw Throwable/messages never cross this boundary. */
object ProcessingUiErrorMapper {
    fun map(error: ProcessingError): ProcessingUiError = when (error.messageKey) {
        ErrorMessageKey.SOURCE_UNAVAILABLE -> ui(R.string.error_invalid_video_title, R.string.error_invalid_video_explanation, R.string.error_invalid_video_action, error)
        ErrorMessageKey.UNSUPPORTED_MEDIA -> ui(R.string.error_codec_title, R.string.error_codec_explanation, R.string.error_codec_action, error)
        ErrorMessageKey.AUDIO_DECODE_FAILURE -> ui(R.string.error_audio_decode_title, R.string.error_audio_decode_explanation, R.string.error_audio_decode_action, error)
        ErrorMessageKey.MODEL_MISSING, ErrorMessageKey.MODEL_CORRUPTED, ErrorMessageKey.MODEL_LOAD_FAILURE ->
            ui(R.string.error_resource_title, R.string.error_resource_explanation, R.string.error_resource_action, error, true)
        ErrorMessageKey.TRANSLATION_PACK_MISSING ->
            ui(R.string.error_translation_resource_title, R.string.error_translation_resource_explanation, R.string.error_resource_action, error, true)
        ErrorMessageKey.TRANSLATION_FAILURE -> ui(R.string.error_translation_failure_title, R.string.error_translation_failure_explanation, R.string.error_translation_failure_action, error)
        ErrorMessageKey.NATIVE_FAILURE -> ui(R.string.error_engine_title, R.string.error_engine_explanation, R.string.error_engine_action, error)
        ErrorMessageKey.STORAGE_FAILURE -> ui(R.string.error_storage_title, R.string.error_storage_explanation, R.string.error_storage_action, error)
        ErrorMessageKey.OUT_OF_MEMORY -> ui(R.string.error_memory_title, R.string.error_memory_explanation, R.string.error_memory_action, error)
        ErrorMessageKey.INTERRUPTED -> ui(R.string.error_interrupted_title, R.string.error_interrupted_explanation, R.string.error_interrupted_action, error)
        ErrorMessageKey.CANCELLED -> ui(R.string.error_cancelled_title, R.string.error_cancelled_explanation, R.string.error_cancelled_action, error)
        ErrorMessageKey.UNKNOWN -> ui(R.string.error_generic_title, R.string.error_generic_explanation, R.string.error_generic_action, error)
    }

    private fun ui(
        title: Int,
        explanation: Int,
        nextStep: Int,
        error: ProcessingError,
        opensResources: Boolean = error.recommendedRecoveryAction == RecoveryAction.OPEN_AI_RESOURCES ||
            error.recommendedRecoveryAction == RecoveryAction.REDOWNLOAD_MODEL ||
            error.recommendedRecoveryAction == RecoveryAction.DOWNLOAD_TRANSLATION_PACK
    ) = ProcessingUiError(title, explanation, nextStep, error.retryable, opensResources)
}
