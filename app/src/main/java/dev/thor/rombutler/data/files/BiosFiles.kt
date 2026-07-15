package dev.thor.rombutler.data.files

import java.io.File
import java.io.IOException

/** Result of moving BIOS files: (from → to) pairs plus failures. */
data class BiosMoveResult(
    val moved: List<Pair<String, String>>,
    val failed: List<String>,
)

/**
 * Auto-detected BIOS folder proposal.
 *
 * @property containsBios true when known BIOS files were found inside —
 *   the strongest possible signal that this is the user's BIOS stash.
 * @property exists false when the folder would be created on accept.
 */
data class BiosFolderSuggestion(
    val path: String,
    val exists: Boolean,
    val containsBios: Boolean,
)

/**
 * Finds loose BIOS/firmware files in the scan folders and moves them into
 * the user's BIOS folder. The counterpart to the ROM flow: BIOS files are
 * deliberately excluded from the review list, but instead of only ignoring
 * them, the butler can now put them where the emulators expect them.
 *
 * Moves are rename-first with a verified copy+delete fallback (works
 * across storage volumes) and never overwrite an existing file — name
 * collisions get the usual `(1)` suffix.
 */
object BiosFiles {

    /** Loose BIOS files in [roots] (recursive, hidden folders skipped). */
    fun findLoose(roots: List<File>, isBios: (String) -> Boolean): List<File> {
        val found = mutableListOf<File>()
        for (root in roots.filter { it.isDirectory }) {
            collect(root, depth = 0, isBios = isBios, into = found)
        }
        return found.sortedBy { it.name.lowercase() }
    }

    /**
     * Picks the most plausible BIOS folder: an existing candidate that
     * already CONTAINS known BIOS files beats a merely existing folder,
     * which beats the create-on-accept [fallback]. Android cannot read
     * other apps' configuration (RetroArch's system dir lives in the
     * inaccessible Android/data), so this file-system heuristic is the
     * best any app can do.
     */
    fun suggestFolder(
        candidates: List<File>,
        isBios: (String) -> Boolean,
        fallback: File?,
    ): BiosFolderSuggestion? {
        val existing = candidates.filter { it.isDirectory }
        existing.firstOrNull { containsBiosFiles(it, isBios) }?.let {
            return BiosFolderSuggestion(it.absolutePath, exists = true, containsBios = true)
        }
        existing.firstOrNull()?.let {
            return BiosFolderSuggestion(it.absolutePath, exists = true, containsBios = false)
        }
        return fallback?.let {
            BiosFolderSuggestion(it.absolutePath, exists = false, containsBios = false)
        }
    }

    /** Moves [files] into [targetDir]; the caller logs the outcome. */
    fun moveAll(files: List<File>, targetDir: File): BiosMoveResult {
        if (!targetDir.isDirectory && !targetDir.mkdirs()) {
            return BiosMoveResult(emptyList(), files.map { it.absolutePath })
        }
        val moved = mutableListOf<Pair<String, String>>()
        val failed = mutableListOf<String>()
        for (file in files) {
            val result = runCatching { moveOne(file, targetDir) }
            val target = result.getOrNull()
            if (target != null) {
                moved += file.absolutePath to target.absolutePath
            } else {
                failed += file.absolutePath
            }
        }
        return BiosMoveResult(moved, failed)
    }

    private fun moveOne(file: File, targetDir: File): File {
        val safeName = IncomingFile.sanitizeName(file.name)
            ?: throw IOException("Ungültiger Dateiname: ${file.name}")
        // Some cores expect their BIOS in a subfolder (Flycast: system/dc)
        val destination = SUBFOLDER_BY_NAME[safeName.lowercase()]
            ?.let { File(targetDir, it) }
            ?: targetDir
        if (!destination.isDirectory && !destination.mkdirs()) {
            throw IOException("Zielordner konnte nicht angelegt werden: $destination")
        }
        val target = IncomingFile.uniqueTarget(destination, safeName)
            ?: throw IOException("Kein freier Zieldateiname: $safeName")

        // Fast path on the same volume, verified copy+delete across volumes
        if (file.renameTo(target)) return target
        file.inputStream().use { input -> IncomingFile.copyAtomically(input, target) }
        if (target.length() != file.length()) {
            target.delete()
            throw IOException("Kopie unvollständig: $safeName")
        }
        if (!file.delete()) {
            // Copy succeeded but the source is stuck — keep both, report failure
            throw IOException("Quelldatei nicht löschbar: $safeName")
        }
        return target
    }

    private fun collect(dir: File, depth: Int, isBios: (String) -> Boolean, into: MutableList<File>) {
        val children = dir.listFiles() ?: return
        for (child in children) {
            when {
                child.isDirectory -> {
                    if (depth < MAX_DEPTH && !child.name.startsWith(".")) {
                        collect(child, depth + 1, isBios, into)
                    }
                }

                child.isFile && isBios(child.name) -> into += child
            }
        }
    }

    private fun containsBiosFiles(dir: File, isBios: (String) -> Boolean): Boolean {
        val found = mutableListOf<File>()
        collect(dir, depth = MAX_DEPTH - 1, isBios = isBios, into = found)
        return found.isNotEmpty()
    }

    /** Core-specific BIOS subfolders below the system directory. */
    private val SUBFOLDER_BY_NAME = mapOf(
        "dc_boot.bin" to "dc",
        "dc_flash.bin" to "dc",
        "naomi.bin" to "dc",
    )

    private const val MAX_DEPTH = 3
}
