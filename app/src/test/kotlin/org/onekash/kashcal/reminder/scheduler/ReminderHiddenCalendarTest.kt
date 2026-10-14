package org.onekash.kashcal.reminder.scheduler

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.onekash.kashcal.reminder.ReminderAlarmFixture
import org.onekash.kashcal.reminder.ReminderAlarmFixture.Companion.DAY
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Tests that hiding a calendar never changes which of its reminders get an alarm: hiding is
 * display only, so a hidden calendar's reminders arm like a visible one's.
 *
 * The case that matters: the user saves an event more than 7 days ahead (outside the window, so no
 * reminder row is written), then hides its calendar in the drawer. When the reminder comes into the
 * window, only the window scan can arm it. Each test seeds that state: the occurrence is 2 days out
 * and has no reminder row yet.
 *
 * Runs the real [ReminderScheduler] over an in-memory database and reads the armed alarms back
 * from Robolectric's AlarmManager.
 */
@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [34])
class ReminderHiddenCalendarTest {

    private lateinit var fixture: ReminderAlarmFixture
    private val now = System.currentTimeMillis()

    @Before
    fun setup() = runTest {
        fixture = ReminderAlarmFixture(ApplicationProvider.getApplicationContext<Context>())
        fixture.seedCalendar()
    }

    @After
    fun tearDown() {
        fixture.close()
    }

    @Test
    fun `the window scan arms a reminder in a visible calendar`() = runTest {
        fixture.insertEvent("Visible", startTs = now + 2 * DAY, reminders = listOf("-PT15M"))

        fixture.scheduler.scheduleUpcomingReminders()

        assertEquals(1, fixture.armedReminders().size)
    }

    @Test
    fun `the window scan arms a reminder in a hidden calendar`() = runTest {
        fixture.insertEvent("Hidden", startTs = now + 2 * DAY, reminders = listOf("-PT15M"))
        fixture.eventReader.setCalendarVisibility(fixture.calendarId, false)

        fixture.scheduler.scheduleUpcomingReminders()

        assertEquals(1, fixture.armedReminders().size)
    }

    @Test
    fun `saving an event in a hidden calendar arms its reminder`() = runTest {
        val event = fixture.insertEvent("Hidden save", startTs = now + 2 * DAY, reminders = listOf("-PT15M"))
        fixture.eventReader.setCalendarVisibility(fixture.calendarId, false)
        val occurrences = fixture.database.occurrencesDao().getForEvent(event.id)

        fixture.scheduler.scheduleRemindersForEvent(event, occurrences, calendarColor = -1)

        assertEquals(1, fixture.armedReminders().size)
    }
}
