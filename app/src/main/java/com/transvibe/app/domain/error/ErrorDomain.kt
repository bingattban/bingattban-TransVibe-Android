package com.transvibe.app.domain.error

/** Stable machine-readable error codes. Values are persisted/logged; do not rename existing codes. */
enum class DomainErrorCode(val stableCode: String) {
    SOURCE_UNAVAILABLE("source_unavailable"),
    UNSUPPORTED_MEDIA("unsupported_media"),
    AUDIO_DECODE_FAILURE("audio_decode_failure"),
    MODEL_MISSING("model_missing"),
    MODEL_CORRUPTED("model_corrupted"),
    MODEL_LOAD_FAILURE("model_load_failure"),
    TRANSLATION_PACK_MISSING("translation_pack_missing"),
    TRANSLATION_FAILURE("translation_failure"),
    NATIVE_FAILURE("native_failure"),
    STORAGE_FAILURE("storage_failure"),
    OUT_OF_MEMORY("out_of_memory"),
    INTERRUPTED("interrupted"),
    CANCELLED("cancelled"),
    UNKNOWN("unknown")
}

enum class ErrorMessageKey {
    SOURCE_UNAVAILABLE,
    UNSUPPORTED_MEDIA,
    AUDIO_DECODE_FAILURE,
    MODEL_MISSING,
    MODEL_CORRUPTED,
    MODEL_LOAD_FAILURE,
    TRANSLATION_PACK_MISSING,
    TRANSLATION_FAILURE,
    NATIVE_FAILURE,
    STORAGE_FAILURE,
    OUT_OF_MEMORY,
    INTERRUPTED,
    CANCELLED,
    UNKNOWN
}

enum class RecoveryAction {
    SELECT_SOURCE_AGAIN,
    CHOOSE_SUPPORTED_MEDIA,
    RETRY,
    OPEN_AI_RESOURCES,
    REDOWNLOAD_MODEL,
    DOWNLOAD_TRANSLATION_PACK,
    FREE_STORAGE,
    USE_SMALLER_MODEL,
    RESUME,
    RETURN_HOME,
    REPORT_IF_REPEATED,
    NONE
}

/**
 * Domain-owned processing failure. UI must consume this type and never a raw [Throwable].
 * [technicalCause] is retained only for diagnostics and must never be rendered as primary UI text.
 */
sealed class ProcessingError(
    val code: DomainErrorCode,
    val retryable: Boolean,
    val messageKey: ErrorMessageKey,
    val technicalCause: Throwable?,
    val recommendedRecoveryAction: RecoveryAction
) {
    class SourceUnavailable(
        cause: Throwable? = null,
        retryable: Boolean = true,
        recovery: RecoveryAction = RecoveryAction.SELECT_SOURCE_AGAIN
    ) : ProcessingError(DomainErrorCode.SOURCE_UNAVAILABLE, retryable, ErrorMessageKey.SOURCE_UNAVAILABLE, cause, recovery)

    class UnsupportedMedia(
        cause: Throwable? = null,
        retryable: Boolean = false,
        recovery: RecoveryAction = RecoveryAction.CHOOSE_SUPPORTED_MEDIA
    ) : ProcessingError(DomainErrorCode.UNSUPPORTED_MEDIA, retryable, ErrorMessageKey.UNSUPPORTED_MEDIA, cause, recovery)

    class AudioDecodeFailure(
        cause: Throwable? = null,
        retryable: Boolean = true,
        recovery: RecoveryAction = RecoveryAction.RETRY
    ) : ProcessingError(DomainErrorCode.AUDIO_DECODE_FAILURE, retryable, ErrorMessageKey.AUDIO_DECODE_FAILURE, cause, recovery)

    class ModelMissing(
        cause: Throwable? = null,
        retryable: Boolean = true,
        recovery: RecoveryAction = RecoveryAction.OPEN_AI_RESOURCES
    ) : ProcessingError(DomainErrorCode.MODEL_MISSING, retryable, ErrorMessageKey.MODEL_MISSING, cause, recovery)

    class ModelCorrupted(
        cause: Throwable? = null,
        retryable: Boolean = true,
        recovery: RecoveryAction = RecoveryAction.REDOWNLOAD_MODEL
    ) : ProcessingError(DomainErrorCode.MODEL_CORRUPTED, retryable, ErrorMessageKey.MODEL_CORRUPTED, cause, recovery)

    class ModelLoadFailure(
        cause: Throwable? = null,
        retryable: Boolean = true,
        recovery: RecoveryAction = RecoveryAction.REDOWNLOAD_MODEL
    ) : ProcessingError(DomainErrorCode.MODEL_LOAD_FAILURE, retryable, ErrorMessageKey.MODEL_LOAD_FAILURE, cause, recovery)

    class TranslationPackMissing(
        cause: Throwable? = null,
        retryable: Boolean = true,
        recovery: RecoveryAction = RecoveryAction.DOWNLOAD_TRANSLATION_PACK
    ) : ProcessingError(DomainErrorCode.TRANSLATION_PACK_MISSING, retryable, ErrorMessageKey.TRANSLATION_PACK_MISSING, cause, recovery)

    class TranslationFailure(
        cause: Throwable? = null,
        retryable: Boolean = true,
        recovery: RecoveryAction = RecoveryAction.RETRY
    ) : ProcessingError(DomainErrorCode.TRANSLATION_FAILURE, retryable, ErrorMessageKey.TRANSLATION_FAILURE, cause, recovery)

    class NativeFailure(
        cause: Throwable? = null,
        retryable: Boolean = true,
        recovery: RecoveryAction = RecoveryAction.RETRY
    ) : ProcessingError(DomainErrorCode.NATIVE_FAILURE, retryable, ErrorMessageKey.NATIVE_FAILURE, cause, recovery)

    class StorageFailure(
        cause: Throwable? = null,
        retryable: Boolean = true,
        recovery: RecoveryAction = RecoveryAction.FREE_STORAGE
    ) : ProcessingError(DomainErrorCode.STORAGE_FAILURE, retryable, ErrorMessageKey.STORAGE_FAILURE, cause, recovery)

    class OutOfMemory(
        cause: Throwable? = null,
        retryable: Boolean = true,
        recovery: RecoveryAction = RecoveryAction.USE_SMALLER_MODEL
    ) : ProcessingError(DomainErrorCode.OUT_OF_MEMORY, retryable, ErrorMessageKey.OUT_OF_MEMORY, cause, recovery)

    class Interrupted(
        cause: Throwable? = null,
        retryable: Boolean = true,
        recovery: RecoveryAction = RecoveryAction.RESUME
    ) : ProcessingError(DomainErrorCode.INTERRUPTED, retryable, ErrorMessageKey.INTERRUPTED, cause, recovery)

    class Cancelled(
        cause: Throwable? = null,
        retryable: Boolean = false,
        recovery: RecoveryAction = RecoveryAction.RETURN_HOME
    ) : ProcessingError(DomainErrorCode.CANCELLED, retryable, ErrorMessageKey.CANCELLED, cause, recovery)

    class Unknown(
        cause: Throwable? = null,
        retryable: Boolean = true,
        recovery: RecoveryAction = RecoveryAction.REPORT_IF_REPEATED
    ) : ProcessingError(DomainErrorCode.UNKNOWN, retryable, ErrorMessageKey.UNKNOWN, cause, recovery)
}
