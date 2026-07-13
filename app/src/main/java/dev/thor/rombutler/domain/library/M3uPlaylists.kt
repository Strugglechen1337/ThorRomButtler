package dev.thor.rombutler.domain.library

import java.io.File

/**
 * Generates `.m3u` playlists for multi-disc games: when a system folder
 * contains two or more discs of the same title, one playlist per title is
 * written so frontends (ES-DE, RetroArch) show a single entry and the
 * emulator can swap discs.
 *
 * Strictly additive: existing playlists are never touched, single-disc
 * games are ignored, and only playable disc formats are referenced
 * (`.bin` files stay hidden behind their `.cue`).
 */
object M3uPlaylists {

    /** One playlist that was actually written. */
    data class Created(val playlist: File, val discCount: Int)

    /**
     * Scans [targetDir] (non-recursive) and writes missing playlists.
     * Returns the created playlists; I/O errors skip the affected title.
     */
    fun generate(targetDir: File): List<Created> {
        val files = targetDir.listFiles().orEmpty()
            .filter { it.isFile && it.extension.lowercase() in PLAYLIST_EXTENSIONS }
        if (files.size < 2) return emptyList()

        return files
            .mapNotNull { file -> discNumberOf(file.name)?.let { Triple(baseTitleOf(file.name), it, file) } }
            .groupBy { it.first }
            .mapNotNull { (baseTitle, discs) ->
                writePlaylist(targetDir, baseTitle, discs.map { it.second to it.third })
            }
    }

    /** Disc number from tokens like `(Disc 2)`, `[CD1]`, `(Disk 3 of 4)`. */
    fun discNumberOf(fileName: String): Int? =
        DISC_TOKEN.find(fileName)?.groupValues?.get(2)?.toIntOrNull()

    /** File name without extension and without the disc token. */
    fun baseTitleOf(fileName: String): String =
        fileName.substringBeforeLast('.')
            .replace(DISC_TOKEN, "")
            .replace(Regex("\\s{2,}"), " ")
            .trim()

    private fun writePlaylist(
        targetDir: File,
        baseTitle: String,
        discs: List<Pair<Int, File>>,
    ): Created? {
        if (baseTitle.isEmpty()) return null
        val distinctDiscs = discs
            .distinctBy { it.first }
            .sortedBy { it.first }
        if (distinctDiscs.size < 2) return null

        val playlist = File(targetDir, "$baseTitle.m3u")
        if (playlist.exists()) return null

        return runCatching {
            playlist.writeText(
                distinctDiscs.joinToString(separator = "\n", postfix = "\n") { it.second.name },
            )
            Created(playlist, distinctDiscs.size)
        }.getOrNull()
    }

    /** Formats a frontend can launch directly; `.bin` is loaded via `.cue`. */
    private val PLAYLIST_EXTENSIONS = setOf("cue", "chd", "iso", "pbp", "gdi", "cdi")

    private val DISC_TOKEN =
        Regex("""\s*[(\[](disc|disk|cd|disque)\s*(\d{1,2})[^)\]]*[)\]]""", RegexOption.IGNORE_CASE)
}
