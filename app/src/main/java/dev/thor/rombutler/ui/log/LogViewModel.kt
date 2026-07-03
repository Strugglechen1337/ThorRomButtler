package dev.thor.rombutler.ui.log

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dev.thor.rombutler.data.files.UndoManager
import dev.thor.rombutler.domain.model.LogEntry
import dev.thor.rombutler.domain.model.LogLevel
import dev.thor.rombutler.domain.repository.LogRepository
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class LogViewModel @Inject constructor(
    private val logRepository: LogRepository,
    private val undoManager: UndoManager,
) : ViewModel() {

    /**
     * Reverts a sort action: extracted files are deleted (archive still
     * present), moved files go back. Outcome is logged either way.
     */
    fun undo(entry: LogEntry) {
        val info = entry.undo ?: return
        if (entry.undone) return
        viewModelScope.launch {
            undoManager.undo(info)
                .onSuccess {
                    logRepository.markUndone(entry)
                    logRepository.append(LogLevel.INFO, "Rückgängig gemacht: ${entry.message}")
                }
                .onFailure { error ->
                    logRepository.append(
                        LogLevel.ERROR,
                        "Rückgängig fehlgeschlagen: ${error.message ?: "Unbekannter Fehler"}",
                    )
                }
        }
    }

    /** Log entries, newest first. */
    val entries: StateFlow<List<LogEntry>> = logRepository.entries
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5_000),
            initialValue = emptyList(),
        )

    fun clearLog() {
        viewModelScope.launch { logRepository.clear() }
    }
}
