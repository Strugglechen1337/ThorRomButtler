package dev.thor.rombutler.domain.repository

/**
 * Moves archive files into their target system folder.
 */
interface ArchiveMover {

    /**
     * Moves the file at [sourcePath] into [targetDir] (created when
     * missing). Prefers an instant rename; falls back to copy + delete
     * across storage volumes. Never overwrites an existing target file.
     *
     * @return absolute path of the moved file on success.
     */
    suspend fun move(sourcePath: String, targetDir: String): Result<String>
}
