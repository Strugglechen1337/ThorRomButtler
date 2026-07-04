package dev.thor.rombutler.data.files

import com.google.common.truth.Truth.assertThat
import dev.thor.rombutler.data.files.LibraryChecker.Companion.normalizeTitle
import org.junit.Test

class LibraryCheckerTest {

    @Test
    fun `regions revisions and tags collapse to the same title`() {
        val variants = listOf(
            "Pixel Kingdom (Europe).sfc",
            "Pixel Kingdom (USA) (Rev 1).sfc",
            "Pixel Kingdom [b].sfc",
            "Pixel   Kingdom (Japan) (En,Ja).sfc",
        )
        val titles = variants.map(::normalizeTitle).distinct()
        assertThat(titles).containsExactly("pixel kingdom")
    }

    @Test
    fun `different games stay different`() {
        assertThat(normalizeTitle("Pixel Kingdom II (Europe).sfc"))
            .isNotEqualTo(normalizeTitle("Pixel Kingdom (Europe).sfc"))
    }
}
