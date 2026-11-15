package com.transvibe.app.data.download

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.IOException
import java.net.SocketTimeoutException
import java.net.URL

class ModelDownloadProtocolTest {
    @Test fun `successful full download starts from zero`() {
        val d = ModelDownloadProtocol.decideResume(0L, 200, null, 1_000L)
        assertEquals(0L, d.appendFromBytes)
        assertFalse(d.restartFromZero)
        assertEquals(1_000L, d.expectedTotalBytes)
    }

    @Test fun `206 resume appends only when content range matches partial`() {
        val d = ModelDownloadProtocol.decideResume(400L, 206, "bytes 400-999/1000", 1_000L)
        assertEquals(400L, d.appendFromBytes)
        assertFalse(d.restartFromZero)
        assertEquals(1_000L, d.expectedTotalBytes)
    }

    @Test fun `server ignoring range forces safe restart`() {
        val d = ModelDownloadProtocol.decideResume(400L, 200, null, 1_000L)
        assertTrue(d.restartFromZero)
        assertEquals(0L, d.appendFromBytes)
    }

    @Test fun `invalid content range is rejected`() {
        assertThrowsIo { ModelDownloadProtocol.decideResume(400L, 206, "bytes 300-999/1000", 1_000L) }
        assertThrowsIo { ModelDownloadProtocol.decideResume(400L, 206, "garbage", 1_000L) }
        assertThrowsIo { ModelDownloadProtocol.decideResume(400L, 206, "bytes 400-999/999", 1_000L) }
    }

    @Test fun `https redirect remains https`() {
        val next = ModelDownloadProtocol.resolveRedirect(URL("https://example.com/models/a.bin"), "/cdn/a.bin")
        assertEquals("https", next.protocol)
        assertEquals("example.com", next.host)
    }

    @Test fun `https downgrade redirect is rejected`() {
        try {
            ModelDownloadProtocol.resolveRedirect(URL("https://example.com/a.bin"), "http://evil.example/a.bin")
            fail("Expected downgrade rejection")
        } catch (_: SecurityException) { Unit }
    }

    @Test fun `timeout and retryable server status are recoverable`() {
        assertTrue(ModelDownloadProtocol.isRecoverableTransportFailure(SocketTimeoutException("timeout")))
        assertTrue(ModelDownloadProtocol.isRecoverableTransportFailure(HttpStatusFailure(503)))
        assertFalse(ModelDownloadProtocol.isRecoverableTransportFailure(HttpStatusFailure(404)))
    }

    private fun assertThrowsIo(block: () -> Unit) {
        try { block(); fail("Expected IOException") } catch (_: IOException) { Unit }
    }
}
