package com.transvibe.app.translation

import com.transvibe.app.domain.model.DownloadPolicy
import com.transvibe.app.domain.model.LanguageCode
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class OfflineTranslationGuardTest {
    private class FakeEngine(private val installed: Boolean) : TranslationEngine {
        override val sourceLanguage = LanguageCode.of("ar")
        override val targetLanguage = LanguageCode.ENGLISH
        var downloadCalls = 0
        override suspend fun isReady() = installed
        override suspend fun prepare(policy: DownloadPolicy) { downloadCalls++ }
        override suspend fun translate(text: String) = text
        override fun close() = Unit
    }

    @Test fun missingBridgeNeverDownloadsDuringProcessing() {
        val bridge = FakeEngine(false)
        assertThrows(IllegalStateException::class.java) {
            runBlocking { bridge.requireInstalledForProcessing() }
        }
        assertEquals(0, bridge.downloadCalls)
    }

    @Test fun installedBridgeCanBeUsedWithoutDownload() = runBlocking {
        val bridge = FakeEngine(true)
        bridge.requireInstalledForProcessing()
        assertEquals("ok", bridge.translate("ok"))
        assertEquals(0, bridge.downloadCalls)
    }
}
