package org.onekash.kashcal.domain.ics

/**
 * What one file import did, counted by event or occurrence. Room and device imports return the
 * same shape so the sheet can describe either.
 *
 * @param ignored the reason for each component left unapplied, in file order
 * @param failed components whose write threw; the rest of the file still imported
 * @param firstStartTs the start of the first created or changed event, for the caller's date jump
 */
data class IcsImportResult(
    val created: Int = 0,
    val updated: Int = 0,
    val upToDate: Int = 0,
    val cancelled: Int = 0,
    val ignored: List<IgnoreReason> = emptyList(),
    val failed: Int = 0,
    val firstStartTs: Long? = null,
) {
    /** True when the import wrote anything. */
    val changedAnything: Boolean get() = created + updated + cancelled > 0

    operator fun plus(other: IcsImportResult) = IcsImportResult(
        created = created + other.created,
        updated = updated + other.updated,
        upToDate = upToDate + other.upToDate,
        cancelled = cancelled + other.cancelled,
        ignored = ignored + other.ignored,
        failed = failed + other.failed,
        firstStartTs = firstStartTs ?: other.firstStartTs,
    )
}
