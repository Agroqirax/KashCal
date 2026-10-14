package org.onekash.kashcal.reminder.device

import android.Manifest
import android.app.AlarmManager
import android.content.Context
import android.provider.CalendarContract.Events
import android.provider.CalendarContract.Reminders
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.test.core.app.ApplicationProvider
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
import org.onekash.kashcal.data.calendar_provider.AndroidCalendarProviderRepository
import org.onekash.kashcal.data.calendar_provider.SqliteCalendarProvider
import org.onekash.kashcal.data.preferences.KashCalDataStore
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.io.File

/**
 * Tests that a muted device calendar's reminders are never armed, through the real
 * [DeviceCalendarReminderScheduler] and the real repository over [SqliteCalendarProvider].
 *
 * The device path arms only the single next reminder across the enabled calendars, so the case
 * that matters is a muted calendar holding the earliest one: the alarm must go to the next
 * calendar's reminder instead. A fake repository holding one upcoming reminder can't show that.
 * Also covers pruning muted ids of calendars the provider no longer has.
 */
@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [34])
class DeviceCalendarReminderMuteTest {

    private companion object {
        const val CAL_A = 1L
        const val CAL_B = 2L
        const val MINUTE = 60_000L
        const val HOUR = 60 * MINUTE
    }

    private lateinit var context: Context
    private lateinit var provider: SqliteCalendarProvider
    private lateinit var repository: AndroidCalendarProviderRepository
    private lateinit var dataStore: KashCalDataStore
    private lateinit var dataStoreScope: CoroutineScope
    private lateinit var dataStoreFile: File
    private lateinit var alarmManager: AlarmManager
    private lateinit var scheduler: DeviceCalendarReminderScheduler
    private val now = System.currentTimeMillis()

    @Before
    fun setup() = runTest {
        context = ApplicationProvider.getApplicationContext()
        provider = SqliteCalendarProvider.install()
        provider.seedCalendar(CAL_A)
        provider.seedCalendar(CAL_B)
        repository = SqliteCalendarProvider.repository(context)
        dataStoreScope = CoroutineScope(Dispatchers.Unconfined + SupervisorJob())
        dataStoreFile = File(context.filesDir, "test_prefs_${System.nanoTime()}.preferences_pb")
        dataStore = KashCalDataStore(context, PreferenceDataStoreFactory.create(scope = dataStoreScope) { dataStoreFile })
        dataStore.setDeviceCalendarRemindersEnabled(true)
        dataStore.setDeviceCalendarsEnabled(true)
        dataStore.setEnabledDeviceCalendarIds(setOf(CAL_A, CAL_B))
        shadowOf(context as android.app.Application).grantPermissions(Manifest.permission.READ_CALENDAR)
        alarmManager = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        // App start arms the widget's midnight alarm; start with none.
        shadowOf(alarmManager).scheduledAlarms.mapNotNull { it.operation }.forEach { alarmManager.cancel(it) }
        scheduler = DeviceCalendarReminderScheduler(context, repository, dataStore)
    }

    @After
    fun tearDown() {
        provider.db.close()
        dataStoreScope.cancel()
        dataStoreFile.delete()
    }

    /** A one-hour event in [calendarId] starting at [start] with a 60-minute reminder. */
    private fun eventWithReminder(calendarId: Long, start: Long): Long {
        val id = provider.insertRow(
            SqliteCalendarProvider.EVENTS,
            Events.CALENDAR_ID to calendarId, Events.TITLE to "Event in $calendarId", Events.DTSTART to start,
            Events.DTEND to start + HOUR, Events.EVENT_TIMEZONE to "UTC", Events.ALL_DAY to 0,
            Events.STATUS to Events.STATUS_CONFIRMED,
        )
        provider.insertRow(
            SqliteCalendarProvider.REMINDERS,
            Reminders.EVENT_ID to id, Reminders.MINUTES to 60, Reminders.METHOD to Reminders.METHOD_ALERT,
        )
        return id
    }

