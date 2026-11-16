package com.transvibe.app.data.model

import android.net.Uri
import com.transvibe.app.data.download.ModelDownloadManager
import com.transvibe.app.data.error.ProcessingErrorMapper
import com.transvibe.app.data.logging.AppLogger
import com.transvibe.app.domain.model.AiResourceSnapshot
import com.transvibe.app.domain.model.DownloadProgress
import com.transvibe.app.domain.model.StartupState
import com.transvibe.app.domain.model.WhisperModel
import com.transvibe.app.domain.model.ModelOrigin
import com.transvibe.app.domain.registry.AiModelRegistry
import com.transvibe.app.domain.repository.ModelRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

class ModelManagementRepository(
    private val resources: AiResourceManager,
    private val downloads: ModelDownloadManager,
    private val registry: AiModelRegistry,
    private val userImporter: UserWhisperModelImporter,
    private val scope: kotlinx.coroutines.CoroutineScope
) : ModelRepository {
    override val resourceSnapshot: StateFlow<AiResourceSnapshot> = resources.snapshot
    override val startupState: StateFlow<StartupState> = resources.startupState
    private val _selectedModelId = MutableStateFlow(resources.selectedModelId)
    override val selectedModelId: StateFlow<String> = _selectedModelId.asStateFlow()
    override val models: StateFlow<List<WhisperModel>> = registry.models

    override fun selectModel(id: String) {
        resources.setSelectedModel(id)
        _selectedModelId.value = id
    }

    override fun selectedModel(): WhisperModel = registry.byId(_selectedModelId.value) ?: registry.recommended
    override fun downloadState(model: WhisperModel): StateFlow<DownloadProgress> = downloads.state(model)
    override fun download(model: WhisperModel) = downloads.download(model)
    override fun retry(model: WhisperModel) = downloads.retry(model)
    override fun importModel(model: WhisperModel, sourceUri: String) = downloads.importModel(model, Uri.parse(sourceUri))
    override suspend fun exportModel(model: WhisperModel, destinationUri: String): Result<Unit> = runCatching { downloads.exportModel(model, Uri.parse(destinationUri)) }
    override fun importUserModel(sourceUri: String, displayName: String?) {
        scope.launch {
            try {
                val imported = userImporter.import(Uri.parse(sourceUri), displayName)
                resources.refreshModel(imported)
            } catch (ce: kotlinx.coroutines.CancellationException) {
                throw ce
            } catch (t: Throwable) {
                AppLogger.error("UserModelImport", ProcessingErrorMapper.fromDownloadThrowable(t), "user_model_import_failed")
            }
        }
    }
    override fun pause(model: WhisperModel) = downloads.pause(model)
    override fun cancel(model: WhisperModel) = downloads.cancel(model)
    override fun delete(model: WhisperModel) {
        downloads.delete(model)
        if (model.origin == ModelOrigin.USER_IMPORTED) registry.removeImported(model.id)
    }
    override suspend fun refreshAll() = resources.refreshAll()
}
