package com.transvibe.app.data.error

import com.transvibe.app.domain.error.DomainErrorCode
import com.transvibe.app.domain.error.ErrorMessageKey
import com.transvibe.app.domain.error.RecoveryAction
import com.transvibe.app.nativebridge.whisper.NativeBridgeException
import com.transvibe.app.nativebridge.whisper.NativeErrorCode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Test

class ProcessingErrorMapperTest {
    @Test
    fun nativeInferenceFailureMapsToStableDomainError() {
        val cause = NativeBridgeException(NativeErrorCode.INFERENCE_FAILED, "internal native detail")
        val error = ProcessingErrorMapper.fromThrowable(cause)
        assertEquals(DomainErrorCode.NATIVE_FAILURE, error.code)
        assertEquals("native_failure", error.code.stableCode)
        assertEquals(ErrorMessageKey.NATIVE_FAILURE, error.messageKey)
        assertEquals(RecoveryAction.RETRY, error.recommendedRecoveryAction)
        assertSame(cause, error.technicalCause)
    }

    @Test
    fun outOfMemoryHasDedicatedRecoverableDomainError() {
        val cause = OutOfMemoryError("allocator detail")
        val error = ProcessingErrorMapper.fromThrowable(cause)
        assertEquals(DomainErrorCode.OUT_OF_MEMORY, error.code)
        assertEquals(ErrorMessageKey.OUT_OF_MEMORY, error.messageKey)
        assertEquals(RecoveryAction.USE_SMALLER_MODEL, error.recommendedRecoveryAction)
        assertSame(cause, error.technicalCause)
    }
}
