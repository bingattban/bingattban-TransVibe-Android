package com.transvibe.app.domain.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class OfficialWhisperModelsTest {
    @Test
    fun standardWhisperCatalogContainsAllPrimaryFamilies() {
        val ids = OfficialWhisperModels.whisperModels.map { it.id }.toSet()
        assertEquals(
            setOf(
                "tiny", "tiny-en", "base", "base-en", "small", "small-en",
                "medium", "medium-en", "large-v1", "large-v2", "large-v3", "large-v3-turbo"
            ),
            ids
        )
    }

    @Test
    fun downloadableModelsHavePinnedIntegrityMetadata() {
        OfficialWhisperModels.whisperModels.forEach { model ->
            assertTrue(model.downloadUrl?.startsWith("https://") == true)
            assertTrue(model.fileSizeBytes > 0L)
            assertTrue(model.sha256.matches(Regex("[0-9a-f]{64}")))
        }
    }

    @Test
    fun onlyDotEnModelsAreEnglishOnly() {
        OfficialWhisperModels.whisperModels.forEach { model ->
            if (model.id.endsWith("-en")) assertTrue(model.englishOnly)
            else assertFalse(model.englishOnly)
        }
    }
}
