package org.onekash.kashcal.data.calendar_provider

import android.Manifest
import android.content.Context
import android.provider.CalendarContract.Events
import android.provider.CalendarContract.Reminders
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/**
 * Tests [AndroidCalendarProviderRepository.getDueAndNextReminders] and the tie-break of
 * [AndroidCalendarProviderRepository.getNextUpcomingReminder] over [SqliteCalendarProvider]: which
 * device reminders are due after a fired one, which is next, and that both use the
 * [DeviceReminderCursor] order so the reminder armed next and the ones posted with it never
 * overlap or leave one out.
 */
@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [34])
class AndroidCalendarProviderRepositoryDueRemindersTest {

    private companion object {
        const val CAL_A = 1L
        const val CAL_B = 2L
        const val MINUTE = 60_000L
        const val HOUR = 60 * MINUTE
    }

    private lateinit var provider: SqliteCalendarProvider
    private lateinit var repository: AndroidCalendarProviderRepository

    // A fixed instant a day ahead, so every time below is in the future of the real clock.
    private val base = (System.currentTimeMillis() / HOUR + 24) * HOUR

    @Before
    fun setup() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        shadowOf(context as android.app.Application).grantPermissions(Manifest.permission.READ_CALENDAR)
        provider = SqliteCalendarProvider.install()
        provider.seedCalendar(CAL_A)
        provider.seedCalendar(CAL_B)
        repository = SqliteCalendarProvider.repository(context)
    }

    @After
    fun tearDown() = provider.db.close()

    /** An event in [calendarId] starting at [start] with one reminder per entry of [minutes]. */
    private fun event(calendarId: Long, start: Long, vararg minutes: Int, end: Long = start + HOUR): Long {
        val id = provider.insertRow(
            SqliteCalendarProvider.EVENTS,
            Events.CALENDAR_ID to calendarId, Events.TITLE to "Event", Events.DTSTART to start,
            Events.DTEND to end, Events.EVENT_TIMEZONE to "UTC", Events.ALL_DAY to 0,
            Events.STATUS to Events.STATUS_CONFIRMED,
        )
        for (m in minutes) {
            provider.insertRow(
                SqliteCalendarProvider.REMINDERS,
                Reminders.EVENT_ID to id, Reminders.MINUTES to m, Reminders.METHOD to Reminders.METHOD_ALERT,
            )
        }
        return id
    }

    private val both = setOf(CAL_A, CAL_B)

    private suspend fun batch(after: DeviceReminderCursor, nowMs: Long, limit: Int = 20, calendars: Set<Long> = both) =
        repository.getDueAndNextReminders(calendars, after, nowMs, limit)

    @Test
    fun `a reminder due at the same instant as the fired one is due after it, and none after the last`() = runTest {
        val start = base + HOUR
        val a = event(CAL_A, start, 60)
        val b = event(CAL_B, start, 60)
        val fired = DeviceReminderCursor(base, a, start)

        assertEquals(listOf(b), batch(fired, nowMs = base + 1).due.map { it.eventId })
        assertEquals(emptyList<Long>(), batch(DeviceReminderCursor(base, b, start), nowMs = base + 1).due.map { it.eventId })
    }

    @Test
    fun `a reminder at now is due and one a millisecond later is next`() = runTest {
        val atNow = event(CAL_A, base + HOUR, 60)
        val later = event(CAL_A, base + HOUR + 1, 60)
        val fired = DeviceReminderCursor(base - 10 * MINUTE, 0, 0)

        val result = batch(fired, nowMs = base)

        assertEquals(listOf(atNow), result.due.map { it.eventId })
        assertEquals(later, result.next?.eventId)
        assertEquals(base + 1, result.next?.triggerTime)
    }

    @Test
    fun `a reminder ordered before the fired one at the same trigger is not due`() = runTest {
        val start = base + HOUR
        val first = event(CAL_A, start, 60)
        val second = event(CAL_B, start, 60)

        val result = batch(DeviceReminderCursor(base, second, start), nowMs = base + MINUTE)

        assertFalse(result.due.any { it.eventId == first })
    }

    @Test
    fun `a calendar that isn't enabled has no due reminders`() = runTest {
        event(CAL_B, base + HOUR, 60)

        val result = batch(DeviceReminderCursor(base - MINUTE, 0, 0), nowMs = base, calendars = setOf(CAL_A))

        assertTrue(result.due.isEmpty())
        assertNull(result.next)
    }

    @Test
    fun `the limit keeps the earliest due reminders and makes the first left out next`() = runTest {
        val e1 = event(CAL_A, base + HOUR, 60)
        val e2 = event(CAL_A, base + HOUR + MINUTE, 60)
        val e3 = event(CAL_A, base + HOUR + 2 * MINUTE, 60)
        val e4 = event(CAL_A, base + HOUR + 3 * MINUTE, 60)
        val ahead = event(CAL_A, base + 2 * HOUR, 60)

        val result = batch(DeviceReminderCursor(base - MINUTE, 0, 0), nowMs = base + 3 * MINUTE, limit = 2)

        assertEquals(listOf(e1, e2), result.due.map { it.eventId })
        assertTrue(result.overflowed)
        assertEquals(e3, result.next?.eventId)
        // The delivery for e3 shows e3 itself, then the rest.
        val followOn = batch(result.next!!.cursor, nowMs = base + 4 * MINUTE, limit = 2)
        assertEquals(listOf(e4), followOn.due.map { it.eventId })
        assertEquals(ahead, followOn.next?.eventId)
        assertFalse(batch(DeviceReminderCursor(base - MINUTE, 0, 0), nowMs = base + 3 * MINUTE, limit = 4).overflowed)
    }

    @Test
    fun `two reminder rows with the same minutes on one event give one due reminder`() = runTest {
        val e = event(CAL_A, base + HOUR, 60, 60)

        val result = batch(DeviceReminderCursor(base - MINUTE, 0, 0), nowMs = base)

        assertEquals(listOf(e), result.due.map { it.eventId })
    }

    @Test
    fun `equal triggers break by event id, so next and due agree`() = runTest {
        // Both remind at base: the larger id starts earlier, so the provider lists it first.
        val smallerId = event(CAL_A, base + HOUR, 60)
        val largerIdStartsFirst = event(CAL_B, base + 10 * MINUTE, 10)

        val next = repository.getNextUpcomingReminder(both, afterMs = base - MINUTE)

        assertEquals(smallerId, next?.eventId)
        assertEquals(
            listOf(largerIdStartsFirst),
            batch(next!!.cursor, nowMs = base).due.map { it.eventId }
        )
    }

    @Test
    fun `a fired cursor after now still finds the next reminder before it`() = runTest {
        val soon = event(CAL_A, base + 2 * HOUR, 60)
        val fired = event(CAL_A, base + 3 * HOUR, 60)
        val firedCursor = DeviceReminderCursor(base + 2 * HOUR, fired, base + 3 * HOUR)

        assertEquals(soon, batch(firedCursor, nowMs = base).next?.eventId)
    }

    @Test
    fun `next is not limited by the fired cursor, so with nothing before it the fired reminder is next`() = runTest {
        // Pinned as today: a delivery whose trigger is still ahead re-arms itself.
        val fired = event(CAL_A, base + 3 * HOUR, 60)
        val firedCursor = DeviceReminderCursor(base + 2 * HOUR, fired, base + 3 * HOUR)

        assertEquals(fired, batch(firedCursor, nowMs = base).next?.eventId)
    }

    @Test
    fun `no enabled calendar gives an empty batch`() = runTest {
        event(CAL_A, base + HOUR, 60)

        val result = batch(DeviceReminderCursor(base - MINUTE, 0, 0), nowMs = base + MINUTE, calendars = emptySet())

        assertTrue(result.due.isEmpty())
        assertNull(result.next)
        assertFalse(result.overflowed)
    }

    @Test
    fun `a failed Instances read gives an empty batch`() = runTest {
        event(CAL_A, base + HOUR, 60)
        provider.queryFailures[SqliteCalendarProvider.INSTANCES] = SqliteCalendarProvider.QueryFailure.THROW

        assertEquals(DeviceReminderBatch.EMPTY, batch(DeviceReminderCursor(base - MINUTE, 0, 0), nowMs = base))
    }

    @Test
    fun `reminders of several events come from one Reminders read`() = runTest {
        val a = event(CAL_A, base + HOUR, 60, 30)
        val b = event(CAL_B, base + HOUR, 60)

        val result = batch(DeviceReminderCursor(base - MINUTE, 0, 0), nowMs = base + 30 * MINUTE)

        assertEquals(listOf(a, b, a), result.due.map { it.eventId })
        assertEquals(listOf(60, 60, 30), result.due.map { it.reminderMinutes })
    }
}
