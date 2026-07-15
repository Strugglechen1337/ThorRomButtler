package dev.thor.rombutler.data.files

import dev.thor.rombutler.di.IoDispatcher
import dev.thor.rombutler.domain.detection.BiosDetector
import dev.thor.rombutler.domain.model.LogLevel
import dev.thor.rombutler.domain.model.UndoInfo
import dev.thor.rombutler.domain.model.UndoKind
import dev.thor.rombutler.domain.repository.LogRepository
import dev.thor.rombutler.domain.repository.SettingsRepository
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

/** Snapshot for the scan screen: found BIOS files + feature readiness. */
data class BiosScan(
    val files: List<String>,
    val folderSet: Boolean,
)

/**
 * DI wiring around [BiosFiles]: reads the scan roots and the BIOS target
 * from settings, moves with one undoable log entry per run.
 */
@Singleton
class BiosSorter @Inject constructor(
    private val settingsRepository: SettingsRepository,
    private val biosDetector: BiosDetector,
    private val logRepository: LogRepository,
    @param:IoDispatcher private val ioDispatcher: CoroutineDispatcher,
) {

    suspend fun scan(): BiosScan = withContext(ioDispatcher) {
        val settings = settingsRepository.settings.first()
        val roots = (listOfNotNull(settings.downloadPath) + settings.additionalSourcePaths)
            .distinct()
            .map(::File)
        BiosScan(
            files = BiosFiles.findLoose(roots, biosDetector::isBios).map { it.absolutePath },
            folderSet = !settings.biosFolderPath.isNullOrBlank(),
        )
    }

    /**
     * Heuristic BIOS-folder proposal for the settings screen; null when a
     * folder is already configured or nothing sensible can be suggested.
     */
    suspend fun suggestFolder(): BiosFolderSuggestion? = withContext(ioDispatcher) {
        val settings = settingsRepository.settings.first()
        if (!settings.biosFolderPath.isNullOrBlank()) return@withContext null

        val romBase = settings.romBasePath?.let(::File)
        val candidates = buildList {
            romBase?.let { base ->
                add(File(base, "bios"))
                add(File(base, "BIOS"))
                base.parentFile?.let { parent ->
                    add(File(parent, "bios"))
                    add(File(parent, "BIOS"))
                }
            }
            for (root in storageRoots()) {
                add(File(root, "BIOS"))
                add(File(root, "bios"))
                add(File(root, "RetroArch/system"))
            }
        }.distinctBy { it.absolutePath }

        BiosFiles.suggestFolder(
            candidates = candidates,
            isBios = biosDetector::isBios,
            fallback = romBase?.parentFile?.let { File(it, "BIOS") },
        )
    }

    /** Creates the folder when needed and persists it as BIOS target. */
    suspend fun applySuggestion(suggestion: BiosFolderSuggestion): Boolean =
        withContext(ioDispatcher) {
            val dir = File(suggestion.path)
            if (!dir.isDirectory && !dir.mkdirs()) return@withContext false
            settingsRepository.setBiosFolderPath(dir.absolutePath)
            true
        }

    /** Internal storage plus mounted SD cards (same logic as the picker). */
    private fun storageRoots(): List<File> {
        val internal = android.os.Environment.getExternalStorageDirectory()
        val external = File("/storage")
            .listFiles { f: File -> f.isDirectory && f.name != "emulated" && f.name != "self" && f.canRead() }
            ?.toList()
            .orEmpty()
        return listOf(internal) + external.sortedBy { it.name }
    }

    /** Moves all found BIOS files into the configured folder. */
    suspend fun moveAll(paths: List<String>): BiosMoveResult = withContext(ioDispatcher) {
        val target = settingsRepository.settings.first().biosFolderPath
            ?: return@withContext BiosMoveResult(emptyList(), paths)
        val result = BiosFiles.moveAll(paths.map(::File), File(target))

        if (result.moved.isNotEmpty()) {
            logRepository.append(
                LogLevel.SUCCESS,
                "BIOS einsortiert: ${result.moved.size} Datei(en) → $target",
                undo = UndoInfo(
                    kind = UndoKind.MOVED,
                    createdFiles = result.moved.map { it.second },
                    restoreTo = result.moved.map { it.first },
                ),
            )
        }
        if (result.failed.isNotEmpty()) {
            logRepository.append(
                LogLevel.ERROR,
                "BIOS-Einsortierung: ${result.failed.size} Datei(en) fehlgeschlagen",
            )
        }
        result
    }
}
