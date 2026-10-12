package org.onekash.kashcal.data.ics

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * [IcsParserService.parseForImport] keeps what a file import needs and
 * [IcsParserService.parseIcsContent] drops for subscriptions: the METHOD, ORGANIZER and ATTENDEE
 * rows, and cancelled components.
 */
@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [33])
class IcsImportFileTest {

    private fun fixture(name: String): String =
        requireNotNull(javaClass.classLoader?.getResource("ics/itip/$name")) { "missing $name" }.readText()

    private fun vcal(method: String?, vararg vevents: String) =
        "BEGIN:VCALENDAR\r\nVERSION:2.0\r\nPRODID:-//Test//Test//EN\r\n" +
            (method?.let { "METHOD:$it\r\n" } ?: "") + vevents.joinToString("") + "END:VCALENDAR\r\n"

    private fun vevent(vararg lines: String) = "BEGIN:VEVENT\r\n" + lines.joinToString("") { "$it\r\n" } + "END:VEVENT\r\n"

    @Test
    fun `the issue 364 file keeps its METHOD, organizer, attendee and UID`() {
        val file = requireNotNull(IcsParserService.parseForImport(fixture("issue_364_request.ics")))

        assertEquals(ImportMethod.REQUEST, file.method)
        assertTrue(file.isInvitation)
        val group = file.groups.single()
        assertEquals("test-invitation-123@example.com", group.uid)
        val master = group.masters.single()
        assertTrue(group.exceptions.isEmpty())
        assertEquals("organizer@example.com", master.event.organizerEmail)
        val attendee = master.attendees.single()
        assertEquals("mailto:attendee@example.com", attendee.address)
        assertEquals("NEEDS-ACTION", attendee.partstat)
        assertEquals(true, attendee.rsvp)
        assertNotNull("the file's DTSTAMP is kept as itipDtstamp", master.event.itipDtstamp)
    }

    @Test
    fun `a CANCEL without STATUS parses as a live component with its METHOD`() {
        val file = requireNotNull(IcsParserService.parseForImport(fixture("nextcloud_cancel.ics")))

        assertEquals(ImportMethod.CANCEL, file.method)
        val c = file.groups.single().masters.single()
        assertFalse("Nextcloud's CANCEL carries no STATUS", c.isCancelled)
        assertEquals(1, c.event.sequence)
    }

    @Test
    fun `a REPLY keeps its METHOD`() {
        val file = requireNotNull(IcsParserService.parseForImport(fixture("nextcloud_reply.ics")))

        assertEquals(ImportMethod.REPLY, file.method)
        assertFalse(file.isInvitation)
    }

    @Test
    fun `a cancelled component is kept for import while the subscription parse drops it`() {
        val content = vcal(
            null,
            vevent(
                "UID:gone@example.com", "DTSTAMP:20260901T120000Z", "DTSTART:20261001T100000Z",
                "DTEND:20261001T110000Z", "SUMMARY:Gone", "STATUS:CANCELLED"
            )
        )

        val file = requireNotNull(IcsParserService.parseForImport(content))

        assertNull(file.method)
        assertTrue(file.groups.single().masters.single().isCancelled)
        assertTrue(IcsParserService.parseIcsContent(content, 0, 0).isEmpty())
    }

    @Test
    fun `server receipts in the file are cleared`() {
        val content = vcal(
            "REQUEST",
            vevent(
                "UID:stamped@example.com", "DTSTAMP:20260901T120000Z", "DTSTART:20261001T100000Z",
                "DTEND:20261001T110000Z", "SUMMARY:Stamped",
                "ORGANIZER;SCHEDULE-STATUS=1.2:mailto:boss@example.com",
                "ATTENDEE;PARTSTAT=ACCEPTED;SCHEDULE-STATUS=1.2:mailto:me@example.com"
            )
        )

        val c = requireNotNull(IcsParserService.parseForImport(content)).groups.single().masters.single()

        assertNull("a file's ORGANIZER receipt must not read as 'Reply sent'", c.event.organizerScheduleStatus)
        assertEquals("ACCEPTED", c.attendees.single().partstat)
    }

    @Test
    fun `an instance-only CANCEL is a group with no master, and RANGE=THISANDFUTURE is flagged`() {
        val content = vcal(
            "CANCEL",
            vevent(
                "UID:series@example.com", "DTSTAMP:20260901T120000Z", "SEQUENCE:2",
                "RECURRENCE-ID:20261008T100000Z", "DTSTART:20261008T100000Z", "DTEND:20261008T110000Z",
                "ORGANIZER:mailto:boss@example.com", "STATUS:CANCELLED"
            ),
            vevent(
                "UID:series@example.com", "DTSTAMP:20260901T120000Z", "SEQUENCE:2",
                "RECURRENCE-ID;RANGE=THISANDFUTURE:20261015T100000Z", "DTSTART:20261015T100000Z",
                "DTEND:20261015T110000Z", "ORGANIZER:mailto:boss@example.com", "STATUS:CANCELLED"
            )
        )

        val group = requireNotNull(IcsParserService.parseForImport(content)).groups.single()

        assertTrue(group.masters.isEmpty())
        assertEquals(2, group.exceptions.size)
        assertTrue(group.exceptions.all { it.isInstance })
        assertEquals(listOf(false, true), group.exceptions.map { it.thisAndFuture })
    }

    @Test
    fun `a file that doesn't parse returns null`() {
        assertNull(IcsParserService.parseForImport("BEGIN:VCALENDAR\r\nnot ical"))
    }
}
