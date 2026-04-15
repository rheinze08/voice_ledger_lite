package com.voiceledger.lite.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.voiceledger.lite.data.LedgerRepository
import com.voiceledger.lite.data.LocalStats
import com.voiceledger.lite.data.NoteEntity
import com.voiceledger.lite.data.OllamaSettings
import com.voiceledger.lite.data.SettingsStore
import com.voiceledger.lite.ollama.InsightSnapshot
import com.voiceledger.lite.ollama.OllamaClient
import java.time.Duration
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

enum class AppTab {
    NOTES,
    COMPOSE,
    INSIGHTS,
    SETTINGS,
}

data class LedgerUiState(
    val selectedTab: AppTab = AppTab.NOTES,
    val notes: List<NoteEntity> = emptyList(),
    val selectedNoteId: Long? = null,
    val editingNoteId: Long? = null,
    val composeTitle: String = "",
    val composeBody: String = "",
    val settings: OllamaSettings = OllamaSettings(),
    val localStats: LocalStats = LocalStats(),
    val latestInsight: InsightSnapshot? = null,
    val isRefreshingInsights: Boolean = false,
    val infoMessage: String? = null,
    val errorMessage: String? = null,
)

class LedgerViewModel(
    private val repository: LedgerRepository,
    private val settingsStore: SettingsStore,
    private val ollamaClient: OllamaClient,
) : ViewModel() {
    private val _uiState = MutableStateFlow(LedgerUiState(settings = settingsStore.load()))
    val uiState: StateFlow<LedgerUiState> = _uiState.asStateFlow()

    init {
        viewModelScope.launch {
            repository.observeNotes().collect { notes ->
                _uiState.update { state ->
                    val selectedStillExists = notes.any { it.id == state.selectedNoteId }
                    state.copy(
                        notes = notes,
                        localStats = calculateStats(notes),
                        selectedNoteId = if (selectedStillExists) state.selectedNoteId else notes.firstOrNull()?.id,
                    )
                }
            }
        }
        viewModelScope.launch {
            repository.observeInsight(LedgerRepository.RECENT_INSIGHT_KIND).collect { insight ->
                _uiState.update { it.copy(latestInsight = insight) }
            }
        }
    }

    fun selectTab(tab: AppTab) {
        _uiState.update { it.copy(selectedTab = tab) }
    }

    fun selectNote(noteId: Long?) {
        _uiState.update { it.copy(selectedNoteId = noteId, selectedTab = AppTab.NOTES) }
    }

    fun updateComposeTitle(value: String) {
        _uiState.update { it.copy(composeTitle = value) }
    }

    fun updateComposeBody(value: String) {
        _uiState.update { it.copy(composeBody = value) }
    }

    fun loadNoteIntoComposer(note: NoteEntity) {
        _uiState.update {
            it.copy(
                selectedTab = AppTab.COMPOSE,
                editingNoteId = note.id,
                composeTitle = note.title,
                composeBody = note.body,
                selectedNoteId = note.id,
            )
        }
    }

    fun clearComposer() {
        _uiState.update {
            it.copy(
                editingNoteId = null,
                composeTitle = "",
                composeBody = "",
            )
        }
    }

    fun saveDraft() {
        val current = _uiState.value
        val body = current.composeBody.trim()
        if (body.isBlank()) {
            _uiState.update { it.copy(errorMessage = "Body is required before you save a note.") }
            return
        }
        val title = current.composeTitle.trim().ifBlank {
            body.lineSequence().firstOrNull()?.take(48) ?: "Untitled note"
        }

        viewModelScope.launch {
            val savedId = repository.saveNote(current.editingNoteId, title, body)
            _uiState.update {
                it.copy(
                    selectedTab = AppTab.NOTES,
                    selectedNoteId = savedId,
                    editingNoteId = null,
                    composeTitle = "",
                    composeBody = "",
                    infoMessage = if (current.editingNoteId == null) {
                        "Note saved locally."
                    } else {
                        "Note updated."
                    },
                )
            }
        }
    }

    fun deleteNote(noteId: Long) {
        viewModelScope.launch {
            repository.deleteNote(noteId)
            _uiState.update {
                it.copy(
                    selectedNoteId = it.notes.firstOrNull { note -> note.id != noteId }?.id,
                    infoMessage = "Note deleted.",
                )
            }
        }
    }

    fun updateBaseUrl(value: String) {
        _uiState.update { it.copy(settings = it.settings.copy(baseUrl = value)) }
    }

    fun updateModel(value: String) {
        _uiState.update { it.copy(settings = it.settings.copy(model = value)) }
    }

    fun updateWindowDays(value: String) {
        _uiState.update {
            it.copy(
                settings = it.settings.copy(
                    windowDays = value.toIntOrNull() ?: it.settings.windowDays,
                ),
            )
        }
    }

    fun updateNoteLimit(value: String) {
        _uiState.update {
            it.copy(
                settings = it.settings.copy(
                    noteLimit = value.toIntOrNull() ?: it.settings.noteLimit,
                ),
            )
        }
    }

    fun updateTimeoutMs(value: String) {
        _uiState.update {
            it.copy(
                settings = it.settings.copy(
                    timeoutMs = value.toIntOrNull() ?: it.settings.timeoutMs,
                ),
            )
        }
    }

    fun saveSettings() {
        val normalized = _uiState.value.settings.normalized()
        settingsStore.save(normalized)
        _uiState.update {
            it.copy(
                settings = normalized,
                infoMessage = "Ollama settings saved.",
            )
        }
    }

    fun refreshInsights() {
        if (_uiState.value.isRefreshingInsights) {
            return
        }
        viewModelScope.launch {
            val settings = _uiState.value.settings.normalized()
            _uiState.update {
                it.copy(
                    settings = settings,
                    isRefreshingInsights = true,
                    errorMessage = null,
                )
            }
            try {
                val notes = repository.recentNotes(settings.windowDays, settings.noteLimit)
                if (notes.isEmpty()) {
                    _uiState.update {
                        it.copy(
                            isRefreshingInsights = false,
                            errorMessage = "Create a few notes before asking Gemma for an aggregate view.",
                        )
                    }
                    return@launch
                }
                val insight = ollamaClient.generateInsight(notes, settings)
                repository.saveInsight(LedgerRepository.RECENT_INSIGHT_KIND, settings, notes, insight)
                _uiState.update {
                    it.copy(
                        isRefreshingInsights = false,
                        infoMessage = "Gemma refreshed the semantic rollup.",
                    )
                }
            } catch (exc: Exception) {
                _uiState.update {
                    it.copy(
                        isRefreshingInsights = false,
                        errorMessage = exc.message ?: "Gemma refresh failed.",
                    )
                }
            }
        }
    }

    fun clearInfoMessage() {
        _uiState.update { it.copy(infoMessage = null) }
    }

    fun clearErrorMessage() {
        _uiState.update { it.copy(errorMessage = null) }
    }

    private fun calculateStats(notes: List<NoteEntity>): LocalStats {
        val now = System.currentTimeMillis()
        val weekAgo = now - Duration.ofDays(7).toMillis()
        val monthAgo = now - Duration.ofDays(30).toMillis()
        return LocalStats(
            totalNotes = notes.size,
            notesThisWeek = notes.count { it.createdAtEpochMs >= weekAgo },
            notesThisMonth = notes.count { it.createdAtEpochMs >= monthAgo },
        )
    }
}
