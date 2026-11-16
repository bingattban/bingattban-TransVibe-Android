package com.transvibe.app.presentation.processing

import com.transvibe.app.domain.model.ProcessingState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ProcessingTerminalNavigationTest {
    @Test fun cancelledGoesHomeAutomatically() {
        assertEquals(ProcessingTerminalRoute.HOME, processingTerminalRoute(ProcessingState.Cancelled))
    }

    @Test fun runningProcessDoesNotLeaveScreen() {
        assertNull(processingTerminalRoute(ProcessingState.Recognizing(30f, "en")))
        assertNull(processingTerminalRoute(ProcessingState.Preparing()))
    }
}
