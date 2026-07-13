package dev.thor.rombutler.data.patch

import dev.thor.rombutler.data.files.IncomingFile
import dev.thor.rombutler.di.IoDispatcher
import dev.thor.rombutler.domain.model.LogLevel
import dev.thor.rombutler.domain.repository.LogRepository
import dev.thor.rombutler.domain.repository.SettingsRepository
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

/** A patch file paired with the ROM it applies to (same base name, same folder). */
data class PatchPair(
    val patchPath: String,
    val patchName: String,
    val romPath: String,
    val romName: String,
)

/**
 * Finds `.ips`/`.ups`/`.bps` patches in the scan folders and applies them.
 * Pairing is by identical base name next to each other — the convention
 * used by romhacking sites ("put the patch next to the ROM"). The source
 * ROM is never modified; the patched game is written as a new file and
 * picked up by the next scan.
 */
@Singleton
class PatchScanner @Inject constructor(
    private val settingsRepository: SettingsRepository,
    private val logRepository: LogRepository,
    @param:IoDispatcher private val ioDispatcher: CoroutineDispatcher,
) {

    /** All applicable patch/ROM pairs in the configured scan folders. */
    suspend fun findPairs(): List<PatchPair> = withContext(ioDispatcher) {
        val settings = settingsRepository.settings.first()
        val roots = (listOfNotNull(settings.downloadPath) + settings.additionalSourcePaths)
            .distinct()
            .map(::File)
            .filter { it.isDirectory }

        roots.flatMap { root ->
            val files = root.listFiles().orEmpty().filter { it.isFile }
            val patches = files.filter { it.extension.lowercase() in RomPatcher.PATCH_EXTENSIONS }
            if (patches.isEmpty()) return@flatMap emptyList()

            val candidates = files
                .filter {
                    it.extension.lowercase() !in RomPatcher.PATCH_EXTENSIONS &&
                        it.extension.lowercase() !in ARCHIVE_EXTENSIONS
                }
                .groupBy { it.nameWithoutExtension.lowercase() }

            patches.mapNotNull { patch ->
                val rom = candidates[patch.nameWithoutExtension.lowercase()]
                    ?.minByOrNull { it.name }
                    ?: return@mapNotNull null
                // Already applied? Then keep the card out of the way.
                val done = File(rom.parentFile, "${rom.nameWithoutExtension} (patched).${rom.extension}")
                if (done.exists()) return@mapNotNull null
                PatchPair(
                    patchPath = patch.absolutePath,
                    patchName = patch.name,
                    romPath = rom.absolutePath,
                    romName = rom.name,
                )
            }
        }.sortedBy { it.patchName.lowercase() }
    }

    /**
     * Applies the pair and writes the result next to the ROM.
     * @return the created file name.
     * @throws PatchException with a user-facing message on any mismatch.
     */
    suspend fun apply(pair: PatchPair): String = withContext(ioDispatcher) {
        val patchFile = File(pair.patchPath)
        val romFile = File(pair.romPath)
        if (!patchFile.isFile || !romFile.isFile) {
            throw PatchException("Datei nicht mehr vorhanden – bitte neu scannen")
        }
        if (patchFile.length() > RomPatcher.MAX_INPUT_BYTES ||
            romFile.length() > RomPatcher.MAX_INPUT_BYTES
        ) {
            throw PatchException("Datei ist zu groß zum Patchen (max. 128 MB)")
        }

        val patched = RomPatcher.apply(patchFile.readBytes(), romFile.readBytes())

        val parent = romFile.parentFile
            ?: throw PatchException("Zielordner nicht ermittelbar")
        val outputName = "${romFile.nameWithoutExtension} (patched).${romFile.extension}"
        val target = IncomingFile.uniqueTarget(parent, outputName)
            ?: throw PatchException("Kein freier Zieldateiname")
        target.writeBytes(patched)

        logRepository.append(
            LogLevel.SUCCESS,
            "Patch angewendet: ${pair.patchName} + ${pair.romName} → ${target.name}",
        )
        target.name
    }

    private companion object {
        val ARCHIVE_EXTENSIONS = setOf("zip", "7z", "rar")
    }
}
