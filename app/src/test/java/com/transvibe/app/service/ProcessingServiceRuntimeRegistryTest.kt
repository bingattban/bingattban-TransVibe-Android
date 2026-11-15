package com.transvibe.app.service

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.UUID

class ProcessingServiceRuntimeRegistryTest {
    @Test fun cancellationMustNotAdvertiseIdleBeforeNativeWorkerReleases() {
        val jobId = UUID.randomUUID().toString()
        try {
            ProcessingServiceRuntimeRegistry.markActive(jobId)
            assertTrue(ProcessingServiceRuntimeRegistry.isActive(jobId))
            assertTrue(ProcessingServiceRuntimeRegistry.hasActiveProcessing())
        } finally {
            ProcessingServiceRuntimeRegistry.markInactive(jobId)
        }
        assertFalse(ProcessingServiceRuntimeRegistry.isActive(jobId))
    }
}
