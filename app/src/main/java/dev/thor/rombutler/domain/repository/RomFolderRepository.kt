package dev.thor.rombutler.domain.repository

import dev.thor.rombutler.domain.model.SystemDefinition

/**
 * Access to the per-system target folders below the ROM base folder.
 */
interface RomFolderRepository {

    /** Absolute target path for [system], e.g. `/storage/.../ROMs/psx`. */
    suspend fun targetPathFor(system: SystemDefinition): String

    /** True when the target folder for [system] already exists. */
    suspend fun folderExists(system: SystemDefinition): Boolean

    /**
     * Creates the target folder for [system] (including parents) when
     * missing.
     *
     * @return the absolute folder path on success.
     */
    suspend fun ensureFolder(system: SystemDefinition): Result<String>
}
