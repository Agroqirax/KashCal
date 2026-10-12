package org.onekash.kashcal.domain.ics

import org.onekash.kashcal.data.ics.ImportGroup
import org.onekash.kashcal.data.ics.ImportMethod
import org.onekash.kashcal.data.ics.ImportedComponent
import org.onekash.kashcal.util.AddressNormalizer

/** The kind of calendar an import writes to; each applies the iTIP rules differently. */
enum class ImportTarget {
    /** A synced Room calendar (CalDAV, iCloud included): the server's copy stays the truth. */
    CALDAV,

    /** The on-phone Room calendar: nothing is sent, and the file updates it directly. */
    LOCAL,

    /** An Android calendar-provider calendar: rows keep no SEQUENCE or DTSTAMP to compare. */
    DEVICE,
}

/**
 * One stored component of a UID in the target calendar.
 *
 * @param instanceTime null for the master; the occurrence's original start for an exception
 * @param itipDtstamp the DTSTAMP of the invitation that last wrote it, null when unknown
 */
data class StoredComponent(
    val eventId: Long,
    val instanceTime: Long?,
    val organizerEmail: String?,
    val sequence: Int,
    val itipDtstamp: Long?,
    val status: String,
    val pendingDelete: Boolean,
) {
    val isCancelled: Boolean get() = status == ImportedComponent.STATUS_CANCELLED
}

/** What the target calendar holds for one UID: its master, if any, and its exceptions. */
data class StoredUid(
    val master: StoredComponent?,
    val exceptions: List<StoredComponent>,
) {
    val isEmpty: Boolean get() = master == null && exceptions.isEmpty()
}

/**
 * Everything the planner reads for one group.
 *
 * @param stored what the target calendar holds for the group's UID, or null for nothing
 * @param userAddresses the user's addresses canonicalized ([AddressNormalizer.canonical]);
 *   empty for the local calendar, which has no identity
 * @param elsewhereInAccount true when another calendar of the same account holds the UID
 */
data class ImportPlanInput(
    val group: ImportGroup,
    val method: ImportMethod?,
    val stored: StoredUid?,
    val target: ImportTarget,
    val userAddresses: Set<String>,
    val elsewhereInAccount: Boolean = false,
)

/** Why a component changes nothing although the calendar holds it. */
enum class UpToDateReason {
    /** Same SEQUENCE and DTSTAMP as the stored copy. */
    SAME_VERSION,

    /** Older than the stored copy (RFC 5546 §2.1.5). */
    OLDER_VERSION,

    /** A CalDAV or device calendar keeps its own copy; the import only offers Respond. */
    KEPT_CALENDAR_COPY,
}

/** Why a component is not applied. */
enum class IgnoreReason {
    /** A REPLY is for the organizer's copy, not a calendar entry (RFC 5546 §3.2.3). */
    REPLY_FILE,

    /** ADD, REFRESH, COUNTER or DECLINECOUNTER, which import doesn't apply. */
    UNSUPPORTED_METHOD,

    /** The file's ORGANIZER differs from the stored event's. */
    ORGANIZER_MISMATCH,

    /** A CANCEL for an event the calendar doesn't hold. */
    CANCEL_NO_MATCH,

    /** A CANCEL without STATUS that uninvites someone else (RFC 5546 §3.2.5). */
    CANCEL_NOT_FOR_USER,

    /** The matching event is waiting to be deleted. */
    DELETION_PENDING,

    /** A cancelled component with nothing to cancel. */
    CANCELLED_IN_FILE,

    /** RANGE=THISANDFUTURE, which import doesn't apply. */
    THIS_AND_FUTURE,

    /** The user's own CANCEL on a CalDAV calendar: the app's cancel or delete does that. */
    OWN_CANCEL,

    /** An invitation whose UID another calendar of the same account already holds. */
    ON_ANOTHER_CALENDAR,
}

/** What import does with one component (or, for [ImportAction.Create], one whole group). */
sealed interface ImportAction {
    /** Creates the group; under a fresh UID when [freshUid], as another calendar holds it. */
    data class Create(val group: ImportGroup, val freshUid: Boolean) : ImportAction

    /** Rewrites the stored master from the file (local calendar only). */
    data class UpdateSeries(val stored: StoredComponent, val component: ImportedComponent) : ImportAction

    /** Rewrites a stored exception from the file (local calendar only). */
    data class UpdateInstance(val stored: StoredComponent, val component: ImportedComponent) : ImportAction

    /** Adds an exception to a stored series (local calendar only). */
    data class CreateInstance(val master: StoredComponent, val component: ImportedComponent) : ImportAction

    /** Cancels a stored series: its master and every exception. */
    data class CancelSeries(
        val master: StoredComponent,
        val exceptions: List<StoredComponent>,
        val component: ImportedComponent,
    ) : ImportAction

    /** Cancels one occurrence; [stored] is its exception, null when it has none yet. */
    data class CancelInstance(
        val master: StoredComponent,
        val stored: StoredComponent?,
        val component: ImportedComponent,
    ) : ImportAction

