package dev.thor.rombutler.data.files

import dev.thor.rombutler.di.IoDispatcher
import dev.thor.rombutler.domain.repository.ArchiveMover
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException
import javax.inject.Inject
import javax.inject.Singleton

/**
 * [ArchiveMover] on plain [java.io.File].
 *
 * Error handling is deliberately conservative:
 * - an existing target file is NEVER overwritten (fails with a clear message)
 * - the cross-volume fallback deletes the source only after the copy
 *   completed and the size matches — an aborted copy leaves the original
 *   untouched and removes the partial target file.
 */
@Singleton
class FileArchiveMover @Inject constructor(
    @param:IoDispatcher private val ioDispatcher: CoroutineDispatcher,
) : ArchiveMover {

    override suspend fun move(sourcePath: String, targetDir: String): Result<String> =
        withContext(ioDispatcher) {
            runCatching {
                val source = File(sourcePath)
                if (!source.isFile) {
                    throw IOException("Quelldatei nicht gefunden: $sourcePath")
                }
                val dir = File(targetDir)
                if (!dir.isDirectory && !dir.mkdirs()) {
                    throw IOException("Zielordner konnte nicht angelegt werden: $targetDir")
                }
                val target = File(dir, source.name)
                if (target.exists()) {
                    throw IOException("Ziel existiert bereits: ${target.absolutePath}")
                }

                // Fast path: same volume
                if (source.renameTo(target)) {
                    return@runCatching target.absolutePath
                }

                // Cross-volume fallback: copy, verify, then delete source
                try {
                    source.inputStream().use { input ->
                        target.outputStream().use { output ->
                            input.copyTo(output, bufferSize = COPY_BUFFER_BYTES)
                        }
                    }
                    if (target.length() != source.length()) {
                        throw IOException("Kopie unvollständig (Größe weicht ab)")
                    }
                } catch (e: Exception) {
                    target.delete() // never leave partial files behind
                    throw e
                }
                if (!source.delete()) {
                    // Copy succeeded but the source is stuck — report it,
                    // the target is valid.
                    throw IOException(
                        "Verschoben nach ${target.absolutePath}, aber Quelle konnte nicht gelöscht werden",
                    )
                }
                target.absolutePath
            }
        }

    companion object {
        private const val COPY_BUFFER_BYTES = 1024 * 1024 // 1 MiB for large archives
    }
}
