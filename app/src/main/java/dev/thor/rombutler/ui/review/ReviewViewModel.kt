package dev.thor.rombutler.ui.review

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dev.thor.rombutler.domain.detection.SystemRegistry
import dev.thor.rombutler.domain.model.Confidence
import dev.thor.rombutler.domain.model.DetectedRom
import dev.thor.rombutler.domain.model.SystemDefinition
import dev.thor.rombutler.domain.model.LogLevel
import dev.thor.rombutler.domain.repository.ArchiveMover
import dev.thor.rombutler.domain.repository.LogRepository
import dev.thor.rombutler.domain.repository.RomFolderRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * One ROM (group) awaiting user review.
 *
 * @property id stable list key (archive path + primary file name).
 * @property archiveFileName archive the ROM lives in.
 * @property archivePath absolute path of that archive.
 * @property rom detection result from the analysis.
 * @property selectedSystemId user-confirmed target system. Prefilled ONLY
 *   for CERTAIN detections — PROBABLE suggestions must be tapped by the
 *   user, UNKNOWN requires a manual pick. The app never decides on doubt.
 * @property targetPath absolute target folder once a system is selected.
 * @property targetExists whether that folder already exists.
 */
data class ReviewItem(
    val id: String,
    val archiveFileName: String,
    val archivePath: String,
    val rom: DetectedRom,
    val selectedSystemId: String? = null,
    val targetPath: String? = null,
    val targetExists: Boolean? = null,
)

/**
 * One-shot feedback after a folder-creation run (formatted by the UI).
 */
data class FolderCreationResult(val created: Int, val failed: Int)

/**
 * One-shot feedback after a move run (formatted by the UI).
 */
data class MoveSummary(val moved: Int, val failed: Int)

/**
 * UI state of the review screen.
 *
 * @property items all ROMs of the current scan session.
 * @property creatingFolders folder creation in progress.
 * @property folderResult one-shot feedback after creating folders.
 * @property moving move run in progress.
 * @property moveSummary one-shot feedback after moving (UI navigates to log).
 */
data class ReviewUiState(
    val items: List<ReviewItem> = emptyList(),
    val creatingFolders: Boolean = false,
    val folderResult: FolderCreationResult? = null,
    val moving: Boolean = false,
    val moveSummary: MoveSummary? = null,
) {
    val assignedCount: Int get() = items.count { it.selectedSystemId != null }
    val missingFolderCount: Int get() = items.count { it.selectedSystemId != null && it.targetExists == false }

    private val itemsByArchive: Map<String, List<ReviewItem>>
        get() = items.groupBy { it.archivePath }

    /**
     * Archives ready to move: EVERY ROM group inside is assigned and all
     * point to the SAME system. Archives are moved as a whole (v0.1 does
     * not extract), so mixed assignments must stay blocked.
     */
    val movableArchivePaths: List<String>
        get() = itemsByArchive
            .filterValues { group ->
                group.all { it.selectedSystemId != null } &&
                    group.map { it.selectedSystemId }.distinct().size == 1
            }
            .keys.toList()

    /** Archives with at least one assignment that still cannot be moved. */
    val blockedArchiveCount: Int
        get() = itemsByArchive
            .filterValues { group -> group.any { it.selectedSystemId != null } }
            .size - movableArchivePaths.size
}

