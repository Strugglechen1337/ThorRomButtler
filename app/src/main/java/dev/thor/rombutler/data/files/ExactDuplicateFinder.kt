package dev.thor.rombutler.data.files

import dev.thor.rombutler.di.IoDispatcher
import dev.thor.rombutler.domain.repository.ExactDuplicateGroup
import dev.thor.rombutler.domain.repository.ExactDuplicateReport
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.io.File
import java.security.MessageDigest
import javax.inject.Inject

/** Streaming SHA-256 duplicate finder that avoids hashing unique file sizes. */
class ExactDuplicateFinder @Inject constructor(
    @param:IoDispatcher private val ioDispatcher: CoroutineDispatcher,
) {
    suspend fun find(base: File, files: List<File>): ExactDuplicateReport = withContext(ioDispatcher) {
        val candidates = files
            .filter { it.isFile && it.length() > 0L }
            .groupBy(File::length)
            .filterValues { it.size > 1 }
            .values
            .flatten()

        val byContent = mutableMapOf<Pair<Long, String>, MutableList<File>>()
        for (file in candidates) {
            currentCoroutineContext().ensureActive()
            val hash = sha256(file)
            byContent.getOrPut(file.length() to hash, ::mutableListOf).add(file)
        }

        val groups = byContent
            .filterValues { it.size > 1 }
            .map { (identity, duplicates) ->
                ExactDuplicateGroup(
                    sha256 = identity.second,
                    sizeBytes = identity.first,
                    files = duplicates
                        .map { it.relativeTo(base).invariantSeparatorsPath }
                        .sorted(),
                )
            }
            .sortedWith(compareByDescending<ExactDuplicateGroup> { it.sizeBytes }.thenBy { it.files.first() })

        ExactDuplicateReport(
            candidateFiles = candidates.size,
            duplicateFiles = groups.sumOf { it.files.size },
            reclaimableBytes = groups.sumOf { it.sizeBytes * (it.files.size - 1) },
            groups = groups,
        )
    }

    private suspend fun sha256(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        val buffer = ByteArray(HASH_BUFFER_BYTES)
        file.inputStream().buffered().use { input ->
            while (true) {
                currentCoroutineContext().ensureActive()
                val read = input.read(buffer)
                if (read < 0) break
                if (read > 0) digest.update(buffer, 0, read)
            }
        }
        return digest.digest().joinToString("") { byte ->
            (byte.toInt() and 0xff).toString(16).padStart(2, '0')
        }
    }

    private companion object {
        const val HASH_BUFFER_BYTES = 1024 * 1024
    }
}
