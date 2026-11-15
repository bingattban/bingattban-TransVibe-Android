package com.transvibe.app.presentation.models

import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.transvibe.app.domain.model.DownloadProgress
import com.transvibe.app.domain.model.LanguageCode
import com.transvibe.app.domain.model.WhisperModel
import com.transvibe.app.domain.repository.ModelRepository
import com.transvibe.app.domain.repository.TranslationPackRepository
import com.transvibe.app.domain.asr.FastAsrModelRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

class ModelsViewModel(
    private val repository: ModelRepository,
    private val translationPacks: TranslationPackRepository,
    private val fastAsrModels: FastAsrModelRepository
) : ViewModel() {
    val resources = repository.resourceSnapshot
    val startupState = repository.startupState
    val selectedModelId = repository.selectedModelId
    val models = repository.models
    val translationBusy = translationPacks.busy
    val installedTranslationPacks = translationPacks.installedPacks
    val translationPackStorage = translationPacks.storage
    val fastAsrModelsList = fastAsrModels.models
    val supportedTranslationLanguages: List<LanguageCode> = listOf(
        "af","ar","be","bg","bn","ca","cs","cy","da","de","el","en","eo","es","et","fa","fi","fr",
        "ga","gl","gu","he","hi","hr","ht","hu","id","is","it","ja","ka","kn","ko","lt","lv","mk",
        "mr","ms","mt","nl","no","pl","pt","ro","ru","sk","sl","sq","sv","sw","ta","te","th","tl",
        "tr","uk","ur","vi","zh"
    ).map(LanguageCode::of)
    private val _message = MutableStateFlow<String?>(null)
    val message: StateFlow<String?> = _message.asStateFlow()

    fun selectModel(id: String) = repository.selectModel(id)
    fun downloadState(model: WhisperModel): StateFlow<DownloadProgress> = repository.downloadState(model)
    fun download(model: WhisperModel) = repository.download(model)
    fun retryDownload(model: WhisperModel) = repository.retry(model)
    fun importModel(model: WhisperModel, uri: Uri) = repository.importModel(model, uri.toString())
    fun exportModel(model: WhisperModel, uri: Uri) = viewModelScope.launch {
        repository.exportModel(model, uri.toString())
            .onSuccess { _message.value = "تم تصدير النموذج بنجاح." }
            .onFailure { _message.value = it.message ?: "تعذر تصدير النموذج." }
    }
    fun importUserModel(uri: Uri) = repository.importUserModel(uri.toString())
    fun pause(model: WhisperModel) = repository.pause(model)
    fun cancelDownload(model: WhisperModel) = repository.cancel(model)
    fun delete(model: WhisperModel) = repository.delete(model)
    fun translationStatus(language: LanguageCode) = translationPacks.status(language)
    fun fastAsrState(modelId: String) = fastAsrModels.state(modelId)
    fun downloadFastAsrModel(modelId: String) = fastAsrModels.download(modelId)
    fun importFastAsrModel(modelId: String, uri: Uri) = fastAsrModels.importModel(modelId, uri.toString())
    fun exportFastAsrModel(modelId: String, uri: Uri) = fastAsrModels.exportModel(modelId, uri.toString())
    fun deleteFastAsrModel(modelId: String) = fastAsrModels.delete(modelId)
    fun refreshFastAsrModels() = fastAsrModels.refresh()

    fun refreshResources() = viewModelScope.launch {
        repository.refreshAll()
        translationPacks.refreshInstalled()
        fastAsrModels.refresh()
    }

    fun refreshTranslationPacks() = viewModelScope.launch {
        translationPacks.refreshInstalled()
    }

    fun downloadTranslationPack(language: LanguageCode) = viewModelScope.launch {
        translationPacks.download(language)
    }

    fun removeTranslationPack(language: LanguageCode) = viewModelScope.launch {
        translationPacks.remove(language)
    }

    fun dismissMessage() { _message.value = null }
}
