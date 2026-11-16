package com.transvibe.app.presentation.startup

import com.transvibe.app.domain.error.ProcessingError
import com.transvibe.app.presentation.notification.NotificationPolicy
import com.transvibe.app.presentation.processing.ProcessingUiErrorMapper
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class UiStatePolicyTest {
    @Test fun notificationDeniedOnAndroid13PreventsOptionalCompletionNotification() {
        assertFalse(NotificationPolicy.canPostCompletionNotification(33, permissionGranted = false))
        assertTrue(NotificationPolicy.canPostCompletionNotification(32, permissionGranted = false))
    }

    @Test fun missingModelRoutesToResourceRecovery() {
        val ui = ProcessingUiErrorMapper.map(ProcessingError.ModelMissing())
        assertTrue(ui.opensResources)
        assertTrue(ui.retryable)
    }

    @Test fun missingTranslationPackRoutesToResourceRecovery() {
        val ui = ProcessingUiErrorMapper.map(ProcessingError.TranslationPackMissing())
        assertTrue(ui.opensResources)
        assertTrue(ui.retryable)
    }
}
