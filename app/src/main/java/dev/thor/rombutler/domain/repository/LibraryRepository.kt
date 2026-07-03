package dev.thor.rombutler.domain.repository

import dev.thor.rombutler.domain.model.DetectedRom

/** Per-system statistics of the ROM library. */
data class SystemStat(
    val systemId: String,
    val displayName: String,
    val romCount: Int,
    val totalBytes: Long,
)

/**
 * Result of a library check.
 *
 * @property stats per-system counts/sizes, biggest first.
 * @property misplaced ROMs whose CERTAIN detection contradicts the folder
 *   they live in (e.g. a `.gba` inside `roms/psx`) — offered for re-sorting.
 */
data class LibraryReport(
    val totalRoms: Int,
    val totalBytes: Long,
    val stats: List<SystemStat>,
    val misplaced: List<DetectedRom>,
)

/**
 * Inspects the existing ROM library below the ROM base folder.
 */
interface LibraryRepository {

    /** Walks the library, collects statistics and finds misplaced ROMs. */
    suspend fun check(): LibraryReport
}
