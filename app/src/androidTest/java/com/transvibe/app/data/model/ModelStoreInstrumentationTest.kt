package com.transvibe.app.data.model

import android.test.InstrumentationTestCase
import com.transvibe.app.domain.model.ModelIntegrityState
import com.transvibe.app.domain.model.WhisperModel
import com.transvibe.app.domain.model.WhisperModelType
import com.transvibe.app.domain.repository.WhisperModelBinaryValidator
import kotlinx.coroutines.runBlocking
import java.security.MessageDigest

@Suppress("DEPRECATION")
class ModelStoreInstrumentationTest : InstrumentationTestCase() {
    private val validBytes = ByteArray(4096) { ((it * 31) and 0xff).toByte() }

    override fun tearDown() {
        instrumentation.context.filesDir.resolve("models").deleteRecursively()
        super.tearDown()
    }

    fun testValidOfficialModelIsReadyAndMissingMetadataIsRecovered() = runBlocking {
        val model = model("valid", validBytes)
        val store = store(nativeValid = true)
        store.file(model).apply { parentFile?.mkdirs(); writeBytes(validBytes) }

        assertTrue(store.validate(model))
        assertFalse(store.validate(model, requireMetadata = true))
        assertFalse(store.metadataFile(model).exists())

        assertEquals(ModelIntegrityState.READY, store.inspectAndRecover(model))
        assertTrue(store.metadataFile(model).isFile)
        assertTrue(store.validate(model, requireMetadata = true))
    }

    fun testWrongSizeIsCorrupted() = runBlocking {
        val model = model("wrong-size", validBytes)
        val store = store(nativeValid = true)
        store.file(model).apply { parentFile?.mkdirs(); writeBytes(validBytes.copyOf(validBytes.size - 1)) }
        assertEquals(ModelIntegrityState.BINARY_CORRUPTED, store.inspectAndRecover(model))
    }

    fun testWrongShaIsCorrupted() = runBlocking {
        val model = model("wrong-sha", validBytes, shaOverride = "0".repeat(64))
        val store = store(nativeValid = true)
        store.file(model).apply { parentFile?.mkdirs(); writeBytes(validBytes) }
        assertEquals(ModelIntegrityState.BINARY_CORRUPTED, store.inspectAndRecover(model))
    }

    fun testNativeInvalidModelIsCorrupted() = runBlocking {
        val model = model("native-invalid", validBytes)
        val store = store(nativeValid = false)
        store.file(model).apply { parentFile?.mkdirs(); writeBytes(validBytes) }
        assertEquals(ModelIntegrityState.BINARY_CORRUPTED, store.inspectAndRecover(model))
    }

    fun testStaleMetadataIsRepaired() = runBlocking {
        val model = model("stale-meta", validBytes)
        val store = store(nativeValid = true)
        store.file(model).apply { parentFile?.mkdirs(); writeBytes(validBytes) }
        store.metadataFile(model).apply { parentFile?.mkdirs(); writeText("{\"schemaVersion\":1,\"id\":\"wrong\"}") }

        assertFalse(store.validate(model, requireMetadata = true))
        assertEquals(ModelIntegrityState.READY, store.inspectAndRecover(model))
        assertTrue(store.validate(model, requireMetadata = true))
    }

    fun testInterruptedReplacementRecoversKnownGoodBackup() = runBlocking {
        val model = model("recovery", validBytes)
        val store = store(nativeValid = true)
        val final = store.file(model).apply { parentFile?.mkdirs(); writeBytes(ByteArray(validBytes.size) { 7 }) }
        val backup = final.resolveSibling(final.name + ".bak").apply { writeBytes(validBytes) }

        assertEquals(ModelIntegrityState.READY, store.inspectAndRecover(model))
        assertContentEquals(validBytes, final.readBytes())
        assertFalse(backup.exists())
    }

    private fun store(nativeValid: Boolean) = ModelStore(
        instrumentation.context,
        object : WhisperModelBinaryValidator {
            override suspend fun validate(candidatePath: String): Boolean = nativeValid
        }
    )

    private fun model(id: String, bytes: ByteArray, shaOverride: String? = null) = WhisperModel(
        id = "test-$id",
        displayName = "Test $id",
        fileName = "test-$id.bin",
        downloadUrl = "https://example.com/test-$id.bin",
        sha256 = shaOverride ?: sha256(bytes),
        fileSizeBytes = bytes.size.toLong(),
        estimatedRamBytes = 32L * 1024L * 1024L,
        type = WhisperModelType.TINY,
        descriptionAr = "test"
    )

    private fun sha256(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256")
        .digest(bytes).joinToString("") { "%02x".format(it) }
}