@HiltViewModel
class ReviewViewModel @Inject constructor(
    private val session: ReviewSession,
    private val folderRepository: RomFolderRepository,
    private val archiveMover: ArchiveMover,
    private val logRepository: LogRepository,
    val registry: SystemRegistry,
) : ViewModel() {

    private val _uiState = MutableStateFlow(ReviewUiState())
    val uiState: StateFlow<ReviewUiState> = _uiState.asStateFlow()

    init {
        loadFromSession()
    }

    private fun loadFromSession() {
        val items = session.analyses.flatMap { analysis ->
            analysis.roms.map { rom ->
                ReviewItem(
                    id = "${analysis.archive.path}::${rom.group.primary}",
                    archiveFileName = analysis.archive.fileName,
                    archivePath = analysis.archive.path,
                    rom = rom,
                )
            }
        }
        _uiState.value = ReviewUiState(items = items)

        // Core rule: only CERTAIN detections get their target prefilled.
        for (item in items) {
            val system = item.rom.detection.system
            if (system != null && item.rom.detection.confidence == Confidence.CERTAIN) {
                selectSystem(item.id, system.id)
            }
        }
    }

    /** Applies the user's (or the CERTAIN prefill's) system choice. */
    fun selectSystem(itemId: String, systemId: String) {
        val system = registry.byId(systemId) ?: return
        viewModelScope.launch {
            val path = folderRepository.targetPathFor(system)
            val exists = folderRepository.folderExists(system)
            _uiState.update { state ->
                state.copy(
                    items = state.items.map { item ->
                        if (item.id == itemId) {
                            item.copy(
                                selectedSystemId = system.id,
                                targetPath = path,
                                targetExists = exists,
                            )
                        } else {
                            item
                        }
                    },
                )
            }
        }
    }

    /** Removes the assignment again (user changed their mind). */
    fun clearSelection(itemId: String) {
        _uiState.update { state ->
            state.copy(
                items = state.items.map { item ->
                    if (item.id == itemId) {
                        item.copy(selectedSystemId = null, targetPath = null, targetExists = null)
                    } else {
                        item
                    }
                },
            )
        }
    }

    /** Creates all missing target folders of the assigned items. */
    fun createMissingFolders() {
        val systems: List<SystemDefinition> = _uiState.value.items
            .filter { it.selectedSystemId != null && it.targetExists == false }
            .mapNotNull { registry.byId(it.selectedSystemId!!) }
            .distinctBy { it.id }
        if (systems.isEmpty()) return

        _uiState.update { it.copy(creatingFolders = true, folderResult = null) }
        viewModelScope.launch {
            var created = 0
            var failed = 0
            for (system in systems) {
                folderRepository.ensureFolder(system)
                    .onSuccess { created++ }
                    .onFailure { failed++ }
            }
            // Refresh existence flags for all assigned items
            val affected = _uiState.value.items.filter { it.selectedSystemId != null }
            for (item in affected) {
                selectSystem(item.id, item.selectedSystemId!!)
            }
            _uiState.update {
                it.copy(
                    creatingFolders = false,
                    folderResult = FolderCreationResult(created = created, failed = failed),
                )
            }
        }
    }

    /** Clears the one-shot folder feedback after the UI showed it. */
    fun consumeFolderResult() {
        _uiState.update { it.copy(folderResult = null) }
    }

    /**
     * Moves all movable archives into their system folders. Every outcome
     * is written to the persistent log; the UI navigates to the log screen
     * when the run finished.
     */
    fun moveAssigned() {
        val state = _uiState.value
        val plan = state.movableArchivePaths.mapNotNull { path ->
            val item = state.items.first { it.archivePath == path }
            val system = registry.byId(item.selectedSystemId ?: return@mapNotNull null)
                ?: return@mapNotNull null
            Triple(path, item.archiveFileName, system)
        }
        if (plan.isEmpty() || state.moving) return

        _uiState.update { it.copy(moving = true, moveSummary = null) }
        viewModelScope.launch {
            var moved = 0
            var failed = 0
            for ((path, fileName, system) in plan) {
                val targetDir = folderRepository.targetPathFor(system)
                archiveMover.move(path, targetDir)
                    .onSuccess { newPath ->
                        moved++
                        logRepository.append(LogLevel.SUCCESS, "$fileName → $newPath")
                        // Remove the archive's items from the review list
                        _uiState.update { s ->
                            s.copy(items = s.items.filterNot { it.archivePath == path })
                        }
                    }
                    .onFailure { error ->
                        failed++
                        logRepository.append(
                            LogLevel.ERROR,
                            "$fileName: ${error.message ?: "Unbekannter Fehler"}",
                        )
                    }
            }
            _uiState.update {
                it.copy(moving = false, moveSummary = MoveSummary(moved = moved, failed = failed))
            }
        }
    }

    /** Clears the one-shot move feedback after the UI handled it. */
    fun consumeMoveSummary() {
        _uiState.update { it.copy(moveSummary = null) }
    }
}
