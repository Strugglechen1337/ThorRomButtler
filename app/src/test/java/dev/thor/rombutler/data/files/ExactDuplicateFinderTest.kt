package dev.thor.rombutler.data.files

import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Test
import java.nio.file.Files

class ExactDuplicateFinderTest {

    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    @Test
    fun `hashes same-sized candidates and reports only identical files`() = runTest {
        val base = Files.createTempDirectory("thor-exact-duplicates").toFile()
        try {
            val first = base.resolve("gba/Game (Europe).gba").apply {
                requireNotNull(parentFile).mkdirs()
                writeBytes(byteArrayOf(1, 2, 3, 4))
            }
            val duplicate = base.resolve("backup/Game.gba").apply {
                requireNotNull(parentFile).mkdirs()
                writeBytes(byteArrayOf(1, 2, 3, 4))
            }
            val sameSizeDifferentContent = base.resolve("gba/Other.gba").apply {
                writeBytes(byteArrayOf(4, 3, 2, 1))
            }
            val uniqueSize = base.resolve("gba/Unique.gba").apply {
                writeBytes(byteArrayOf(9, 8, 7))
            }

            val report = ExactDuplicateFinder(UnconfinedTestDispatcher(testScheduler)).find(
                base = base,
                files = listOf(first, duplicate, sameSizeDifferentContent, uniqueSize),
            )

            assertThat(report.candidateFiles).isEqualTo(3)
            assertThat(report.duplicateFiles).isEqualTo(2)
            assertThat(report.reclaimableBytes).isEqualTo(4L)
            assertThat(report.groups).hasSize(1)
            assertThat(report.groups.single().files).containsExactly(
                "backup/Game.gba",
                "gba/Game (Europe).gba",
            )
        } finally {
            base.deleteRecursively()
        }
    }
}
