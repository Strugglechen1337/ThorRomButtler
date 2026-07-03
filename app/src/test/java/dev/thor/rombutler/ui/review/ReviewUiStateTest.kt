package dev.thor.rombutler.ui.review

import com.google.common.truth.Truth.assertThat
import dev.thor.rombutler.domain.detection.RomFileGroup
import dev.thor.rombutler.domain.detection.SystemRegistry
import dev.thor.rombutler.domain.model.Confidence
import dev.thor.rombutler.domain.model.DetectedRom
import dev.thor.rombutler.domain.model.DetectionResult
import dev.thor.rombutler.domain.model.MatchSource
import org.junit.Test

class ReviewUiStateTest {

    private val registry = SystemRegistry()

    private fun item(
        id: String,
        confidence: Confidence,
        systemId: String?,
        selected: String? = null,
        occupied: Boolean = false,
        overwrite: Boolean = false,
    ) = ReviewItem(
        id = id,
        source = RomSource.LooseFiles,
        rom = DetectedRom(
            group = RomFileGroup(primary = "$id.bin", members = listOf("$id.bin")),
            memberEntryPaths = listOf("/dl/$id.bin"),
            detection = DetectionResult(
                system = systemId?.let { registry.byId(it) },
                confidence = confidence,
                source = MatchSource.EXTENSION,
            ),
            totalSizeBytes = 0,
        ),
        selectedSystemId = selected,
        targetOccupied = occupied,
        overwrite = overwrite,
    )

    @Test
    fun `processable excludes unresolved duplicates`() {
        val state = ReviewUiState(
            items = listOf(
                item("a", Confidence.CERTAIN, "gba", selected = "gba"),
                item("b", Confidence.CERTAIN, "gba", selected = "gba", occupied = true),
                item("c", Confidence.CERTAIN, "gba", selected = "gba", occupied = true, overwrite = true),
                item("d", Confidence.UNKNOWN, null),
            ),
        )
        assertThat(state.assignedCount).isEqualTo(3)
        // b is a duplicate without overwrite -> not processable
        assertThat(state.processableCount).isEqualTo(2)
    }

    @Test
    fun `open suggestions counts unassigned probables only`() {
        val state = ReviewUiState(
            items = listOf(
                item("a", Confidence.PROBABLE, "psx"),
                item("b", Confidence.PROBABLE, "psx", selected = "psx"),
                item("c", Confidence.UNKNOWN, null),
                item("d", Confidence.CERTAIN, "gba", selected = "gba"),
            ),
        )
        assertThat(state.openSuggestionCount).isEqualTo(1)
    }
}
