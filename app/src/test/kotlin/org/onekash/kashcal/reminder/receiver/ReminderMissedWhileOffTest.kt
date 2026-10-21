package org.onekash.kashcal.reminder.receiver

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.onekash.kashcal.data.db.entity.ReminderStatus
import org.onekash.kashcal.reminder.ReminderAlarmFixture
import org.onekash.kashcal.reminder.ReminderAlarmFixture.Companion.HOUR
import org.onekash.kashcal.reminder.ReminderAlarmFixture.Companion.MINUTE
import org.onekash.kashcal.reminder.device.DeviceCalendarReminderScheduler
import org.onekash.kashcal.reminder.scheduler.ReminderScheduler
import org.onekash.kashcal.widget.WidgetUpdateManager
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Tests what happens to a reminder whose trigger time passed while the phone was off.
 *
 * The phone being off or out of battery across a trigger time is the real input: the alarm is
 * gone after the restart, and the reminder row is still PENDING. When the event hasn't ended,
 * the user still needs the alert, so recovery has to show it (arm it to fire now). An alert for
 * an event that is already over is noise and stays unshown.
 *
 * Runs the real [ReminderScheduler] and [BootRecoveryHandler] over an in-memory database and
 * reads the armed alarms back from Robolectric's AlarmManager. A reminder counts as shown when
 * it has an alarm due by now (Android fires a past exact alarm at once) or its row is FIRED.
 */
@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [34])
class ReminderMissedWhileOffTest {

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

    private suspend fun boot() {
        fixture.clearAlarms()
        BootRecoveryHandler(
            fixture.scheduler,
            // Device reminders and the widget alarm are separate paths, not under test here
            mockk<DeviceCalendarReminderScheduler>(relaxed = true),
            mockk<WidgetUpdateManager>(relaxed = true)
        ).rescheduleReminders()
    }

    private suspend fun isShown(rowId: Long): Boolean {
        val armedAt = fixture.armedReminders()[rowId]
        val row = fixture.reminderRow(rowId)
        return (armedAt != null && armedAt <= System.currentTimeMillis() + MINUTE) ||
            row?.status == ReminderStatus.FIRED
    }

    @Test
    fun `boot shows a day-before reminder that came due overnight for an event still ahead`() = runTest {
        val start = now + 12 * HOUR
        val event = fixture.insertEvent("Flight", start, listOf("-P1D"))
        val row = fixture.insertReminderRow(event, start, "-P1D", start - 24 * HOUR)

        boot()

        assertTrue("missed reminder for an upcoming event was not shown", isShown(row))
    }

    @Test
    fun `boot shows a 15-minute reminder that came due during a restart`() = runTest {
        val start = now + 10 * MINUTE
        val event = fixture.insertEvent("Standup", start, listOf("-PT15M"))
        val row = fixture.insertReminderRow(event, start, "-PT15M", start - 15 * MINUTE)

        boot()

        assertTrue("missed reminder for an upcoming event was not shown", isShown(row))
    }

    @Test
    fun `boot doesn't show a missed reminder for an event that has ended`() = runTest {
        val start = now - 3 * HOUR
        val event = fixture.insertEvent("Lunch", start, listOf("-PT15M"))
        val row = fixture.insertReminderRow(event, start, "-PT15M", start - 15 * MINUTE)

        boot()

        assertFalse("reminder for an ended event was shown", isShown(row))
    }

    @Test
    fun `the reminder scan shows a reminder missed while the phone was off`() = runTest {
        val start = now + 12 * HOUR
        val event = fixture.insertEvent("Flight", start, listOf("-P1D"))
        val row = fixture.insertReminderRow(event, start, "-P1D", start - 24 * HOUR)
        fixture.clearAlarms()

        fixture.scheduler.scheduleUpcomingReminders()

        assertTrue("missed reminder for an upcoming event was not shown", isShown(row))
    }
}
