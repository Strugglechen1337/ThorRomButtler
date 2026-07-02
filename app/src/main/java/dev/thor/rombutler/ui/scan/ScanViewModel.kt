package dev.thor.rombutler.ui.scan

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dev.thor.rombutler.domain.model.ArchiveAnalysis
import dev.thor.rombutler.domain.model.RomArchive
import dev.thor.rombutler.domain.repository.ArchiveAnalyzer
import dev.thor.rombutler.domain.repository.ArchiveRepository
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * One archive in the list plus its analysis progress.
 *
 * @property analysis `null` while the analysis is still running.
 */
data class ArchiveListItem(
    val archive: RomArchive,
    val analysis: ArchiveAnalysis? = null,
)

/**
 * UI state of the scan screen.
 */
sealed interface ScanUiState {
    /** Directory scan in progress. */
    data object Scanning : ScanUiState

    /** Scan finished without finding any archives. */
    data object Empty : ScanUiState

    /** Archives found; analyses stream in one by one. */
    data class Found(val items: List<ArchiveListItem>) : ScanUiState
}

@HiltViewModel
class ScanViewModel @Inject constructor(
    private val archiveRepository: ArchiveRepository,
    private val archiveAnalyzer: ArchiveAnalyzer,
) : ViewModel() {

    private val _uiState = MutableStateFlow<ScanUiState>(ScanUiState.Scanning)
    val uiState: StateFlow<ScanUiState> = _uiState.asStateFlow()

    private var scanJob: Job? = null

    init {
        rescan()
    }

    /** Scans the download folder, then analyzes each archive sequentially. */
    fun rescan() {
        scanJob?.cancel()
        _uiState.value = ScanUiState.Scanning
        scanJob = viewModelScope.launch {
            val archives = archiveRepository.scanForArchives()
            if (archives.isEmpty()) {
                _uiState.value = ScanUiState.Empty
                return@launch
            }
            _uiState.value = ScanUiState.Found(archives.map { ArchiveListItem(it) })

            // Sequential on purpose: archive I/O is disk-bound, and cards
            // filling in one after another reads as pleasant progress.
            for (archive in archives) {
                val analysis = archiveAnalyzer.analyze(archive)
                _uiState.update { state ->
                    if (state !is ScanUiState.Found) return@update state
                    state.copy(
                        items = state.items.map { item ->
                            if (item.archive.path == archive.path) {
                                item.copy(analysis = analysis)
                            } else {
                                item
                            }
                        },
                    )
                }
            }
        }
    }
}
