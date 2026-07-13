package dev.thor.rombutler.domain.library

import com.google.common.truth.Truth.assertThat
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class M3uPlaylistsTest {

    @get:Rule
    val tempDir = TemporaryFolder()

    @Test
    fun `multi disc game gets one playlist with discs in order`() {
        val dir = tempDir.newFolder("psx")
        // Disc 2 created first on purpose: order must come from the token
        java.io.File(dir, "Crystal Saga VII (Europe) (Disc 2).cue").writeText("d2")
        java.io.File(dir, "Crystal Saga VII (Europe) (Disc 1).cue").writeText("d1")
        java.io.File(dir, "Crystal Saga VII (Europe) (Disc 1).bin").writeText("raw")

        val created = M3uPlaylists.generate(dir)

        assertThat(created).hasSize(1)
        val playlist = java.io.File(dir, "Crystal Saga VII (Europe).m3u")
        assertThat(playlist.exists()).isTrue()
        assertThat(playlist.readText()).isEqualTo(
            "Crystal Saga VII (Europe) (Disc 1).cue\n" +
                "Crystal Saga VII (Europe) (Disc 2).cue\n",
        )
    }

    @Test
    fun `single disc and unrelated files create nothing`() {
        val dir = tempDir.newFolder("dc")
        java.io.File(dir, "Star Courier GX (Disc 1).gdi").writeText("x")
        java.io.File(dir, "Moonlight Quest.chd").writeText("y")

        assertThat(M3uPlaylists.generate(dir)).isEmpty()
    }

    @Test
    fun `existing playlist is never overwritten`() {
        val dir = tempDir.newFolder("psx")
        java.io.File(dir, "Pixel Kingdom (Disc 1).chd").writeText("1")
        java.io.File(dir, "Pixel Kingdom (Disc 2).chd").writeText("2")
        java.io.File(dir, "Pixel Kingdom.m3u").writeText("user content")

        assertThat(M3uPlaylists.generate(dir)).isEmpty()
        assertThat(java.io.File(dir, "Pixel Kingdom.m3u").readText())
            .isEqualTo("user content")
    }

    @Test
    fun `disc token variants are recognized`() {
        assertThat(M3uPlaylists.discNumberOf("Game (Disc 1).cue")).isEqualTo(1)
        assertThat(M3uPlaylists.discNumberOf("Game [CD2].iso")).isEqualTo(2)
        assertThat(M3uPlaylists.discNumberOf("Game (Disk 3 of 4).chd")).isEqualTo(3)
        assertThat(M3uPlaylists.discNumberOf("Game (Europe).iso")).isNull()
        assertThat(M3uPlaylists.baseTitleOf("Game (Europe) (Disc 2).cue"))
            .isEqualTo("Game (Europe)")
    }
}
