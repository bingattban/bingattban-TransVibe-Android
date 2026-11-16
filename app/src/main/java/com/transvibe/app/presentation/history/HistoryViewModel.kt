package com.transvibe.app.presentation.history

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.transvibe.app.domain.model.ProcessingHistorySummary
import com.transvibe.app.domain.repository.ProcessingRepository
import com.transvibe.app.domain.repository.SessionRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

class HistoryViewModel(
    private val sessions: SessionRepository,
    private val processing: ProcessingRepository
) : ViewModel() {
    val history: StateFlow<List<ProcessingHistorySummary>> = sessions.observeHistory().stateIn(
        viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList()
    )
    private val _message = MutableStateFlow<String?>(null)
    val message: StateFlow<String?> = _message.asStateFlow()
    private val _selected = MutableStateFlow<Set<String>>(emptySet())
    val selected: StateFlow<Set<String>> = _selected.asStateFlow()

    fun openSession(jobId: String, onOpened: () -> Unit) = viewModelScope.launch(Dispatchers.IO) {
        if (sessions.getSession(jobId) == null) {
            _message.value = "تعذر تحميل الجلسة المحفوظة."
            return@launch
        }
        processing.selectCurrent(jobId)
        launch(Dispatchers.Main) { onOpened() }
    }

    fun toggleSelected(jobId: String) {
        _selected.value = _selected.value.toMutableSet().apply {
            if (!add(jobId)) remove(jobId)
        }
    }

    fun clearSelection() { _selected.value = emptySet() }

    fun selectAll() { _selected.value = history.value.mapTo(linkedSetOf()) { it.jobId } }

    fun deleteOne(jobId: String) = viewModelScope.launch(Dispatchers.IO) {
        if (processing.getCurrent()?.jobId == jobId) processing.clearCurrent()
        if (!sessions.deleteSession(jobId)) _message.value = "تعذر حذف العملية."
        _selected.value = _selected.value - jobId
    }

    fun deleteSelected() = viewModelScope.launch(Dispatchers.IO) {
        val ids = _selected.value
        if (ids.isEmpty()) return@launch
        val current = processing.getCurrent()?.jobId
        if (current != null && current in ids) processing.clearCurrent()
        sessions.deleteSessions(ids)
        _selected.value = emptySet()
    }

    fun dismissMessage() { _message.value = null }
}
