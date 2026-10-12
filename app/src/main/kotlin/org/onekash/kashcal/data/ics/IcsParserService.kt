package org.onekash.kashcal.data.ics

import android.util.Log
import org.onekash.icaldav.model.ParseResult
import org.onekash.icaldav.parser.ICalParser
import org.onekash.kashcal.data.db.entity.Event
import org.onekash.kashcal.data.db.entity.IcsSubscription
import org.onekash.kashcal.sync.parser.icaldav.ICalEventMapper

private const val TAG = "IcsParserService"

/**
 * Parses ICS subscription feeds and imported ICS files with the icaldav [ICalParser], which
 * includes exception events (RECURRENCE-ID).
 *
 * [parseIcsContent] drops CANCELLED events and attendees: ICS subscriptions are read-only, so
 * cancelled events should not appear. [parseForImport] keeps both for a file import.
 */
object IcsParserService {

    private val parser = ICalParser()

    /**
     * Returns true if [content] has a VCALENDAR wrapper and at least one VEVENT or VTODO.
     *
     * A substring check only; it doesn't parse.
     */
    fun isValidIcs(content: String): Boolean {
        return content.contains("BEGIN:VCALENDAR") &&
            content.contains("END:VCALENDAR") &&
            (content.contains("BEGIN:VEVENT") || content.contains("BEGIN:VTODO"))
    }

    /**
     * Parses [content] into events, without CANCELLED ones; returns an empty list if parsing fails.
     *
     * @param calendarId assigned to every parsed event.
     * @param subscriptionId builds each event's `caldavUrl` source key
     *   ([IcsSubscription.eventSourcePrefix] plus the event's import id).
     */
    fun parseIcsContent(
        content: String,
        calendarId: Long,
        subscriptionId: Long
    ): List<Event> {
        val result = parser.parseAllEvents(content)
        return when (result) {
            is ParseResult.Success -> {
                val events = result.value
                    .filter { it.status.toICalString() != "CANCELLED" }
                    .map { icalEvent ->
                        // Attendees are dropped: subscriptions are read-only copies; a file
                        // import reads them through [parseForImport].
                        ICalEventMapper.toEntity(
                            icalEvent = icalEvent,
                            rawIcal = null,
                            calendarId = calendarId,
                            caldavUrl = "${IcsSubscription.eventSourcePrefix(subscriptionId)}${icalEvent.importId}",
                            etag = null
                        ).event
                    }
                Log.d(TAG, "Parsed ${events.size} events from ICS (filtered ${result.value.size - events.size} cancelled)")
                events
            }
            is ParseResult.Error -> {
                Log.e(TAG, "Failed to parse ICS content: ${result.error.message}")
                emptyList()
            }
        }
    }

    /**
     * Parses an ICS file for a one-shot import, keeping what [parseIcsContent] drops for
     * subscriptions: the METHOD, ORGANIZER and ATTENDEE rows, and CANCELLED components (a
     * cancellation has to reach the event it cancels). Returns null when the file doesn't parse.
     *
     * Each component is mapped as the pull maps it, with `calendarId` 0 and no URL or stored
     * copy, its `itipDtstamp` set to the file's DTSTAMP, and the file's SCHEDULE-STATUS receipts
     * cleared (they describe a server's delivery, not this import). An exception is mapped with
     * its group's master DTSTART so its RECURRENCE-ID normalizes as the pull's does.
     */
    fun parseForImport(content: String): IcsImportFile? {
        val result = parser.parseWithMethod(content)
        if (result !is ParseResult.Success) {
            Log.e(TAG, "Failed to parse ICS file for import: ${(result as? ParseResult.Error)?.error?.message}")
            return null
        }
        val thisAndFuture = thisAndFutureKeys(content)
        val groups = result.value.events.groupBy { it.uid }.map { (uid, icalEvents) ->
            val masters = icalEvents.filter { it.recurrenceId == null }
            val masterDtStart = masters.singleOrNull()?.dtStart
            fun component(icalEvent: org.onekash.icaldav.model.ICalEvent): ImportedComponent {
                val mapped = ICalEventMapper.toEntity(
                    icalEvent = icalEvent,
                    rawIcal = null,
                    calendarId = 0,
                    caldavUrl = null,
                    etag = null,
                    masterDtStart = if (icalEvent.recurrenceId != null) masterDtStart else null,
                )
                val recurrenceDigits = icalEvent.recurrenceId?.toICalString()?.let(::digitsOf)
                return ImportedComponent(
                    event = mapped.event.copy(
                        organizerScheduleStatus = null,
                        itipDtstamp = icalEvent.dtstamp?.timestamp,
                    ),
                    attendees = mapped.attendees.map { row ->
                        ImportedAttendee(
                            address = row.address,
                            displayName = row.displayName,
                            role = row.role,
                            partstat = row.partstat,
                            cutype = row.cutype,
                            rsvp = row.rsvp,
                            delegatedFrom = row.delegatedFrom,
                            delegatedTo = row.delegatedTo,
                            member = row.member,
                            sentBy = row.sentBy,
                        )
                    },
                    thisAndFuture = recurrenceDigits != null && "$uid|$recurrenceDigits" in thisAndFuture,
                )
            }
            ImportGroup(
                uid = uid,
                masters = masters.map(::component),
                exceptions = icalEvents.filter { it.recurrenceId != null }.map(::component),
            )
        }
        return IcsImportFile(ImportMethod.fromValue(result.value.method?.name), groups)
    }

    /**
     * Returns "uid|digits" keys for every RECURRENCE-ID in [content] that carries
     * RANGE=THISANDFUTURE: the parsed model drops the RANGE parameter (RFC 5545 §3.2.13), so it
     * is read from the unfolded text, one VEVENT at a time.
     */
    private fun thisAndFutureKeys(content: String): Set<String> {
        val unfolded = content.replace(Regex("""\r?\n[ \t]"""), "")
        return Regex("""BEGIN:VEVENT(.*?)END:VEVENT""", RegexOption.DOT_MATCHES_ALL)
            .findAll(unfolded)
            .mapNotNull { block ->
                val lines = block.groupValues[1].lines()
                val uid = lines.firstOrNull { it.startsWith("UID:", ignoreCase = true) }
                    ?.substringAfter(':')?.trim() ?: return@mapNotNull null
                val recurrenceId = lines.firstOrNull { it.startsWith("RECURRENCE-ID", ignoreCase = true) }
                    ?: return@mapNotNull null
                val params = recurrenceId.substringBefore(':')
                if (!params.contains("RANGE=THISANDFUTURE", ignoreCase = true)) return@mapNotNull null
                "$uid|${digitsOf(recurrenceId.substringAfterLast(':'))}"
            }
            .toSet()
    }

    private fun digitsOf(value: String): String = value.filter { it.isDigit() }.take(14)

    /**
     * Returns the calendar name from [content], or null if it doesn't parse or has none.
     *
     * Prefers RFC 7986 NAME over X-WR-CALNAME (`ICalCalendar.effectiveName`), falling back to
     * PRODID when neither is set. Only tests call it.
     */
    fun getCalendarName(content: String): String? {
        val calendar = parser.parse(content).getOrNull() ?: return null
        calendar.effectiveName?.let { return it }
        return calendar.prodId?.takeIf { it.isNotBlank() }
    }
}
