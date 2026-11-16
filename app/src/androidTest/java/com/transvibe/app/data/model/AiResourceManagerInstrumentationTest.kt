package com.transvibe.app.data.model

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import com.transvibe.app.domain.model.AiResourceState
import com.transvibe.app.domain.model.DownloadPolicy
import com.transvibe.app.domain.model.LanguageCode
import com.transvibe.app.domain.model.ProcessingResourceRequest
import com.transvibe.app.domain.model.WhisperModel
import com.transvibe.app.domain.model.WhisperModelType
import com.transvibe.app.domain.registry.AiModelRegistry
import com.transvibe.app.domain.repository.WhisperModelBinaryValidator
import com.transvibe.app.translation.TranslationEngine
import com.transvibe.app.translation.TranslationEngineFactory
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import java.security.MessageDigest
import kotlin.coroutines.resume

@RunWith(AndroidJUnit4::class)
class AiResourceManagerInstrumentationTest {
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    private val bytes = ByteArray(4096) { (it % 251).toByte() }
    private lateinit var scope: CoroutineScope

    @Before
    fun setUp() {
        instrumentation.context.getSharedPreferences("ai_resources", 0).edit().clear().commit()
        instrumentation.context.filesDir.resolve("models").deleteRecursively()
        scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    }

    @After
    fun tearDown() {
        scope.cancel()
        instrumentation.context.filesDir.resolve("models").deleteRecursively()
    }

    @Test
    fun testSelectedModelReadiness() = runBlocking {
        val model = model("ready")
        val store = store(true)
        store.file(model).apply { parentFile?.mkdirs(); writeBytes(bytes) }
        val manager = manager(store, model, translationReady = true)
        val state = manager.checkPipeline(model, ProcessingResourceRequest("en", "en", true))
        assertEquals(AiResourceState.Ready, state)
    }

    @Test
    fun testMissingModel() = runBlocking {
        val model = model("missing")
        val manager = manager(store(true), model, translationReady = true)
        val state = manager.checkPipeline(model, ProcessingResourceRequest("en", "en", true))
        assertTrue(state is AiResourceState.MissingWhisperModel || state is AiResourceState.InsufficientStorage)
    }

    @Test
    fun testMissingTranslationPack() = runBlocking {
        val model = model("missing-translation")
        val store = store(true)
        store.file(model).apply { parentFile?.mkdirs(); writeBytes(bytes) }
        val manager = manager(store, model, translationReady = false)
        val state = manager.checkPipeline(model, ProcessingResourceRequest("en", "ar", true))
        assertEquals(AiResourceState.MissingTranslationModel, state)
    }

    @Test
    fun testCancellationPropagatesFromModelValidation() = runBlocking {
        val model = model("cancel")
        val validator = object : WhisperModelBinaryValidator {
            override suspend fun validate(candidatePath: String): Boolean = suspendCancellableCoroutine { }
        }
        val store = ModelStore(instrumentation.context, validator)
        store.file(model).apply { parentFile?.mkdirs(); writeBytes(bytes) }
        val manager = manager(store, model, translationReady = true)
        val job = launch { manager.checkPipeline(model, ProcessingResourceRequest("en", "en", true)) }
        kotlinx.coroutines.delay(50)
        job.cancel()
        try {
            job.join()
            assertTrue(job.isCancelled)
        } catch (_: CancellationException) { Unit }
    }

    @Test
    fun testStateRefreshDetectsCorruptedModel() = runBlocking {
        val model = model("corrupted")
        val store = store(true)
        store.file(model).apply { parentFile?.mkdirs(); writeBytes(ByteArray(bytes.size) { 9 }) }
        val manager = manager(store, model, translationReady = true)
        assertEquals(com.transvibe.app.domain.model.ModelIntegrityState.BINARY_CORRUPTED, manager.refreshModel(model))
    }

    private fun manager(store: ModelStore, model: WhisperModel, translationReady: Boolean) = AiResourceManager(
        instrumentation.context,
        store,
        FakeRegistry(model),
        FakeTranslationFactory(translationReady),
        scope
    )

    private fun store(valid: Boolean) = ModelStore(instrumentation.context, object : WhisperModelBinaryValidator {
        override suspend fun validate(candidatePath: String): Boolean = valid
    })

    private fun model(id: String) = WhisperModel(
        id = "resource-$id",
        displayName = id,
        fileName = "resource-$id.bin",
        downloadUrl = "https://example.com/$id.bin",
        sha256 = sha256(bytes),
        fileSizeBytes = bytes.size.toLong(),
        estimatedRamBytes = 32L * 1024 * 1024,
        type = WhisperModelType.TINY,
        descriptionAr = "test"
    )

    private fun sha256(value: ByteArray) = MessageDigest.getInstance("SHA-256").digest(value).joinToString("") { "%02x".format(it) }

    private class FakeRegistry(private val model: WhisperModel) : AiModelRegistry {
        private val all = MutableStateFlow(listOf(model))
        override val models: StateFlow<List<WhisperModel>> = all
        override val officialModels: List<WhisperModel> = listOf(model)
        override val importedModels: StateFlow<List<WhisperModel>> = MutableStateFlow(emptyList())
        override val recommended: WhisperModel = model
        override fun byId(id: String): WhisperModel? = model.takeIf { it.id == id }
        override fun registerImported(model: WhisperModel) { all.value = all.value + model }
        override fun removeImported(modelId: String) { all.value = all.value.filterNot { it.id == modelId } }
    }

    private class FakeTranslationFactory(private val ready: Boolean) : TranslationEngineFactory {
        override fun create(sourceLanguage: LanguageCode, targetLanguage: LanguageCode): TranslationEngine = object : TranslationEngine {
            override val sourceLanguage = sourceLanguage
            override val targetLanguage = targetLanguage
            override suspend fun isReady(): Boolean = ready
            override suspend fun prepare(policy: DownloadPolicy) = Unit
            override suspend fun translate(text: String): String = text
            override fun close() = Unit
        }
    }
}
