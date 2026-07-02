package dev.thor.rombutler.data.log

import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import dev.thor.rombutler.di.IoDispatcher
import dev.thor.rombutler.domain.model.LogEntry
import dev.thor.rombutler.domain.model.LogLevel
import dev.thor.rombutler.domain.repository.LogRepository
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

/**
 * [LogRepository] persisting to a plain text file in app-internal storage
 * (`filesDir/action_log.txt`), one entry per line:
 * `<epochMillis>\t<LEVEL>\t<message>` — newlines in messages are escaped.
 */
@Singleton
class FileLogRepository @Inject constructor(
    @param:ApplicationContext private val context: Context,
    @param:IoDispatcher private val ioDispatcher: CoroutineDispatcher,
) : LogRepository {

    private val logFile: File get() = File(context.filesDir, FILE_NAME)
    private val mutex = Mutex()
    private val scope = CoroutineScope(SupervisorJob() + ioDispatcher)

    private val _entries = MutableStateFlow<List<LogEntry>>(emptyList())
    override val entries: StateFlow<List<LogEntry>> = _entries

    init {
        scope.launch { _entries.value = load() }
    }

    override suspend fun append(level: LogLevel, message: String) =
        withContext(ioDispatcher) {
            mutex.withLock {
                val entry = LogEntry(
                    timestampMillis = System.currentTimeMillis(),
                    level = level,
                    message = message,
                )
                logFile.appendText(entry.toLine() + "\n")
                _entries.value = listOf(entry) + _entries.value
            }
        }

    override suspend fun clear() = withContext(ioDispatcher) {
        mutex.withLock {
            logFile.delete()
            _entries.value = emptyList()
        }
    }

    private fun load(): List<LogEntry> {
        if (!logFile.isFile) return emptyList()
        return logFile.readLines()
            .mapNotNull { it.toEntryOrNull() }
            .asReversed() // file is oldest-first, UI wants newest-first
    }

    companion object {
        private const val FILE_NAME = "action_log.txt"

        private fun LogEntry.toLine(): String =
            "$timestampMillis\t$level\t${message.replace("\n", "\\n")}"

        private fun String.toEntryOrNull(): LogEntry? {
            val parts = split('\t', limit = 3)
            if (parts.size != 3) return null
            return LogEntry(
                timestampMillis = parts[0].toLongOrNull() ?: return null,
                level = runCatching { LogLevel.valueOf(parts[1]) }.getOrNull() ?: return null,
                message = parts[2].replace("\\n", "\n"),
            )
        }
    }
}
