package dev.thor.rombutler.data.files

import dev.thor.rombutler.di.IoDispatcher
import dev.thor.rombutler.domain.detection.BiosDetector
import dev.thor.rombutler.domain.detection.DetectionEngine
import dev.thor.rombutler.domain.detection.RomFileGrouper
import dev.thor.rombutler.domain.detection.SystemRegistry
import dev.thor.rombutler.domain.model.Confidence
import dev.thor.rombutler.domain.model.DetectedRom
import dev.thor.rombutler.domain.repository.LibraryReport
import dev.thor.rombutler.domain.repository.LibraryRepository
import dev.thor.rombutler.domain.repository.SettingsRepository
import dev.thor.rombutler.domain.repository.SystemStat
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

/**
 * [LibraryRepository] on plain [java.io.File]: walks every known system
 * folder below the ROM base, counts ROMs per system and flags files whose
 * CERTAIN detection contradicts the folder they live in.
 *
 * Deliberately conservative: only CERTAIN detections are reported as
 * misplaced (a lone `.bin` in `roms/psx` is fine), and folder hints are
 * NOT used here — the current folder is exactly what is being questioned.
 */
@Singleton
class LibraryChecker @Inject constructor(
    private val settingsRepository: SettingsRepository,
    private val registry: SystemRegistry,
    private val engine: DetectionEngine,
    private val grouper: RomFileGrouper,
    private val biosDetector: BiosDetector,
    @param:IoDispatcher private val ioDispatcher: CoroutineDispatcher,
) : LibraryRepository {

    override suspend fun check(): LibraryReport = withContext(ioDispatcher) {
        val settings = settingsRepository.settings.first()
        val basePath = settings.romBasePath
            ?: throw IllegalStateException("ROM-Basisordner ist nicht konfiguriert")
        val base = File(basePath)
        if (!base.isDirectory) throw IllegalStateException("ROM-Basisordner nicht gefunden: $basePath")

        // Effective folder name -> system (overrides win)
        val folderToSystem = registry.systems.associateBy { system ->
            (settings.folderOverrides[system.id] ?: system.esdeFolder).lowercase()
        }

        val stats = mutableListOf<SystemStat>()
        val misplaced = mutableListOf<DetectedRom>()

        for (dir in base.listFiles()?.filter { it.isDirectory }.orEmpty()) {
            val system = folderToSystem[dir.name.lowercase()] ?: continue
            val romFiles = mutableListOf<File>()
            collectRomFiles(dir, depth = 0, into = romFiles)

            stats += SystemStat(
                systemId = system.id,
                displayName = system.displayName,
                romCount = romFiles.size,
                totalBytes = romFiles.sumOf { it.length() },
            )

            // Group per directory (bin+cue stays together), then question
            // only groups with a CERTAIN detection that disagrees.
            romFiles.groupBy { it.parentFile?.absolutePath.orEmpty() }
                .forEach { (_, dirFiles) ->
                    val byName = dirFiles.associateBy { it.name }
                    for (group in grouper.group(byName.keys.toList())) {
                        val primary = byName.getValue(group.primary)
                        val detection = detect(primary)
                        if (detection.confidence == Confidence.CERTAIN &&
                            detection.system != null &&
                            detection.system!!.id != system.id
                        ) {
                            misplaced += DetectedRom(
                                group = group,
                                memberEntryPaths = group.members.mapNotNull { byName[it]?.absolutePath },
                                detection = detection,
                                totalSizeBytes = group.members.sumOf { byName[it]?.length() ?: 0L },
                            )
                        }
                    }
                }
        }

        LibraryReport(
            totalRoms = stats.sumOf { it.romCount },
            totalBytes = stats.sumOf { it.totalBytes },
            stats = stats.sortedByDescending { it.totalBytes },
            misplaced = misplaced.sortedBy { it.group.primary.lowercase() },
        )
    }

    private fun collectRomFiles(dir: File, depth: Int, into: MutableList<File>) {
        for (child in dir.listFiles().orEmpty()) {
            when {
                child.isDirectory && depth < MAX_DEPTH && !child.name.startsWith(".") ->
                    collectRomFiles(child, depth + 1, into)

                child.isFile &&
                    engine.isRomFileName(child.name) &&
                    !biosDetector.isBios(child.name) -> into += child
            }
        }
    }

    /** Extension first, header only when needed — NO folder hint here. */
    private fun detect(file: File): dev.thor.rombutler.domain.model.DetectionResult {
        val byName = engine.detect(file.name)
        if (byName.confidence == Confidence.CERTAIN) return byName
        val maxBytes = minOf(DetectionEngine.MAX_HEADER_BYTES.toLong(), file.length()).toInt()
        val header = try {
            ByteArray(maxBytes).also { buffer -> file.inputStream().use { it.read(buffer) } }
        } catch (_: java.io.IOException) {
            return byName
        }
        val byMagic = engine.detect(file.name, header)
        return if (byMagic.confidence.ordinal < byName.confidence.ordinal) byMagic else byName
    }

    private companion object {
        const val MAX_DEPTH = 2 // system folder + per-game subfolders
    }
}
