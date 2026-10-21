package org.onekash.kashcal.reminder.device

import android.Manifest
import android.app.AlarmManager
import android.content.Context
import android.provider.CalendarContract.Events
import android.provider.CalendarContract.Reminders
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.test.core.app.ApplicationProvider
import io.mockk.mockk
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.onekash.kashcal.data.calendar_provider.SqliteCalendarProvider
import org.onekash.kashcal.data.preferences.KashCalDataStore
import org.onekash.kashcal.reminder.receiver.BootRecoveryHandler
import org.onekash.kashcal.reminder.scheduler.ReminderScheduler
import org.onekash.kashcal.widget.WidgetUpdateManager
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.io.File

/**
 * Tests what happens to a device-calendar reminder whose trigger time passed while the phone
 * was off.
 *
 * The phone being off or out of battery across a trigger time is the real input: boot wipes
 * the armed alarm. When the event hasn't ended, the user still needs the alert, so boot
 * recovery has to arm it to fire now. An alert for an event that is already over stays unshown.
 *
 * Runs the real [DeviceCalendarReminderScheduler] and the real repository over
 * [SqliteCalendarProvider], driven through [BootRecoveryHandler] as boot runs it, and reads the
 * armed device alarms back from Robolectric's AlarmManager.
 */
@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [34])
class DeviceReminderMissedWhileOffTest {

    private companion object {
        const val CAL = 1L
        const val MINUTE = 60_000L
        const val HOUR = 60 * MINUTE
    }

    private lateinit var context: Context
    private lateinit var provider: SqliteCalendarProvider
    private lateinit var dataStore: KashCalDataStore
    private lateinit var dataStoreScope: CoroutineScope
    private lateinit var dataStoreFile: File
    private lateinit var alarmManager: AlarmManager
    private val now = System.currentTimeMillis()

    @Before
    fun setup() = runTest {
        context = ApplicationProvider.getApplicationContext()
        provider = SqliteCalendarProvider.install()
        provider.seedCalendar(CAL)
        dataStoreScope = CoroutineScope(Dispatchers.Unconfined + SupervisorJob())
        dataStoreFile = File(context.filesDir, "test_prefs_${System.nanoTime()}.preferences_pb")
        dataStore = KashCalDataStore(context, PreferenceDataStoreFactory.create(scope = dataStoreScope) { dataStoreFile })
        dataStore.setDeviceCalendarRemindersEnabled(true)
        dataStore.setDeviceCalendarsEnabled(true)
        dataStore.setEnabledDeviceCalendarIds(setOf(CAL))
        shadowOf(context as android.app.Application).grantPermissions(Manifest.permission.READ_CALENDAR)
        alarmManager = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
    }

    @After
    fun tearDown() {
        provider.db.close()
        dataStoreScope.cancel()
        dataStoreFile.delete()
    }

    private fun eventWithReminder(start: Long, end: Long, minutesBefore: Int): Long {
        val id = provider.insertRow(
            SqliteCalendarProvider.EVENTS,
            Events.CALENDAR_ID to CAL, Events.TITLE to "Standup", Events.DTSTART to start,
            Events.DTEND to end, Events.EVENT_TIMEZONE to "UTC", Events.ALL_DAY to 0,
            Events.STATUS to Events.STATUS_CONFIRMED,
        )
        provider.insertRow(
            SqliteCalendarProvider.REMINDERS,
            Reminders.EVENT_ID to id, Reminders.MINUTES to minutesBefore, Reminders.METHOD to Reminders.METHOD_ALERT,
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

    private suspend fun boot() {
        shadowOf(alarmManager).scheduledAlarms.mapNotNull { it.operation }.forEach { alarmManager.cancel(it) }
        val deviceScheduler = DeviceCalendarReminderScheduler(
            context = context,
            calendarProviderRepository = SqliteCalendarProvider.repository(context),
            dataStore = dataStore
        )
        BootRecoveryHandler(
            // Room reminders and the widget alarm are separate paths, not under test here
            mockk<ReminderScheduler>(relaxed = true),
            deviceScheduler,
            mockk<WidgetUpdateManager>(relaxed = true)
        ).rescheduleReminders()
    }

    private fun isShownNow(eventId: Long): Boolean =
        deviceAlarms().any { (id, trigger) -> id == eventId && trigger <= System.currentTimeMillis() + MINUTE }

    @Test
    fun `boot shows a device reminder that came due during a restart`() = runTest {
        val event = eventWithReminder(start = now + 10 * MINUTE, end = now + 70 * MINUTE, minutesBefore = 15)

        boot()

        assertTrue("missed device reminder for an upcoming event was not shown: ${deviceAlarms()}", isShownNow(event))
    }

    @Test
    fun `boot shows a missed device reminder for an event that has started but not ended`() = runTest {
        val event = eventWithReminder(start = now - 5 * MINUTE, end = now + 55 * MINUTE, minutesBefore = 15)

        boot()

        assertTrue("missed device reminder for an ongoing event was not shown: ${deviceAlarms()}", isShownNow(event))
    }

    @Test
    fun `boot doesn't show a missed device reminder for an event that has ended`() = runTest {
        val event = eventWithReminder(start = now - 3 * HOUR, end = now - 2 * HOUR, minutesBefore = 15)

        boot()

        assertTrue("reminder for an ended event was shown: ${deviceAlarms()}", !isShownNow(event))
    }

    @Test
    fun `boot still arms a device reminder that is not yet due`() = runTest {
        val event = eventWithReminder(start = now + 2 * HOUR, end = now + 3 * HOUR, minutesBefore = 15)

        boot()

        assertTrue(
            "upcoming device reminder not armed at its time: ${deviceAlarms()}",
            deviceAlarms().any { (id, trigger) -> id == event && trigger == now + 2 * HOUR - 15 * MINUTE }
        )
    }
}