    data class UpToDate(val reason: UpToDateReason, val component: ImportedComponent) : ImportAction

    data class Ignored(
        val reason: IgnoreReason,
        val component: ImportedComponent?,
        val fileOrganizer: String? = null,
        val storedOrganizer: String? = null,
    ) : ImportAction
}

/**
 * The plan for one group.
 *
 * @param isInvitation the file is an iTIP REQUEST or CANCEL
 * @param userIsOrganizer the file's ORGANIZER is one of the user's addresses
 * @param userIsAttendee one of the user's addresses is among the group's ATTENDEEs
 */
data class GroupPlan(
    val uid: String,
    val actions: List<ImportAction>,
    val isInvitation: Boolean,
    val userIsOrganizer: Boolean,
    val userIsAttendee: Boolean,
)

/**
 * Decides what an import does with each group of a file, following RFC 5546: match by UID and
 * RECURRENCE-ID, newer by SEQUENCE then DTSTAMP (§2.1.5), a CANCEL never creates (§3.2.5), a
 * REPLY is not a calendar entry (§3.2.3). Pure: callers look up [ImportPlanInput.stored] and
 * apply the actions.
 */
object IcsImportPlanner {

    private enum class Version { NEWER, SAME, OLDER }

    fun plan(input: ImportPlanInput): GroupPlan {
        val group = input.group
        val method = input.method
        val organizer = group.components.firstNotNullOfOrNull { it.event.organizerEmail }
        val userIsOrganizer = organizer != null && canonical(organizer) in input.userAddresses
        val userIsAttendee = group.components.any { c -> c.attendees.any { canonical(it.address) in input.userAddresses } }
        val actions = when (method) {
            ImportMethod.REPLY -> group.components.map { ImportAction.Ignored(IgnoreReason.REPLY_FILE, it) }
            ImportMethod.ADD, ImportMethod.REFRESH, ImportMethod.COUNTER, ImportMethod.DECLINECOUNTER ->
                group.components.map { ImportAction.Ignored(IgnoreReason.UNSUPPORTED_METHOD, it) }
            ImportMethod.CANCEL -> planCancel(input, userIsOrganizer)
            ImportMethod.REQUEST, ImportMethod.PUBLISH, null -> planRequest(input)
        }
        return GroupPlan(
            uid = group.uid,
            actions = actions,
            isInvitation = method == ImportMethod.REQUEST || method == ImportMethod.CANCEL,
            userIsOrganizer = userIsOrganizer,
            userIsAttendee = userIsAttendee,
        )
    }

    private fun planCancel(input: ImportPlanInput, userIsOrganizer: Boolean): List<ImportAction> {
        val components = input.group.components
        if (userIsOrganizer && input.target == ImportTarget.CALDAV) {
            return components.map { ImportAction.Ignored(IgnoreReason.OWN_CANCEL, it) }
        }
        val stored = input.stored
        if (stored == null || stored.isEmpty) {
            return components.map { ImportAction.Ignored(IgnoreReason.CANCEL_NO_MATCH, it) }
        }
        val storedOrganizer = stored.master?.organizerEmail ?: stored.exceptions.firstOrNull()?.organizerEmail
        return components.map { c ->
            val forUser = c.isCancelled ||
                c.attendees.any { canonical(it.address) in input.userAddresses } ||
                (input.target == ImportTarget.LOCAL && sameOrganizer(c.event.organizerEmail, storedOrganizer))
            val master = stored.master
            when {
                c.thisAndFuture -> ImportAction.Ignored(IgnoreReason.THIS_AND_FUTURE, c)
                !forUser -> ImportAction.Ignored(IgnoreReason.CANCEL_NOT_FOR_USER, c)
                !sameOrganizer(c.event.organizerEmail, storedOrganizer) ->
                    ImportAction.Ignored(IgnoreReason.ORGANIZER_MISMATCH, c, c.event.organizerEmail, storedOrganizer)
                master == null -> ImportAction.Ignored(IgnoreReason.CANCEL_NO_MATCH, c)
                master.pendingDelete -> ImportAction.Ignored(IgnoreReason.DELETION_PENDING, c)
                !c.isInstance -> {
                    // A CANCEL MUST carry a higher SEQUENCE (RFC 5546 §3.2.5); only an older one
                    // is skipped, so a same-version CANCEL from a lax sender still cancels.
                    when {
                        version(c, master) == Version.OLDER -> ImportAction.UpToDate(UpToDateReason.OLDER_VERSION, c)
                        master.isCancelled -> ImportAction.UpToDate(UpToDateReason.SAME_VERSION, c)
                        else -> ImportAction.CancelSeries(master, stored.exceptions, c)
                    }
                }
                else -> {
                    val exception = stored.exceptions.firstOrNull { it.instanceTime == c.event.originalInstanceTime }
                    when {
                        version(c, exception ?: master) == Version.OLDER ->
                            ImportAction.UpToDate(UpToDateReason.OLDER_VERSION, c)
                        exception?.isCancelled == true -> ImportAction.UpToDate(UpToDateReason.SAME_VERSION, c)
                        else -> ImportAction.CancelInstance(master, exception, c)
                    }
                }
            }
        }
    }

