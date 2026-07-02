package dev.thor.rombutler.domain.model

/** Severity of a log entry. */
enum class LogLevel { SUCCESS, ERROR, INFO }

/**
 * One entry in the persistent action log (moves, errors, folder creation).
 *
 * @property timestampMillis when it happened (epoch millis).
 * @property level severity for UI coloring.
 * @property message human-readable description (German UI language).
 */
data class LogEntry(
    val timestampMillis: Long,
    val level: LogLevel,
    val message: String,
)
