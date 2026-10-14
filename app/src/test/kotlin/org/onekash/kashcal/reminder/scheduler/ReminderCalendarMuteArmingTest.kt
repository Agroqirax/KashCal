package org.onekash.kashcal.reminder.scheduler

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import io.mockk.coEvery
import io.mockk.spyk
import io.mockk.verify
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.onekash.kashcal.data.db.dao.CalendarsDao
import org.onekash.kashcal.data.db.dao.ScheduledRemindersDao
import org.onekash.kashcal.data.db.entity.Calendar
import org.onekash.kashcal.data.db.entity.ReminderStatus
import org.onekash.kashcal.domain.coordinator.EventCoordinator
import org.onekash.kashcal.reminder.ReminderAlarmFixture
import org.onekash.kashcal.reminder.ReminderAlarmFixture.Companion.DAY
import org.onekash.kashcal.reminder.ReminderAlarmFixture.Companion.MINUTE
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Tests that a muted calendar's reminders are kept off AlarmManager while it is muted, and that
 * unmuting arms the next 7 days at once.
 *
 * - The window scan and the save path arm nothing for a muted calendar.
 * - The scan first sweeps alarms still armed for a muted calendar (left by a race or a failed
 *   toggle), keeping the rows and dismissing those whose time passed; a calendar unmuted while
 *   the sweep reads still keeps its alarms, and a failing sweep doesn't stop the other
 *   calendars from being armed. The unmute racing the sweep's cancel: MuteUnmuteReminderTest.
 * - [EventCoordinator.setCalendarMuted] cancels on mute (keeping the rows) and arms on unmute,
 *   applies the step for the flag's current value under one lock, and leaves notifications on
 *   screen alone. Restoring the kept rows on unmute: MuteUnmuteReminderTest.
 *
 * Runs the real [ReminderScheduler] and [EventCoordinator] over an in-memory database and reads the
 * armed alarms back from Robolectric's AlarmManager. The coordinator's other collaborators are
 * strict mocks: muting must not touch them.
 */
@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [34])
class ReminderCalendarMuteArmingTest {

    private lateinit var context: Context
    private lateinit var fixture: ReminderAlarmFixture
    private var otherCalendarId: Long = 0
    private val now = System.currentTimeMillis()

    @Before
    fun setup() = runTest {
        context = ApplicationProvider.getApplicationContext()
        fixture = ReminderAlarmFixture(context)
        fixture.seedCalendar()
        otherCalendarId = fixture.addCalendar("Other")
    }

    @After
    fun tearDown() {
        fixture.close()
    }

    private fun coordinator(scheduler: ReminderScheduler = fixture.scheduler) =
        fixture.coordinator(scheduler)

    /** Seeds an event 2 days out with a -PT15M reminder row and its alarm; returns the row id. */
    private suspend fun armedReminder(title: String, calendarId: Long): Long {
        val start = now + 2 * DAY
        val event = fixture.insertEvent(title, start, listOf("-PT15M"), calendarId = calendarId)
        val rowId = fixture.insertReminderRow(event, start, "-PT15M", start - 15 * MINUTE)
        fixture.scheduler.scheduleAlarm(rowId, start - 15 * MINUTE)
        return rowId
    }

    private fun verifyNoNotificationCleared() {
        verify(exactly = 0) { fixture.channels.cancelForReminder(any()) }
        verify(exactly = 0) { fixture.channels.cancel(any()) }
    }

    // ==================== Arm time ====================

    @Test
    fun `the window scan arms nothing for a muted calendar and still arms the others`() = runTest {
        val muted = fixture.insertEvent("Muted", now + 2 * DAY, listOf("-PT15M"))
        fixture.insertEvent("Other", now + 2 * DAY, listOf("-PT15M"), calendarId = otherCalendarId)
        fixture.setMuted(fixture.calendarId, true)

        fixture.scheduler.scheduleUpcomingReminders()

        assertEquals(listOf(now + 2 * DAY - 15 * MINUTE), fixture.armedReminders().values.toList())
        assertEquals(0, fixture.database.scheduledRemindersDao().getPendingForEvent(muted.id).size)
    }

    @Test
    fun `saving an event in a muted calendar writes no reminder row and arms nothing`() = runTest {
        val event = fixture.insertEvent("Muted save", now + 2 * DAY, listOf("-PT15M"))
        fixture.setMuted(fixture.calendarId, true)
        val occurrences = fixture.database.occurrencesDao().getForEvent(event.id)

        fixture.scheduler.scheduleRemindersForEvent(event, occurrences, calendarColor = -1)

        assertEquals(0, fixture.reminderRowCount())
        assertTrue(fixture.armedReminders().isEmpty())
    }

