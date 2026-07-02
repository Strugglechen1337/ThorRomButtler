package dev.thor.rombutler.domain.repository

import dev.thor.rombutler.domain.model.LogEntry
import dev.thor.rombutler.domain.model.LogLevel
import kotlinx.coroutines.flow.Flow

/**
 * Persistent action log. Entries survive app restarts so users can check
 * later what was moved where (and what failed).
 */
interface LogRepository {

    /** All entries, newest first. */
    val entries: Flow<List<LogEntry>>

    /** Appends an entry with the current timestamp. */
    suspend fun append(level: LogLevel, message: String)

    /** Removes all entries. */
    suspend fun clear()
}
