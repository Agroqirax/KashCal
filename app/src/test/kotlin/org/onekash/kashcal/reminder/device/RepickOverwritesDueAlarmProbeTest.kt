package org.onekash.kashcal.reminder.device

import android.Manifest
import android.app.AlarmManager
import android.content.Context
import android.content.Intent
import android.provider.CalendarContract.Events
import android.provider.CalendarContract.Reminders
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.test.core.app.ApplicationProvider
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.onekash.kashcal.data.calendar_provider.SqliteCalendarProvider
import org.onekash.kashcal.data.preferences.KashCalDataStore
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.io.File

/**
 * Probe: a device re-pick (observer, time change, untick, refresh) that runs after a reminder's
 * time has passed but before its alarm is delivered. Does that reminder still notify?
 *
 * Real scheduler + repository over SqliteCalendarProvider + receiver handler, real clock.
 */
@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [34])
class RepickOverwritesDueAlarmProbeTest {

    private companion object {
        const val CAL_A = 1L
        const val CAL_B = 2L
        const val MINUTE = 60_000L
        const val HOUR = 60 * MINUTE
    }

    private lateinit var context: Context
    private lateinit var provider: SqliteCalendarProvider
    private lateinit var dataStore: KashCalDataStore
    private lateinit var dataStoreScope: CoroutineScope
    private lateinit var dataStoreFile: File
    private lateinit var alarmManager: AlarmManager
    private lateinit var scheduler: DeviceCalendarReminderScheduler
    private lateinit var notifications: DeviceCalendarReminderNotificationManager

    // A whole hour two days ahead, so every trigger below is ahead of the real clock.
    private val base = (System.currentTimeMillis() / HOUR + 48) * HOUR

    @Before
    fun setup() = runTest {
        context = ApplicationProvider.getApplicationContext()
        provider = SqliteCalendarProvider.install()
        provider.seedCalendar(CAL_A)
        provider.seedCalendar(CAL_B)
        dataStoreScope = CoroutineScope(Dispatchers.Unconfined + SupervisorJob())
        dataStoreFile = File(context.filesDir, "sametime_${System.nanoTime()}.preferences_pb")
        dataStore = KashCalDataStore(context, PreferenceDataStoreFactory.create(scope = dataStoreScope) { dataStoreFile })
        dataStore.setDeviceCalendarRemindersEnabled(true)
        dataStore.setDeviceCalendarsEnabled(true)
        dataStore.setEnabledDeviceCalendarIds(setOf(CAL_A, CAL_B))
        shadowOf(context as android.app.Application).grantPermissions(Manifest.permission.READ_CALENDAR)
        alarmManager = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        shadowOf(alarmManager).scheduledAlarms.mapNotNull { it.operation }.forEach { alarmManager.cancel(it) }
        scheduler = DeviceCalendarReminderScheduler(context, SqliteCalendarProvider.repository(context), dataStore)
        notifications = mockk()
        coEvery { notifications.showNotification(any(), any(), any(), any(), any(), any(), any(), any()) } returns 1
    }

    @After
    fun tearDown() {
        provider.db.close()
        dataStoreScope.cancel()
        dataStoreFile.delete()
    }

    private fun event(calendarId: Long, start: Long, minutes: Int = 60, end: Long = start + HOUR): Long {
        val id = provider.insertRow(
            SqliteCalendarProvider.EVENTS,
            Events.CALENDAR_ID to calendarId, Events.TITLE to "Meeting in $calendarId", Events.DTSTART to start,
            Events.DTEND to end, Events.EVENT_TIMEZONE to "UTC", Events.ALL_DAY to 0,
            Events.STATUS to Events.STATUS_CONFIRMED,
        )
        provider.insertRow(
            SqliteCalendarProvider.REMINDERS,
            Reminders.EVENT_ID to id, Reminders.MINUTES to minutes, Reminders.METHOD to Reminders.METHOD_ALERT,
        )
        return id
    }

    /** The intent of the one armed reminder alarm. */
    private fun armed(): Intent =
        shadowOf(alarmManager).scheduledAlarms
            .mapNotNull { alarm -> alarm.operation?.let { Shadows.shadowOf(it).savedIntent } }
            .single { it.component?.className == DeviceCalendarAlarmReceiver::class.java.name }

    private fun Intent.eventId() = getLongExtra(DeviceCalendarReminderScheduler.EXTRA_EVENT_ID, -1L)
    private fun Intent.triggerTime() = getLongExtra(DeviceCalendarReminderScheduler.EXTRA_TRIGGER_TIME, -1L)

    /** Delivers [alarm] as the receiver would, at [nowMs]. */
    private suspend fun deliver(alarm: Intent, nowMs: Long, isSnooze: Boolean = false) {
        DeviceCalendarAlarmReceiver().handleAlarm(
            scheduler, notifications,
            alarm.eventId(),
            alarm.getLongExtra(DeviceCalendarReminderScheduler.EXTRA_OCCURRENCE_TS, -1L),
            alarm.getStringExtra(DeviceCalendarReminderScheduler.EXTRA_TITLE).orEmpty(),
            alarm.getStringExtra(DeviceCalendarReminderScheduler.EXTRA_LOCATION),
            alarm.getBooleanExtra(DeviceCalendarReminderScheduler.EXTRA_IS_ALL_DAY, false),
            alarm.getIntExtra(DeviceCalendarReminderScheduler.EXTRA_CALENDAR_COLOR, 0),
            alarm.getLongExtra(DeviceCalendarReminderScheduler.EXTRA_CALENDAR_ID, -1L),
            alarm.triggerTime(),
            isSnooze = isSnooze,
            nowMs = nowMs,
        )
    }

    private fun shownTimes(eventId: Long, times: Int) =
        coVerify(exactly = times) { notifications.showNotification(eq(eventId), any(), any(), any(), any(), any(), any(), any()) }

    @Test
    fun `a re-pick after a reminder's time but before its delivery keeps it`() = runTest {
        val start = System.currentTimeMillis() + HOUR + 2_000
        val trigger = start - HOUR
        val a = event(CAL_A, start)
        val b = event(CAL_A, start + HOUR)
        scheduler.scheduleNextReminder()
        assertEquals(a, armed().eventId())

        // A's time passes; its alarm hasn't been delivered yet when a re-pick runs.
        while (System.currentTimeMillis() <= trigger + 50) Thread.sleep(50)
        scheduler.scheduleNextReminder()
        val afterRepick = armed()
        println("PROBE armed after re-pick: event=${afterRepick.eventId()} trigger=${afterRepick.triggerTime()} (a=$a b=$b)")

        // Whatever is armed now is what gets delivered.
        deliver(afterRepick, nowMs = System.currentTimeMillis())
        shownTimes(a, 1)
    }
}
