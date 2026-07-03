package dev.thor.rombutler.domain.model

/** Severity of a log entry. */
enum class LogLevel { SUCCESS, ERROR, INFO }

/** How a logged action can be reverted. */
enum class UndoKind {
    /** Files were extracted from an archive — undo deletes them (only
     *  while the source archive still exists). */
    EXTRACTED,

    /** Files were moved — undo moves them back to their original paths. */
    MOVED,
}

/**
 * Structured revert information attached to a successful sort action.
 *
 * @property createdFiles absolute paths written by the action.
 * @property restoreTo original paths ([UndoKind.MOVED], parallel to
 *   [createdFiles]).
 * @property sourceArchivePath source archive ([UndoKind.EXTRACTED]);
 *   undo is refused once it no longer exists (data would be lost).
 */
data class UndoInfo(
    val kind: UndoKind,
    val createdFiles: List<String>,
    val restoreTo: List<String> = emptyList(),
    val sourceArchivePath: String? = null,
)

/**
 * One entry in the persistent action log (moves, errors, folder creation).
 *
 * @property timestampMillis when it happened (epoch millis).
 * @property level severity for UI coloring.
 * @property message human-readable description.
 * @property undo revert info; `null` for non-revertible entries.
 * @property undone true once the entry was reverted.
 */
data class LogEntry(
    val timestampMillis: Long,
    val level: LogLevel,
    val message: String,
    val undo: UndoInfo? = null,
    val undone: Boolean = false,
)