    private fun planRequest(input: ImportPlanInput): List<ImportAction> {
        val group = input.group
        val stored = input.stored
        val isInvitation = input.method == ImportMethod.REQUEST
        if (stored == null || stored.isEmpty) {
            val allCancelled = if (group.masters.isNotEmpty()) {
                group.masters.all { it.isCancelled }
            } else {
                group.exceptions.all { it.isCancelled }
            }
            return when {
                allCancelled -> group.components.map { ImportAction.Ignored(IgnoreReason.CANCELLED_IN_FILE, it) }
                input.elsewhereInAccount && isInvitation ->
                    group.components.map { ImportAction.Ignored(IgnoreReason.ON_ANOTHER_CALENDAR, it) }
                else -> {
                    // A RANGE=THISANDFUTURE change can't be stored as one changed occurrence.
                    val (later, rest) = group.exceptions.partition { it.thisAndFuture }
                    val kept = group.copy(exceptions = rest)
                    listOfNotNull(
                        ImportAction.Create(kept, freshUid = input.elsewhereInAccount).takeIf { kept.components.isNotEmpty() }
                    ) + later.map { ImportAction.Ignored(IgnoreReason.THIS_AND_FUTURE, it) }
                }
            }
        }
        val master = stored.master
        val storedOrganizer = master?.organizerEmail ?: stored.exceptions.firstOrNull()?.organizerEmail
        return group.components.map { c ->
            when {
                c.thisAndFuture -> ImportAction.Ignored(IgnoreReason.THIS_AND_FUTURE, c)
                master == null -> ImportAction.UpToDate(UpToDateReason.KEPT_CALENDAR_COPY, c)
                master.pendingDelete -> ImportAction.Ignored(IgnoreReason.DELETION_PENDING, c)
                input.target != ImportTarget.LOCAL -> ImportAction.UpToDate(UpToDateReason.KEPT_CALENDAR_COPY, c)
                !c.isInstance -> planLocalSeries(c, master, stored, storedOrganizer)
                else -> planLocalInstance(c, master, stored, storedOrganizer)
            }
        }
    }

    private fun planLocalSeries(
        c: ImportedComponent,
        master: StoredComponent,
        stored: StoredUid,
        storedOrganizer: String?,
    ): ImportAction = when (version(c, master)) {
        Version.OLDER -> ImportAction.UpToDate(UpToDateReason.OLDER_VERSION, c)
        Version.SAME -> ImportAction.UpToDate(UpToDateReason.SAME_VERSION, c)
        Version.NEWER -> when {
            !sameOrganizer(c.event.organizerEmail, storedOrganizer) ->
                ImportAction.Ignored(IgnoreReason.ORGANIZER_MISMATCH, c, c.event.organizerEmail, storedOrganizer)
            c.isCancelled -> ImportAction.CancelSeries(master, stored.exceptions, c)
            else -> ImportAction.UpdateSeries(master, c)
        }
    }

    private fun planLocalInstance(
        c: ImportedComponent,
        master: StoredComponent,
        stored: StoredUid,
        storedOrganizer: String?,
    ): ImportAction {
        val exception = stored.exceptions.firstOrNull { it.instanceTime == c.event.originalInstanceTime }
        val v = version(c, exception ?: master)
        return when {
            v == Version.OLDER -> ImportAction.UpToDate(UpToDateReason.OLDER_VERSION, c)
            exception != null && v == Version.SAME -> ImportAction.UpToDate(UpToDateReason.SAME_VERSION, c)
            !sameOrganizer(c.event.organizerEmail, storedOrganizer) ->
                ImportAction.Ignored(IgnoreReason.ORGANIZER_MISMATCH, c, c.event.organizerEmail, storedOrganizer)
            c.isCancelled -> ImportAction.CancelInstance(master, exception, c)
            exception != null -> ImportAction.UpdateInstance(exception, c)
            else -> ImportAction.CreateInstance(master, c)
        }
    }

    /**
     * Orders a component against the stored copy (RFC 5546 §2.1.5): SEQUENCE first, then DTSTAMP
     * as the tie-breaker when both are known; with an unknown stored DTSTAMP, SEQUENCE decides.
     */
    private fun version(c: ImportedComponent, stored: StoredComponent): Version {
        val fileSequence = c.event.sequence
        if (fileSequence != stored.sequence) {
            return if (fileSequence > stored.sequence) Version.NEWER else Version.OLDER
        }
        val fileStamp = c.event.itipDtstamp
        val storedStamp = stored.itipDtstamp
        if (fileStamp == null || storedStamp == null || fileStamp == storedStamp) return Version.SAME
        return if (fileStamp > storedStamp) Version.NEWER else Version.OLDER
    }

    private fun sameOrganizer(file: String?, stored: String?): Boolean =
        file?.let(::canonical) == stored?.let(::canonical)

    private fun canonical(address: String): String = AddressNormalizer.canonical(address)
}
