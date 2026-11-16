package com.transvibe.app.presentation.player

import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.transvibe.app.domain.model.AppPreferences
import com.transvibe.app.domain.model.ProcessingSession
import com.transvibe.app.domain.model.ProcessingState
import com.transvibe.app.domain.model.SourceAccessState
import com.transvibe.app.domain.model.SubtitleSegment
import com.transvibe.app.domain.repository.PlayerGateway
import com.transvibe.app.domain.repository.PlayerExportResult
import com.transvibe.app.domain.repository.ProcessingRepository
import com.transvibe.app.domain.repository.SettingsRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.first
import java.util.concurrent.atomic.AtomicLong

class PlayerViewModel(
    processing: ProcessingRepository,
    private val settings: SettingsRepository,
    private val playerRepository: PlayerGateway
) : ViewModel() {
    val processingState: StateFlow<ProcessingState> = processing.currentState.stateIn(
        viewModelScope, SharingStarted.WhileSubscribed(5_000), ProcessingState.Idle
    )
    val preferences: StateFlow<AppPreferences> = settings.preferences.stateIn(
        viewModelScope, SharingStarted.WhileSubscribed(5_000), AppPreferences()
    )
    private val _sourceAccess = MutableStateFlow<SourceAccessState?>(null)
    val sourceAccess: StateFlow<SourceAccessState?> = _sourceAccess.asStateFlow()
    private val _exportResult = MutableStateFlow<PlayerExportResult?>(null)
    val exportResult: StateFlow<PlayerExportResult?> = _exportResult.asStateFlow()
    private val _subtitleWindow = MutableStateFlow<List<SubtitleSegment>>(emptyList())
    val subtitleWindow: StateFlow<List<SubtitleSegment>> = _subtitleWindow.asStateFlow()

    private val windowRequestId = AtomicLong(0L)
    @Volatile private var loadedSessionId: String? = null
    @Volatile private var windowFromMs: Long = Long.MAX_VALUE
    @Volatile private var windowToMs: Long = Long.MIN_VALUE

    fun checkSourceAccess(session: ProcessingSession) = viewModelScope.launch(Dispatchers.IO) {
        _sourceAccess.value = playerRepository.checkSourceAccess(session)
    }

    fun ensureSubtitleWindow(sessionId: String, positionMs: Long, force: Boolean = false) {
        val stillCovered = loadedSessionId == sessionId && positionMs in (windowFromMs + 5_000L)..(windowToMs - 10_000L)
        if (!force && stillCovered) return
        val from = (positionMs - 30_000L).coerceAtLeast(0L)
        val to = positionMs + 60_000L
        loadedSessionId = sessionId
        windowFromMs = from
        windowToMs = to
        val requestId = windowRequestId.incrementAndGet()
        viewModelScope.launch(Dispatchers.IO) {
            val result = playerRepository.loadSubtitleWindow(sessionId, positionMs)
            if (requestId == windowRequestId.get() && loadedSessionId == sessionId) {
                _subtitleWindow.value = result
            }
        }
    }

    fun updateSegment(sessionId: String, segment: SubtitleSegment, originalText: String, translatedText: String?) =
        viewModelScope.launch(Dispatchers.IO) {
            val updated = segment.copy(originalText = originalText.trim(), translatedText = translatedText?.trim())
            if (playerRepository.updateSegment(sessionId, updated)) {
                _subtitleWindow.value = _subtitleWindow.value.map { if (it.id == segment.id) updated else it }
            }
        }

    fun setSubtitleDisplayMode(value: String) = viewModelScope.launch { settings.setSubtitleDisplayMode(value) }
    fun setSubtitleTextScale(value: Float) = viewModelScope.launch { settings.setSubtitleTextScale(value) }
    fun setSubtitleBackgroundOpacity(value: Float) = viewModelScope.launch { settings.setSubtitleBackgroundOpacity(value) }
    fun setSubtitleBackgroundStyle(value: String) = viewModelScope.launch { settings.setSubtitleBackgroundStyle(value) }
    fun setSubtitleTextColor(value: String) = viewModelScope.launch { settings.setSubtitleTextColor(value) }
    fun setSubtitleVerticalPosition(value: Float) = viewModelScope.launch { settings.setSubtitleVerticalPosition(value) }
    fun setSubtitleAlignment(value: String) = viewModelScope.launch { settings.setSubtitleAlignment(value) }

    fun exportToSettingsFolder(session: ProcessingSession, webVtt: Boolean) = viewModelScope.launch(Dispatchers.IO) {
        val prefs = settings.preferences.first()
        _exportResult.value = playerRepository.exportToPreferredDestination(
            session = session,
            treeUri = prefs.subtitleOutputTreeUri,
            webVtt = webVtt
        )
    }

    fun setPreferredExportFormat(value: String) = viewModelScope.launch {
        settings.setPreferredExportFormat(value)
    }

    fun exportSrt(session: ProcessingSession, destination: Uri) = export(session, destination, false)
    fun exportWebVtt(session: ProcessingSession, destination: Uri) = export(session, destination, true)
    private fun export(session: ProcessingSession, destination: Uri, webVtt: Boolean) = viewModelScope.launch(Dispatchers.IO) {
        _exportResult.value = playerRepository.export(session, destination.toString(), webVtt)
    }
}
