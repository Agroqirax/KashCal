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
 * Device reminders due together all notify: the real scheduler, the real repository over
 * [SqliteCalendarProvider] and the real receiver handler, driven by the one armed alarm.
 *
 * The device path keeps one alarm. When it fires, every reminder due by then and not yet shown
 * is shown with it and the first one still ahead is armed, so two meetings with the same alert,
 * or reminders a late delivery overtook, aren't lost. A snooze delivery shows only itself.
 */
@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [34])
class SameTimeDeviceRemindersTest {

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
    fun `two reminders at the same instant both notify once the alarm fires on the real clock`() = runTest {
        val start = System.currentTimeMillis() + HOUR + 3_000
        val trigger = start - HOUR
        val a = event(CAL_A, start)
        val b = event(CAL_B, start)
        val c = event(CAL_A, start + HOUR)
        scheduler.scheduleNextReminder()
        val alarm = armed()
        assertEquals(a, alarm.eventId())

        // The alarm only fires once its time has passed.
        while (System.currentTimeMillis() <= trigger + 50) Thread.sleep(50)
        DeviceCalendarAlarmReceiver().handleAlarm(
            scheduler, notifications, alarm.eventId(), start, "Meeting", null, false, 0, CAL_A, trigger,
        )

        shownTimes(a, 1)
        shownTimes(b, 1)
        shownTimes(c, 0)
        assertEquals(c, armed().eventId())
    }

    @Test
    fun `three reminders at the same instant all notify`() = runTest {
        val ids = listOf(event(CAL_A, base + HOUR), event(CAL_B, base + HOUR), event(CAL_A, base + HOUR))
        scheduler.scheduleNextReminder()

        deliver(armed(), nowMs = base + 1)

        ids.forEach { shownTimes(it, 1) }
    }

    @Test
    fun `a late delivery shows the reminders it overtook`() = runTest {
        val a = event(CAL_A, base + HOUR)
        val b = event(CAL_B, base + HOUR + 2 * MINUTE)
        scheduler.scheduleNextReminder()

        // Delivered three minutes late, after B's trigger too.
        deliver(armed(), nowMs = base + 3 * MINUTE)

        shownTimes(a, 1)
        shownTimes(b, 1)
    }

    @Test
    fun `a snooze delivery shows only itself`() = runTest {
        val a = event(CAL_A, base + HOUR)
        val b = event(CAL_B, base + HOUR)
        scheduler.scheduleNextReminder()

        deliver(armed(), nowMs = base + 1, isSnooze = true)

        shownTimes(a, 1)
        shownTimes(b, 0)
    }

    @Test
    fun `a due reminder in a muted calendar isn't shown`() = runTest {
        val a = event(CAL_A, base + HOUR)
        val b = event(CAL_B, base + HOUR)
        scheduler.scheduleNextReminder()
        dataStore.setDeviceCalendarMuted(CAL_B, true)

        deliver(armed(), nowMs = base + 1)

        shownTimes(a, 1)
        shownTimes(b, 0)
    }

    @Test
    fun `a reminder still ahead isn't shown early and is armed next`() = runTest {
        val a = event(CAL_A, base + HOUR)
        val b = event(CAL_B, base + HOUR + 10 * MINUTE)
        scheduler.scheduleNextReminder()

        deliver(armed(), nowMs = base + 1)

        shownTimes(a, 1)
        shownTimes(b, 0)
        assertEquals(b, armed().eventId())
        assertEquals(base + 10 * MINUTE, armed().triggerTime())
    }

    @Test
    fun `two zero-length events reminding at their start both notify`() = runTest {
        val a = event(CAL_A, base, minutes = 0, end = base)
        val b = event(CAL_B, base, minutes = 0, end = base)
        scheduler.scheduleNextReminder()

        deliver(armed(), nowMs = base)

        shownTimes(a, 1)
        shownTimes(b, 1)
    }
}
