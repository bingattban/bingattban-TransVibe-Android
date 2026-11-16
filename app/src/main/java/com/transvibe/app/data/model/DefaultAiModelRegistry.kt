package com.transvibe.app.data.model

import android.content.Context
import com.transvibe.app.domain.model.ModelCapability
import com.transvibe.app.domain.model.ModelOrigin
import com.transvibe.app.domain.model.ModelPerformanceTier
import com.transvibe.app.domain.model.NativeValidationState
import com.transvibe.app.domain.model.OfficialWhisperModels
import com.transvibe.app.domain.model.WhisperModel
import com.transvibe.app.domain.model.WhisperModelType
import com.transvibe.app.domain.registry.AiModelRegistry
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * Single source of truth for all AI model descriptors.
 * Official descriptors are compile-time trusted metadata; imported descriptors are persisted locally
 * and their SHA-256 is only a local integrity fingerprint, never publisher-authenticity evidence.
 */
class DefaultAiModelRegistry(context: Context) : AiModelRegistry {
    private val registryDir = File(context.filesDir, "models/registry").apply { mkdirs() }
    private val importedFile = File(registryDir, "imported_whisper_models.json")
    override val officialModels: List<WhisperModel> = OfficialWhisperModels.whisperModels
    private val _importedModels = MutableStateFlow(loadImported())
    override val importedModels: StateFlow<List<WhisperModel>> = _importedModels.asStateFlow()
    private val _models = MutableStateFlow(officialModels + _importedModels.value)
    override val models: StateFlow<List<WhisperModel>> = _models.asStateFlow()
    override val recommended: WhisperModel = officialModels.first { it.recommended }

    override fun byId(id: String): WhisperModel? = _models.value.firstOrNull { it.id == id }

    @Synchronized
    override fun registerImported(model: WhisperModel) {
        require(model.origin == ModelOrigin.USER_IMPORTED)
        require(model.nativeValidationState == NativeValidationState.VALID)
        require(officialModels.none { it.id == model.id }) { "Imported model ID collides with official model" }
        val next = _importedModels.value.filterNot { it.id == model.id || it.fileName == model.fileName } + model
        persist(next)
        _importedModels.value = next
        _models.value = officialModels + next
    }

    @Synchronized
    override fun removeImported(modelId: String) {
        val next = _importedModels.value.filterNot { it.id == modelId }
        persist(next)
        _importedModels.value = next
        _models.value = officialModels + next
    }

    private fun loadImported(): List<WhisperModel> = runCatching {
        if (!importedFile.isFile) return@runCatching emptyList()
        val root = JSONArray(importedFile.readText())
        buildList {
            for (i in 0 until root.length()) {
                val o = root.getJSONObject(i)
                val capabilities = buildSet {
                    val a = o.optJSONArray("capabilities") ?: JSONArray()
                    for (j in 0 until a.length()) {
                        runCatching { ModelCapability.valueOf(a.getString(j)) }.getOrNull()?.let(::add)
                    }
                }
                add(
                    WhisperModel(
                        id = o.getString("id"),
                        displayName = o.getString("displayName"),
                        fileName = o.getString("fileName"),
                        downloadUrl = null,
                        sha256 = o.getString("calculatedFingerprint"),
                        fileSizeBytes = o.getLong("fileSizeBytes"),
                        estimatedRamBytes = o.optLong("minimumRamBytes", 0L).takeIf { it > 0L },
                        type = WhisperModelType.CUSTOM,
                        descriptionAr = o.optString("descriptionAr", "نموذج Whisper مستورد من المستخدم وتم التحقق منه محلياً."),
                        recommended = false,
                        englishOnly = o.optBoolean("englishOnly", false),
                        variant = o.optString("variant", "custom"),
                        capabilities = capabilities.ifEmpty {
                            setOf(
                                ModelCapability.MULTILINGUAL_RECOGNITION,
                                ModelCapability.ENGLISH_RECOGNITION,
                                ModelCapability.SPEECH_TO_ENGLISH_TRANSLATION,
                                ModelCapability.LANGUAGE_DETECTION,
                                ModelCapability.PROMPT_CONTEXT
                            )
                        },
                        performanceTier = runCatching { ModelPerformanceTier.valueOf(o.optString("performanceTier")) }
                            .getOrDefault(ModelPerformanceTier.VERY_HEAVY),
                        origin = ModelOrigin.USER_IMPORTED,
                        importTimestampEpochMs = o.getLong("importTimestampEpochMs"),
                        nativeValidationState = runCatching { NativeValidationState.valueOf(o.optString("nativeValidationState")) }
                            .getOrDefault(NativeValidationState.VALID)
                    )
                )
            }
        }
    }.getOrElse { emptyList() }

    private fun persist(models: List<WhisperModel>) {
        val array = JSONArray()
        models.forEach { model ->
            array.put(JSONObject().apply {
                put("schemaVersion", 1)
                put("id", model.id)
                put("displayName", model.displayName)
                put("fileName", model.fileName)
                put("fileSizeBytes", model.fileSizeBytes)
                put("calculatedFingerprint", model.sha256)
                put("importTimestampEpochMs", model.importTimestampEpochMs ?: System.currentTimeMillis())
                put("nativeValidationState", model.nativeValidationState.name)
                put("variant", model.variant)
                put("englishOnly", model.englishOnly)
                put("minimumRamBytes", model.minimumRamBytes ?: JSONObject.NULL)
                put("performanceTier", model.performanceTier.name)
                put("capabilities", JSONArray(model.capabilities.map { it.name }))
                put("descriptionAr", model.descriptionAr)
                put("publisherAuthenticityVerified", false)
            })
        }
        val tmp = File(registryDir, importedFile.name + ".tmp")
        tmp.writeText(array.toString())
        if (!tmp.renameTo(importedFile)) {
            importedFile.writeText(tmp.readText())
            tmp.delete()
        }
    }
}
