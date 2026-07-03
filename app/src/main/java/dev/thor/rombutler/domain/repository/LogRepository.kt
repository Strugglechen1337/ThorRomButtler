package dev.thor.rombutler.domain.repository

import dev.thor.rombutler.domain.model.LogEntry
import dev.thor.rombutler.domain.model.LogLevel
import dev.thor.rombutler.domain.model.UndoInfo
import kotlinx.coroutines.flow.Flow

/**
 * Persistent action log. Entries survive app restarts so users can check
 * later what was moved where (and what failed).
 */
interface LogRepository {

    /** All entries, newest first. */
    val entries: Flow<List<LogEntry>>

    /** Appends an entry with the current timestamp. */
    suspend fun append(level: LogLevel, message: String, undo: UndoInfo? = null)

    /** Marks [entry] as reverted (its undo action must not run twice). */
    suspend fun markUndone(entry: LogEntry)

    /** Removes all entries. */
    suspend fun clear()
}