    /** Device reminder alarms currently set, as event id to trigger time. */
    private fun deviceAlarms(): List<Pair<Long, Long>> =
        shadowOf(alarmManager).scheduledAlarms.mapNotNull { alarm ->
            val intent = alarm.operation?.let { Shadows.shadowOf(it).savedIntent } ?: return@mapNotNull null
            if (intent.component?.className != DeviceCalendarAlarmReceiver::class.java.name) return@mapNotNull null
            intent.getLongExtra(DeviceCalendarReminderScheduler.EXTRA_EVENT_ID, -1L) to alarm.triggerAtMs
        }

    @Test
    fun `the next reminder skips a muted calendar's earlier one`() = runTest {
        eventWithReminder(CAL_A, start = now + 2 * HOUR)
        val later = eventWithReminder(CAL_B, start = now + 4 * HOUR)
        dataStore.setDeviceCalendarMuted(CAL_A, true)

        scheduler.scheduleNextReminder()

        assertEquals(listOf(later to now + 3 * HOUR), deviceAlarms())
    }

    @Test
    fun `unmuting brings back the earlier reminder`() = runTest {
        val earlier = eventWithReminder(CAL_A, start = now + 2 * HOUR)
        eventWithReminder(CAL_B, start = now + 4 * HOUR)
        dataStore.setDeviceCalendarMuted(CAL_A, true)
        scheduler.scheduleNextReminder()

        dataStore.setDeviceCalendarMuted(CAL_A, false)
        scheduler.scheduleNextReminder()

        assertEquals(listOf(earlier to now + HOUR), deviceAlarms())
    }

    @Test
    fun `muting every enabled calendar cancels the armed alarm`() = runTest {
        eventWithReminder(CAL_A, start = now + 2 * HOUR)
        eventWithReminder(CAL_B, start = now + 4 * HOUR)
        scheduler.scheduleNextReminder()
        assertEquals(1, deviceAlarms().size)

        dataStore.setDeviceCalendarMuted(CAL_A, true)
        dataStore.setDeviceCalendarMuted(CAL_B, true)
        scheduler.scheduleNextReminder()

        assertEquals(emptyList<Pair<Long, Long>>(), deviceAlarms())
    }

    @Test
    fun `a hidden calendar that isn't muted still gets its reminder`() = runTest {
        val earlier = eventWithReminder(CAL_A, start = now + 2 * HOUR)
        eventWithReminder(CAL_B, start = now + 4 * HOUR)
        dataStore.setHiddenDeviceCalendarIds(setOf(CAL_A))

        scheduler.scheduleNextReminder()

        assertEquals(listOf(earlier to now + HOUR), deviceAlarms())
    }

    // ==================== Stale ids ====================

    private fun removeCalendar(id: Long) {
        provider.db.delete(SqliteCalendarProvider.CALENDARS, "_id = ?", arrayOf(id.toString()))
    }

    @Test
    fun `pruning drops the mute of a calendar the provider no longer has`() = runTest {
        dataStore.setDeviceCalendarMuted(CAL_A, true)
        dataStore.setDeviceCalendarMuted(CAL_B, true)
        removeCalendar(CAL_B)

        repository.pruneStaleCalendarIds(dataStore)

        assertEquals(setOf(CAL_A), dataStore.getMutedDeviceCalendarIds())
        assertEquals(setOf(CAL_A), dataStore.getEnabledDeviceCalendarIds())
    }

    @Test
    fun `pruning drops stale mutes even when no calendar is enabled`() = runTest {
        dataStore.setEnabledDeviceCalendarIds(emptySet())
        dataStore.setDeviceCalendarMuted(CAL_A, true)
        dataStore.setDeviceCalendarMuted(CAL_B, true)
        removeCalendar(CAL_B)

        repository.pruneStaleCalendarIds(dataStore)

        assertEquals(setOf(CAL_A), dataStore.getMutedDeviceCalendarIds())
    }
}
