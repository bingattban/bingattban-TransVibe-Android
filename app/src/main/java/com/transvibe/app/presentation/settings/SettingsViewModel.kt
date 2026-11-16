package com.transvibe.app.presentation.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.transvibe.app.domain.model.AppPreferences
import com.transvibe.app.domain.model.StorageInfo
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

class SettingsViewModel(
    private val settings: com.transvibe.app.domain.repository.SettingsRepository,
    private val models: com.transvibe.app.domain.repository.ModelRepository,
    private val fastAsrModels: com.transvibe.app.domain.asr.FastAsrModelRepository,
    private val storage: com.transvibe.app.domain.repository.StorageInspector,
    val appVersion: String
) : ViewModel() {
    val preferences: StateFlow<AppPreferences> = settings.preferences.stateIn(
        viewModelScope, SharingStarted.WhileSubscribed(5_000), AppPreferences()
    )
    val selectedModelId = models.selectedModelId
    val modelRegistry = models.models
    val modelResources = models.resourceSnapshot
    val availableFastAsrModels: StateFlow<List<com.transvibe.app.domain.asr.FastAsrModelInfo>> =
        combine(fastAsrModels.models.map { info -> fastAsrModels.state(info.id) }) { states ->
            fastAsrModels.models.filterIndexed { index, _ ->
                states[index].status == com.transvibe.app.domain.model.DownloadProgress.Status.INSTALLED
            }
        }.stateIn(
            viewModelScope,
            SharingStarted.WhileSubscribed(5_000),
            fastAsrModels.models.filter { fastAsrModels.isInstalled(it.id) }
        )
    private val _storageInfo = MutableStateFlow(StorageInfo())
    val storageInfo: StateFlow<StorageInfo> = _storageInfo.asStateFlow()

    fun selectModel(id: String) = models.selectModel(id)
    fun setDefaultProcessingModel(id: String) = viewModelScope.launch { settings.setDefaultProcessingModelId(id) }
    fun setKeepScreenOnDuringPlayback(v: Boolean) = viewModelScope.launch { settings.setKeepScreenOnDuringPlayback(v) }
    fun setPreferredExportFormat(v: String) = viewModelScope.launch { settings.setPreferredExportFormat(v) }
    fun setProcessingProfile(v: String) = viewModelScope.launch { settings.setProcessingProfile(v) }
    fun setApplicationLanguage(v: String) = viewModelScope.launch { settings.setApplicationLanguage(v) }
    fun setInterfaceTextScale(v: Float) = viewModelScope.launch { settings.setInterfaceTextScale(v) }
    fun setWifiOnlyTranslationDownloads(v: Boolean) = viewModelScope.launch { settings.setWifiOnlyTranslationDownloads(v) }
    fun setSubtitleTextScale(v: Float) = viewModelScope.launch { settings.setSubtitleTextScale(v) }
    fun setSubtitleDisplayMode(v: String) = viewModelScope.launch { settings.setSubtitleDisplayMode(v) }
    fun setSubtitleBackgroundOpacity(v: Float) = viewModelScope.launch { settings.setSubtitleBackgroundOpacity(v) }
    fun setSubtitleBackgroundStyle(v: String) = viewModelScope.launch { settings.setSubtitleBackgroundStyle(v) }
    fun setSubtitleTextColor(v: String) = viewModelScope.launch { settings.setSubtitleTextColor(v) }
    fun setSubtitleVerticalPosition(v: Float) = viewModelScope.launch { settings.setSubtitleVerticalPosition(v) }
    fun setSubtitleAlignment(v: String) = viewModelScope.launch { settings.setSubtitleAlignment(v) }
    fun setThemeMode(v: String) = viewModelScope.launch { settings.setThemeMode(v) }
    fun setSubtitleOutputTreeUri(v: String?) = viewModelScope.launch { settings.setSubtitleOutputTreeUri(v) }
    fun refreshStorageInfo() = viewModelScope.launch(Dispatchers.IO) { _storageInfo.value = storage.inspect() }
}