    @Test
    fun `saving an exception event in a muted calendar arms nothing`() = runTest {
        val start = now + 2 * DAY
        val master = fixture.insertEvent("Series", start, listOf("-PT15M"))
        val exceptionId = fixture.database.eventsDao().insert(
            master.copy(id = 0, title = "Moved", originalEventId = master.id, originalInstanceTime = start)
        )
        val exception = fixture.database.eventsDao().getById(exceptionId)!!
        fixture.setMuted(fixture.calendarId, true)
        val occurrences = fixture.database.occurrencesDao().getForEvent(master.id)

        fixture.scheduler.scheduleRemindersForEvent(exception, occurrences, calendarColor = -1)

        assertEquals(0, fixture.reminderRowCount())
        assertTrue(fixture.armedReminders().isEmpty())
    }

    // ==================== Sweep ====================

    @Test
    fun `the window scan sweeps an alarm left armed for a muted calendar`() = runTest {
        val mutedRow = armedReminder("Muted", fixture.calendarId)
        val otherRow = armedReminder("Other", otherCalendarId)
        fixture.setMuted(fixture.calendarId, true)

        fixture.scheduler.scheduleUpcomingReminders()

        val armed = fixture.armedReminders()
        assertFalse(mutedRow in armed)
        assertEquals("the row is kept for the unmute", ReminderStatus.PENDING, fixture.reminderRow(mutedRow)?.status)
        assertTrue(otherRow in armed)
    }

    @Test
    fun `the sweep runs when the muted calendar is the only one with reminders`() = runTest {
        // The occurrence query then returns nothing, which ends the scan early.
        val mutedRow = armedReminder("Muted", fixture.calendarId)
        fixture.setMuted(fixture.calendarId, true)

        fixture.scheduler.scheduleUpcomingReminders()

        assertTrue(fixture.armedReminders().isEmpty())
        assertEquals(ReminderStatus.PENDING, fixture.reminderRow(mutedRow)?.status)
    }

    @Test
    fun `the scan never arms a past reminder of a muted calendar`() = runTest {
        // Arming it at once and cancelling it later would still cost a wake-up, so the sweep
        // has to run before anything that re-arms existing rows.
        val start = now + 10 * MINUTE
        val event = fixture.insertEvent("Muted past", start, listOf("-PT20M"))
        val rowId = fixture.insertReminderRow(event, start, "-PT20M", now - 10 * MINUTE)
        fixture.setMuted(fixture.calendarId, true)
        val scheduler = spyk(fixture.scheduler)

        scheduler.scheduleUpcomingReminders()

        verify(exactly = 0) { scheduler.scheduleAlarm(rowId, any()) }
        // Its time has passed and its calendar is muted: dismissed, so it can't post late.
        assertEquals(ReminderStatus.DISMISSED, fixture.reminderRow(rowId)?.status)
    }

    @Test
    fun `a failing sweep still arms the other calendars`() = runTest {
        val real = fixture.database.scheduledRemindersDao()
        val failing = object : ScheduledRemindersDao by real {
            override suspend fun getMutedCalendarIdsWithPendingReminders(): List<Long> =
                throw IllegalStateException("disk")
        }
        val scheduler = schedulerWith(remindersDao = failing)
        fixture.insertEvent("Other", now + 2 * DAY, listOf("-PT15M"), calendarId = otherCalendarId)
        fixture.setMuted(fixture.calendarId, true)

        scheduler.scheduleUpcomingReminders()

        assertEquals(listOf(now + 2 * DAY - 15 * MINUTE), fixture.armedReminders().values.toList())
    }

    @Test
    fun `a calendar unmuted during the sweep's read keeps its rows and alarms`() = runTest {
        val rowId = armedReminder("Unmuted meanwhile", fixture.calendarId)
        fixture.setMuted(fixture.calendarId, true)
        val real = fixture.database.scheduledRemindersDao()
        // The unmute lands between the sweep's read of the rows and its check of the flag.
        val unmuteAfterRead = object : ScheduledRemindersDao by real {
            override suspend fun getPendingForMutedCalendar(calendarId: Long) =
                real.getPendingForMutedCalendar(calendarId).also { fixture.setMuted(calendarId, false) }
        }
        val scheduler = schedulerWith(remindersDao = unmuteAfterRead)

        scheduler.scheduleUpcomingReminders()

        assertNotNull(fixture.reminderRow(rowId))
        assertTrue(rowId in fixture.armedReminders())
    }

