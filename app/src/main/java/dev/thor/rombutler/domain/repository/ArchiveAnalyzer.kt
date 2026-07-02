package dev.thor.rombutler.domain.repository

import dev.thor.rombutler.domain.model.ArchiveAnalysis
import dev.thor.rombutler.domain.model.RomArchive

/**
 * Inspects archive contents without extracting them and runs the
 * detection engine on every contained ROM.
 */
interface ArchiveAnalyzer {

    /**
     * Lists the entries of [archive], groups multi-file ROMs and detects
     * their systems. Reads at most a few KB per entry (entry names, cue/m3u
     * text, magic-byte headers) — never the whole archive.
     */
    suspend fun analyze(archive: RomArchive): ArchiveAnalysis
}
