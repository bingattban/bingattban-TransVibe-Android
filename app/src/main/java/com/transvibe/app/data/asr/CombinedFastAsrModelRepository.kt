package com.transvibe.app.data.asr

import com.transvibe.app.domain.asr.FastAsrModelInfo
import com.transvibe.app.domain.asr.FastAsrModelRepository
import com.transvibe.app.domain.asr.SingleFastAsrModelRepository
import com.transvibe.app.domain.model.DownloadProgress
import kotlinx.coroutines.flow.StateFlow

class CombinedFastAsrModelRepository(
    repositories: List<SingleFastAsrModelRepository>
) : FastAsrModelRepository {
    private val byId = repositories.associateBy { it.model.id }
    override val models: List<FastAsrModelInfo> = repositories.map { it.model }

    private fun repo(id: String): SingleFastAsrModelRepository =
        byId[id] ?: error("Unknown fast ASR model: $id")

    override fun state(modelId: String): StateFlow<DownloadProgress> = repo(modelId).state
    override fun isInstalled(modelId: String): Boolean = byId[modelId]?.isInstalled() == true
    override fun download(modelId: String) = repo(modelId).download()
    override fun importModel(modelId: String, sourceUri: String) = repo(modelId).importModel(sourceUri)
    override fun exportModel(modelId: String, destinationUri: String) = repo(modelId).exportModel(destinationUri)
    override fun delete(modelId: String) = repo(modelId).delete()
    override fun refresh(modelId: String?) {
        if (modelId == null) byId.values.forEach { it.refresh() } else repo(modelId).refresh()
    }
}
