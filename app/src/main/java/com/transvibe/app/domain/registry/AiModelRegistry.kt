package com.transvibe.app.domain.registry

import com.transvibe.app.domain.model.WhisperModel
import kotlinx.coroutines.flow.StateFlow

interface AiModelRegistry {
    val models: StateFlow<List<WhisperModel>>
    val officialModels: List<WhisperModel>
    val importedModels: StateFlow<List<WhisperModel>>
    val recommended: WhisperModel

    fun byId(id: String): WhisperModel?
    fun require(id: String): WhisperModel = byId(id) ?: error("Unknown model: $id")
    fun registerImported(model: WhisperModel)
    fun removeImported(modelId: String)
}
