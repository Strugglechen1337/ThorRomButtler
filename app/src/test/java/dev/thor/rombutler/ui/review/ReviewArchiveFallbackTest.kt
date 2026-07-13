package dev.thor.rombutler.ui.review

import com.google.common.truth.Truth.assertThat
import dev.thor.rombutler.domain.detection.RomFileGroup
import dev.thor.rombutler.domain.detection.SystemRegistry
import dev.thor.rombutler.domain.model.ArchiveType
import dev.thor.rombutler.domain.model.DetectedRom
import dev.thor.rombutler.domain.model.DetectionResult
import dev.thor.rombutler.extraction.TaskSource
import org.junit.Test

class ReviewArchiveFallbackTest {

    private val registry = SystemRegistry()

    private fun fallback(type: ArchiveType): ReviewItem {
        val extension = when (type) {
            ArchiveType.ZIP -> "zip"
            ArchiveType.SEVEN_ZIP -> "7z"
            ArchiveType.RAR4, ArchiveType.RAR5 -> "rar"
        }
        return ReviewItem(
            id = "fallback",
            source = RomSource.ArchiveFallback(
                archivePath = "/downloads/game.$extension",
                archiveType = type,
                archiveFileName = "game.$extension",
                archiveSizeBytes = 80,
            ),
            rom = DetectedRom(
                group = RomFileGroup("game.$extension", listOf("game.rom")),
                memberEntryPaths = listOf("folder/game.rom"),
                detection = DetectionResult.UNKNOWN,
                totalSizeBytes = 100,
            ),
        )
    }

    @Test
    fun `7z fallback is extracted even when manually assigned`() {
        val resolved = fallback(ArchiveType.SEVEN_ZIP).resolveTask(registry.byId("gba")!!)

        assertThat(resolved.source).isEqualTo(
            TaskSource.Archive("/downloads/game.7z", ArchiveType.SEVEN_ZIP),
        )
        assertThat(resolved.entryPaths).containsExactly("folder/game.rom")
        assertThat(resolved.expectedBytes).isEqualTo(100L)
        assertThat(resolved.keepsArchivePacked).isFalse()
    }

    @Test
    fun `only arcade zip fallback stays packed`() {
        val arcade = registry.byId("arcade")!!
        val packed = fallback(ArchiveType.ZIP).resolveTask(arcade)
        val sevenZip = fallback(ArchiveType.SEVEN_ZIP).resolveTask(arcade)

        assertThat(packed.source).isEqualTo(TaskSource.Loose)
        assertThat(packed.entryPaths).containsExactly("/downloads/game.zip")
        assertThat(packed.expectedBytes).isEqualTo(80L)
        assertThat(packed.keepsArchivePacked).isTrue()

        assertThat(sevenZip.source).isEqualTo(
            TaskSource.Archive("/downloads/game.7z", ArchiveType.SEVEN_ZIP),
        )
        assertThat(sevenZip.keepsArchivePacked).isFalse()
    }
}
