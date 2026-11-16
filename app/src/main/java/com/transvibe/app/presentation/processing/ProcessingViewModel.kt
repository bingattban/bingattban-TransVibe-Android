package com.transvibe.app.presentation.processing

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.transvibe.app.domain.model.ProcessingStage
import com.transvibe.app.domain.model.ProcessingState
import com.transvibe.app.domain.model.SubtitleSegment
import com.transvibe.app.domain.repository.ProcessingRepository
import com.transvibe.app.domain.repository.ProcessingTaskGateway
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

class ProcessingViewModel(
    repository: ProcessingRepository,
    private val processingTasks: ProcessingTaskGateway
) : ViewModel() {
    val state: StateFlow<ProcessingState> = repository.currentState.stateIn(
        viewModelScope, SharingStarted.WhileSubscribed(5_000), ProcessingState.Idle
    )
    val stage: StateFlow<ProcessingStage> = repository.currentStage.stateIn(
        viewModelScope, SharingStarted.WhileSubscribed(5_000), ProcessingStage.NONE
    )
    val recentSegments: StateFlow<List<SubtitleSegment>> = repository.observeRecentSegmentsForCurrent(3).stateIn(
        viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList()
    )
    val canResume: StateFlow<Boolean> = repository.canResumeCurrent.stateIn(
        viewModelScope, SharingStarted.WhileSubscribed(5_000), false
    )
    private val _message = MutableStateFlow<String?>(null)
    val message: StateFlow<String?> = _message.asStateFlow()
    private val _cancelling = MutableStateFlow(false)
    val cancelling: StateFlow<Boolean> = _cancelling.asStateFlow()

    init {
        viewModelScope.launch {
            state.collect { processing ->
                if (processing is ProcessingState.Cancelled ||
                    processing is ProcessingState.Completed ||
                    processing is ProcessingState.Error
                ) _cancelling.value = false
            }
        }
    }

    fun cancel() {
        if (_cancelling.value) return
        _cancelling.value = true
        try {
            processingTasks.cancel()
        } catch (error: Exception) {
            _cancelling.value = false
            _message.value = error.localizedMessage ?: "Could not request cancellation"
        }
    }
    fun retry() = viewModelScope.launch {
        processingTasks.retry()
    }
    fun clearCurrent() = viewModelScope.launch { processingTasks.clearCurrent() }
    fun dismissMessage() { _message.value = null }
}
