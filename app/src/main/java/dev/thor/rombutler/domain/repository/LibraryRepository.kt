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
 * Same game present multiple times in one system folder (1G1R view):
 * different regions/revisions of one title, per No-Intro naming.
 *
 * @property title normalized game title (tags stripped).
 * @property systemName display name of the system.
 * @property variants the actual file names, e.g. "(Europe)" and "(USA)".
 */
data class DuplicateGroup(
    val title: String,
    val systemName: String,
    val variants: List<String>,
)

/** Files with identical size and SHA-256 content hash. */
data class ExactDuplicateGroup(
    val sha256: String,
    val sizeBytes: Long,
    val files: List<String>,
)

/** Result of the optional, potentially long-running exact duplicate scan. */
data class ExactDuplicateReport(
    val candidateFiles: Int,
    val duplicateFiles: Int,
    val reclaimableBytes: Long,
    val groups: List<ExactDuplicateGroup>,
)

/**
 * Result of a library check.
 *
 * @property stats per-system counts/sizes, biggest first.
 * @property misplaced ROMs whose CERTAIN detection contradicts the folder
 *   they live in (e.g. a `.gba` inside `roms/psx`) — offered for re-sorting.
 * @property duplicates same-title variants within one system (informational).
 */
data class LibraryReport(
    val totalRoms: Int,
    val totalBytes: Long,
    val stats: List<SystemStat>,
    val misplaced: List<DetectedRom>,
    val duplicates: List<DuplicateGroup> = emptyList(),
)

/**
 * Inspects the existing ROM library below the ROM base folder.
 */
interface LibraryRepository {

    /** Walks the library, collects statistics and finds misplaced ROMs. */
    suspend fun check(): LibraryReport

    /** Hashes only same-sized candidates and reports byte-identical files. */
    suspend fun findExactDuplicates(): ExactDuplicateReport
}
