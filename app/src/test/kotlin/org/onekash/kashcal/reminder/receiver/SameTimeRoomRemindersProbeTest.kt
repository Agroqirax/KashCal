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
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.onekash.kashcal.reminder.ReminderAlarmFixture
import org.onekash.kashcal.reminder.ReminderAlarmFixture.Companion.MINUTE
import org.onekash.kashcal.reminder.notification.ReminderNotificationManager
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Probe: two Room reminders due at the same instant (two meetings at the same time with the same
 * alert, in two calendars). Does each one notify?
 *
 * Room arms one alarm per reminder row. Runs the real scheduler and receiver handler over an
 * in-memory database: the window scan arms both, the first fires (its refill runs), then the
 * second fires. The trigger is a moment ahead so the test can wait for it to pass.
 */
@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [34])
class SameTimeRoomRemindersProbeTest {

    private lateinit var fixture: ReminderAlarmFixture
    private lateinit var notifications: ReminderNotificationManager

    @Before
    fun setup() = runTest {
        val context = ApplicationProvider.getApplicationContext<Context>()
        WorkManagerTestInitHelper.initializeTestWorkManager(context)
        fixture = ReminderAlarmFixture(context)
        fixture.seedCalendar()
        notifications = mockk()
        coEvery { notifications.buildNotification(any()) } returns mockk()
        every { notifications.postNotification(any(), any()) } returns 1
        every { notifications.cancelNotification(any()) } just runs
    }

    @After
    fun tearDown() = fixture.close()

    @Test
    fun `two Room reminders due at the same instant both notify`() = runTest {
        val other = fixture.addCalendar("Other")
        val start = System.currentTimeMillis() + 15 * MINUTE + 6_000
        val trigger = start - 15 * MINUTE
        fixture.insertEvent("Meeting A", start, listOf("-PT15M"))
        fixture.insertEvent("Meeting B", start, listOf("-PT15M"), calendarId = other)
        fixture.scheduler.scheduleUpcomingReminders()
        val armed = fixture.armedReminders()
        println("PROBE armed: $armed (trigger=$trigger)")
        assertEquals("one alarm per reminder", 2, armed.size)

        while (System.currentTimeMillis() <= trigger + 50) Thread.sleep(50)
        val (first, second) = armed.keys.sorted()
        withContext(Dispatchers.IO) { ReminderAlarmReceiver().handleAlarm(fixture.scheduler, notifications, first) }
        val afterFirst = fixture.armedReminders()
        println("PROBE after the first fired and refilled: $afterFirst")
        withContext(Dispatchers.IO) { ReminderAlarmReceiver().handleAlarm(fixture.scheduler, notifications, second) }

        assertEquals("the second alarm survives the first's refill", true, second in afterFirst)
        verify(exactly = 2) { notifications.postNotification(any(), any()) }
    }
}