    private fun schedulerWith(
        remindersDao: ScheduledRemindersDao = fixture.database.scheduledRemindersDao(),
        calendarsDao: CalendarsDao = fixture.database.calendarsDao(),
    ) = ReminderScheduler(
        context = context,
        scheduledRemindersDao = remindersDao,
        eventReader = fixture.eventReader,
        channels = fixture.channels,
        attendeesDao = fixture.database.attendeesDao(),
        accountsDao = fixture.database.accountsDao(),
        calendarsDao = calendarsDao,
        occurrencesDao = fixture.database.occurrencesDao()
    )

    // ==================== Toggle ====================

    @Test
    fun `muting cancels only that calendar's alarms and clears no notification`() = runTest {
        val pendingRow = armedReminder("Pending", fixture.calendarId)
        val start = now + 3 * DAY
        val snoozedEvent = fixture.insertEvent("Snoozed", start, listOf("-PT15M"))
        val snoozedRow = fixture.insertReminderRow(
            snoozedEvent, start, "-PT15M", now + 10 * MINUTE, status = ReminderStatus.SNOOZED
        )
        fixture.scheduler.scheduleAlarm(snoozedRow, now + 10 * MINUTE)
        val otherRow = armedReminder("Other", otherCalendarId)

        coordinator().setCalendarMuted(fixture.calendarId, true)

        assertTrue(fixture.database.calendarsDao().getById(fixture.calendarId)!!.isNotificationMuted)
        val armed = fixture.armedReminders()
        assertFalse(pendingRow in armed)
        assertFalse(snoozedRow in armed)
        // The rows stay, so unmuting can arm them again.
        assertEquals(ReminderStatus.PENDING, fixture.reminderRow(pendingRow)?.status)
        assertEquals(ReminderStatus.SNOOZED, fixture.reminderRow(snoozedRow)?.status)
        assertTrue(otherRow in armed)
        verifyNoNotificationCleared()
    }

    @Test
    fun `unmuting arms the calendar's next 7 days at once`() = runTest {
        fixture.insertEvent("Tomorrow", now + DAY, listOf("-PT15M"))
        fixture.insertEvent("Later", now + 5 * DAY, listOf("-PT15M"))
        fixture.setMuted(fixture.calendarId, true)
        fixture.scheduler.scheduleUpcomingReminders()
        assertTrue(fixture.armedReminders().isEmpty())

        coordinator().setCalendarMuted(fixture.calendarId, false)

        assertEquals(
            setOf(now + DAY - 15 * MINUTE, now + 5 * DAY - 15 * MINUTE),
            fixture.armedReminders().values.toSet()
        )
    }

    @Test
    fun `unmuting doesn't bring back a reminder whose time passed while muted`() = runTest {
        // The reminder time was 5 minutes ago; the event starts in 10.
        fixture.insertEvent("Soon", now + 10 * MINUTE, listOf("-PT15M"))
        fixture.setMuted(fixture.calendarId, true)

        coordinator().setCalendarMuted(fixture.calendarId, false)

        assertEquals(0, fixture.reminderRowCount())
        assertTrue(fixture.armedReminders().isEmpty())
    }

    @Test
    fun `a mute and an unmute in quick succession end with the alarms matching the flag`() = runTest {
        fixture.insertEvent("Tomorrow", now + DAY, listOf("-PT15M"))
        fixture.scheduler.scheduleUpcomingReminders()
        val scheduler = spyk(fixture.scheduler)
        val cancelStarted = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        // Holds the mute's cancel open until the unmute has been started. The real scheduler
        // runs the cancel: a spy's callOriginal() after a suspension breaks its continuation.
        coEvery { scheduler.silenceRemindersForCalendar(any()) } coAnswers {
            cancelStarted.complete(Unit)
            release.await()
            fixture.scheduler.silenceRemindersForCalendar(firstArg())
        }
        val coordinator = coordinator(scheduler)

        withContext(Dispatchers.IO) {
            withTimeout(10_000) {
                val mute = async { coordinator.setCalendarMuted(fixture.calendarId, true) }
                cancelStarted.await()
                val unmute = async { coordinator.setCalendarMuted(fixture.calendarId, false) }
                // Without the lock the unmute finishes its scan here, before the cancel runs.
                withTimeoutOrNull(500) { unmute.await() }
                release.complete(Unit)
                awaitAll(mute, unmute)
            }
        }

        val muted = fixture.database.calendarsDao().getById(fixture.calendarId)!!.isNotificationMuted
        val armed = fixture.armedReminders().isNotEmpty()
        assertEquals("the alarms must match the final flag (muted=$muted)", !muted, armed)
    }
}
