package org.onekash.kashcal.sync.parser.icaldav

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.onekash.icaldav.parser.ICalParser
import org.onekash.kashcal.data.db.entity.Event
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.time.Instant

/**
 * [IcsPatcher.patchCancelled]: an attendee's copy of an imported CANCEL is the stored file with
 * STATUS:CANCELLED set and every other line as the server holds it (RFC 5546 §3.2.5).
 */
@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [33])
class StatusTextTest {

    private val unknown = "X-UNMODELLED;X-NOTE=\"keep; this: exactly\":value as sent"
    private val longDescription = "DESCRIPTION:" + "A long agenda line that the server folded. ".repeat(4)

    private val series = listOf(
        "BEGIN:VCALENDAR", "VERSION:2.0", "PRODID:-//Organizer//Client//EN",
        "BEGIN:VEVENT", "UID:planning@example.test", "DTSTAMP:20261101T100000Z",
        "DTSTART:20261201T100000Z", "DTEND:20261201T110000Z", "RRULE:FREQ=DAILY;COUNT=5",
        "SUMMARY:Planning", "SEQUENCE:1", unknown,
        "ORGANIZER:mailto:boss@example.test",
        "ATTENDEE;PARTSTAT=ACCEPTED:mailto:self@example.test",
        "BEGIN:VALARM", "ACTION:DISPLAY", "TRIGGER:-PT15M", "STATUS:NOT-A-VEVENT-STATUS", "END:VALARM",
        "END:VEVENT",
        "BEGIN:VEVENT", "UID:planning@example.test", "DTSTAMP:20261101T100000Z",
        "RECURRENCE-ID:20261202T100000Z", "DTSTART:20261202T140000Z", "DTEND:20261202T150000Z",
        "SUMMARY:Planning moved", "SEQUENCE:1", "STATUS:CONFIRMED",
        "ORGANIZER:mailto:boss@example.test",
        "END:VEVENT",
        "END:VCALENDAR", ""
    ).joinToString("\r\n")

    private val moved = Instant.parse("2026-12-02T10:00:00Z").toEpochMilli()
    private val unchanged = Instant.parse("2026-12-04T10:00:00Z").toEpochMilli()

    private fun unfold(s: String) = s.replace(Regex("""\r?\n[ \t]"""), "")
    private fun parsed(ics: String) = ICalParser().parseAllEvents(ics).getOrNull()!!

    @Test
    fun `a series cancel sets STATUS on every VEVENT and leaves the rest as written`() {
        val out = requireNotNull(IcsPatcher.patchCancelled(series, occurrence = null))

        assertTrue(parsed(out).all { it.status.toICalString() == "CANCELLED" })
        assertTrue("unknown lines kept byte for byte", out.contains(unknown))
        assertTrue("a nested VALARM's STATUS is not a VEVENT's", out.contains("STATUS:NOT-A-VEVENT-STATUS"))
        assertTrue("SEQUENCE unchanged", parsed(out).all { it.sequence == 1 })
        assertEquals(
            "only STATUS lines change",
            unfold(series).lines().filterNot { it.startsWith("STATUS:") },
            unfold(out).lines().filterNot { it.startsWith("STATUS:") },
        )
    }

    @Test
    fun `an occurrence cancel touches only that changed occurrence`() {
        val out = requireNotNull(IcsPatcher.patchCancelled(series, occurrence = moved))

        val events = parsed(out)
        assertEquals("CONFIRMED", events.single { it.recurrenceId == null }.status.toICalString().ifBlank { "CONFIRMED" })
        assertEquals("CANCELLED", events.single { it.recurrenceId != null }.status.toICalString())
    }

    @Test
    fun `an occurrence with no VEVENT gets a cancelled override built from the series`() {
        val occurrenceRow = Event(
            uid = "planning@example.test", calendarId = 1, title = "Planning",
            startTs = unchanged, endTs = unchanged + 3_600_000, dtstamp = 0,
        )

        val out = requireNotNull(IcsPatcher.patchCancelled(series, occurrence = unchanged, override = occurrenceRow))

        val events = parsed(out)
        assertEquals(3, events.size)
        val override = events.single { it.recurrenceId?.timestamp == unchanged }
        assertEquals("CANCELLED", override.status.toICalString())
        assertEquals("Planning", override.summary)
        assertEquals(null, override.rrule)
        assertEquals(unchanged, override.dtStart.timestamp)
        assertTrue("the series is unchanged", out.contains("RRULE:FREQ=DAILY;COUNT=5"))
    }

    @Test
    fun `an occurrence with no VEVENT and no override is refused`() {
        assertNull(IcsPatcher.patchCancelled(series, occurrence = unchanged))
    }

    @Test
    fun `a folded file is patched and refolded`() {
        val folded = series.replace("SUMMARY:Planning\r\n", "SUMMARY:Planning\r\n" +
            longDescription.chunked(70).joinToString("\r\n ") + "\r\n")

        val out = requireNotNull(IcsPatcher.patchCancelled(folded, occurrence = null))

        assertNotNull(parsed(out).single { it.recurrenceId == null }.description)
        assertTrue(out.lines().all { it.toByteArray().size <= 76 })
    }
}
