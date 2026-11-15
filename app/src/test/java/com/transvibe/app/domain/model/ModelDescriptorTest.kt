package com.transvibe.app.domain.model

import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.assertThrows
import org.junit.Test

class ModelDescriptorTest {
    @Test
    fun officialModelsCarryTrustedSourceAndCapabilities() {
        OfficialWhisperModels.whisperModels.forEach { model ->
            assertTrue(model.origin == ModelOrigin.OFFICIAL_TRUSTED)
            assertTrue(model.downloadUrl?.startsWith("https://") == true)
            assertTrue(model.sha256.matches(Regex("[0-9a-f]{64}")))
            assertTrue(ModelCapability.PROMPT_CONTEXT in model.capabilities)
        }
    }

    @Test
    fun importedDescriptorTreatsHashAsLocalFingerprintOnly() {
        val imported = WhisperModel(
            id = "imported-test",
            displayName = "Imported",
            fileName = "imported-test.bin",
            downloadUrl = null,
            sha256 = "a".repeat(64),
            fileSizeBytes = 10_000_000,
            estimatedRamBytes = 512_000_000,
            type = WhisperModelType.CUSTOM,
            descriptionAr = "test",
            origin = ModelOrigin.USER_IMPORTED,
            importTimestampEpochMs = 1L,
            nativeValidationState = NativeValidationState.VALID
        )
        assertFalse(imported.isOfficialTrusted)
        assertNull(imported.downloadUrl)
        assertTrue(imported.localIntegrityFingerprint == "a".repeat(64))
    }
    @Test
    fun descriptorRejectsTraversalInIdAndFilename() {
        assertThrows(IllegalArgumentException::class.java) {
            WhisperModel(
                id = "../escape", displayName = "Bad", fileName = "model.bin", downloadUrl = null,
                sha256 = "a".repeat(64), fileSizeBytes = 10_000_000, estimatedRamBytes = null,
                type = WhisperModelType.CUSTOM, descriptionAr = "bad", origin = ModelOrigin.USER_IMPORTED
            )
        }
        assertThrows(IllegalArgumentException::class.java) {
            WhisperModel(
                id = "safe-id", displayName = "Bad", fileName = "..", downloadUrl = null,
                sha256 = "a".repeat(64), fileSizeBytes = 10_000_000, estimatedRamBytes = null,
                type = WhisperModelType.CUSTOM, descriptionAr = "bad", origin = ModelOrigin.USER_IMPORTED
            )
        }
    }

}
