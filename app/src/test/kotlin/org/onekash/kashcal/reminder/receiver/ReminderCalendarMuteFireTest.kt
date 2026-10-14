package org.onekash.kashcal.reminder.receiver

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.work.testing.WorkManagerTestInitHelper
import io.mockk.coEvery
import io.mockk.every
import io.mockk.just
import io.mockk.mockk
import io.mockk.runs
import io.mockk.verify
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.onekash.kashcal.data.db.entity.ReminderStatus
import org.onekash.kashcal.reminder.ReminderAlarmFixture
import org.onekash.kashcal.reminder.ReminderAlarmFixture.Companion.MINUTE
import org.onekash.kashcal.reminder.notification.ReminderNotificationManager
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Tests that a reminder whose calendar was muted after its alarm was armed posts nothing when the
 * alarm fires, while a hidden calendar's reminder still posts.
 *
 * The calendar is muted through the DAO only, as when a scan or feed refresh read the calendar
 * before the toggle, so the alarm is still armed and only the fire-time check stands between it
 * and a notification. Each reminder is due 5 minutes ago for an event 10 minutes ahead (the alarm
 * delivered at or after its time), so no later scan can arm it again.
 *
 * Runs the real receiver handler and scheduler over an in-memory database, with a mocked
 * notification manager.
 */
@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [34])
class ReminderCalendarMuteFireTest {

    private lateinit var fixture: ReminderAlarmFixture
    private lateinit var notificationManager: ReminderNotificationManager
    private val now = System.currentTimeMillis()
    private val start = now + 10 * MINUTE
    private val trigger = now - 5 * MINUTE

    @Before
    fun setup() = runTest {
        val context = ApplicationProvider.getApplicationContext<Context>()
        WorkManagerTestInitHelper.initializeTestWorkManager(context)
        fixture = ReminderAlarmFixture(context)
        fixture.seedCalendar()

        notificationManager = mockk()
        coEvery { notificationManager.buildNotification(any()) } returns mockk()
        every { notificationManager.postNotification(any(), any()) } returns 1
        every { notificationManager.cancelNotification(any()) } just runs
    }

    @After
    fun tearDown() {
        fixture.close()
    }

    private suspend fun fire(reminderId: Long) = withContext(Dispatchers.IO) {
        ReminderAlarmReceiver().handleAlarm(fixture.scheduler, notificationManager, reminderId)
    }

    private fun assertNothingPosted() {
        verify(exactly = 0) { notificationManager.postNotification(any(), any()) }
    }

    /**
     * The row is kept and FIRED, so the next scan's findExisting finds it and doesn't create the
     * reminder again; a deleted row would come back.
     */
    private suspend fun assertFired(reminderId: Long) {
        assertEquals(ReminderStatus.FIRED, fixture.reminderRow(reminderId)?.status)
    }

    @Test
    fun `an armed reminder of a calendar muted afterwards posts nothing`() = runTest {
        val event = fixture.insertEvent("Standup", start, listOf("-PT15M"))
        val rowId = fixture.insertReminderRow(event, start, "-PT15M", trigger)
        fixture.scheduler.scheduleAlarm(rowId, trigger)
        fixture.setMuted(fixture.calendarId, true)

        fire(rowId)

        assertNothingPosted()
        assertFired(rowId)
    }

    @Test
    fun `a snoozed reminder of a muted calendar posts nothing`() = runTest {
        val event = fixture.insertEvent("Snoozed", start, listOf("-PT15M"))
        val rowId = fixture.insertReminderRow(event, start, "-PT15M", trigger, status = ReminderStatus.SNOOZED)
        fixture.setMuted(fixture.calendarId, true)

        fire(rowId)

        assertNothingPosted()
        assertFired(rowId)
    }

    /** A changed occurrence's row, stored under the exception, which has the master's calendar. */
    private suspend fun exceptionReminderRow(): Long {
        val master = fixture.insertEvent("Series", start, listOf("-PT15M"))
        val exceptionId = fixture.database.eventsDao().insert(
            master.copy(id = 0, title = "Moved", originalEventId = master.id, originalInstanceTime = start)
        )
        fixture.database.occurrencesDao().linkException(master.id, start, exceptionId)
        val exception = fixture.database.eventsDao().getById(exceptionId)!!
        return fixture.insertReminderRow(exception, start, "-PT15M", trigger)
    }

    @Test
    fun `a changed occurrence's reminder in a muted calendar posts nothing`() = runTest {
        val rowId = exceptionReminderRow()
        fixture.setMuted(fixture.calendarId, true)

        fire(rowId)

        assertNothingPosted()
        assertFired(rowId)
    }

    @Test
    fun `a changed occurrence's reminder in a calendar that notifies posts`() = runTest {
        val rowId = exceptionReminderRow()

        fire(rowId)

        verify(exactly = 1) { notificationManager.postNotification(any(), any()) }
    }

    @Test
    fun `a hidden calendar's reminder posts`() = runTest {
        val event = fixture.insertEvent("Hidden", start, listOf("-PT15M"))
        val rowId = fixture.insertReminderRow(event, start, "-PT15M", trigger)
        fixture.eventReader.setCalendarVisibility(fixture.calendarId, false)

        fire(rowId)

        verify(exactly = 1) { notificationManager.postNotification(any(), any()) }
        assertEquals(ReminderStatus.FIRED, fixture.reminderRow(rowId)?.status)
    }

    @Test
    fun `unmuting after a muted fire doesn't bring the reminder back`() = runTest {
        val event = fixture.insertEvent("Standup", start, listOf("-PT15M"))
        val rowId = fixture.insertReminderRow(event, start, "-PT15M", trigger)
        fixture.setMuted(fixture.calendarId, true)
        fire(rowId)

        fixture.coordinator().setCalendarMuted(fixture.calendarId, false)

        assertNothingPosted()
        assertTrue(fixture.armedReminders().values.none { it <= now })
        assertFired(rowId)
    }
}
