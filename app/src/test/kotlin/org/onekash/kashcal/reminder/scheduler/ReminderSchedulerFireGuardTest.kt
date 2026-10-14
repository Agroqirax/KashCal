package org.onekash.kashcal.reminder.scheduler

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import io.mockk.coEvery
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.onekash.kashcal.data.db.dao.AccountsDao
import org.onekash.kashcal.data.db.dao.AttendeesDao
import org.onekash.kashcal.data.db.dao.CalendarsDao
import org.onekash.kashcal.data.db.dao.OccurrencesDao
import org.onekash.kashcal.data.db.dao.ScheduledRemindersDao
import org.onekash.kashcal.data.db.entity.Event
import org.onekash.kashcal.data.db.entity.Occurrence
import org.onekash.kashcal.data.db.entity.ScheduledReminder
import org.onekash.kashcal.data.db.entity.SyncStatus
import org.onekash.kashcal.domain.reader.EventReader
import org.onekash.kashcal.reminder.notification.ReminderNotificationChannels
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Tests [ReminderScheduler.shouldFireReminder], the fire-time guard that decides whether a
 * reminder alarm still posts a notification.
 *
 * An armed alarm for a Room-backed event fires even after the event is deleted (row gone, e.g. a
 * server delete pulled in the background) or soft-deleted (PENDING_DELETE, e.g. a CalDAV delete not
 * yet pushed), and the notification is built from data copied onto the reminder row, so without the
 * guard it posts for an event that is gone. The guard is the Room counterpart of the device path's
 * `DeviceCalendarReminderScheduler.shouldFireReminder` and covers every Room-backed source (local,
 * iCloud, CalDAV, ICS, birthdays, anniversaries), since they share the events table.
 *
 * The scheduler is built over the Robolectric application context; shouldFireReminder reads
 * only through [EventReader].
 */
@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [34])
class ReminderSchedulerFireGuardTest {

    private lateinit var context: Context
    private lateinit var scheduledRemindersDao: ScheduledRemindersDao
    private lateinit var eventReader: EventReader
    private lateinit var channels: ReminderNotificationChannels
    private lateinit var attendeesDao: AttendeesDao
    private lateinit var accountsDao: AccountsDao
    private lateinit var calendarsDao: CalendarsDao
    private lateinit var occurrencesDao: OccurrencesDao

    private val tomorrow = System.currentTimeMillis() + 24L * 60 * 60 * 1000

    @Before
    fun setup() {
        context = ApplicationProvider.getApplicationContext()
        scheduledRemindersDao = mockk(relaxed = true)
        eventReader = mockk(relaxed = true)
        channels = mockk(relaxed = true)
        attendeesDao = mockk(relaxed = true)
        accountsDao = mockk(relaxed = true)
        calendarsDao = mockk(relaxed = true)
        occurrencesDao = mockk(relaxed = true)
    }

    private fun newScheduler(): ReminderScheduler = ReminderScheduler(
        context = context,
        scheduledRemindersDao = scheduledRemindersDao,
        eventReader = eventReader,
        channels = channels,
        attendeesDao = attendeesDao,
        accountsDao = accountsDao,
        calendarsDao = calendarsDao,
        occurrencesDao = occurrencesDao
    )

    private fun event(
        id: Long,
        syncStatus: SyncStatus = SyncStatus.SYNCED,
        status: String = "CONFIRMED",
        importSource: String? = null,
        originalEventId: Long? = null,
    ): Event = Event(
        id = id,
        uid = "uid-$id",
        calendarId = 10L,
        title = "Event $id",
        startTs = tomorrow,
        endTs = tomorrow + 60_000,
        dtstamp = 0L,
        syncStatus = syncStatus,
        status = status,
        importSource = importSource,
        originalEventId = originalEventId,
        originalInstanceTime = if (originalEventId != null) tomorrow else null,
    )

    private fun reminderFor(eventId: Long) = ScheduledReminder(
        eventId = eventId,
        occurrenceTime = tomorrow,
        triggerTime = tomorrow - 900_000,
        reminderOffset = "-PT15M",
        eventTitle = "Event",
        isAllDay = false,
        calendarColor = 0,
    )

    @Test
    fun `shouldFireReminder returns false when event no longer exists`() = runTest {
        coEvery { eventReader.getEventById(100L) } returns null

        assertFalse(newScheduler().shouldFireReminder(100L))
    }

    @Test
    fun `shouldFireReminder returns false when event is soft-deleted (PENDING_DELETE)`() = runTest {
        coEvery { eventReader.getEventById(100L) } returns event(100L, SyncStatus.PENDING_DELETE)

        assertFalse(newScheduler().shouldFireReminder(100L))
    }

    @Test
    fun `shouldFireReminder returns true for a live synced event`() = runTest {
        coEvery { eventReader.getEventById(100L) } returns event(100L, SyncStatus.SYNCED)

        assertTrue(newScheduler().shouldFireReminder(100L))
    }

    @Test
    fun `shouldFireReminder returns false for a cancelled series an ICS import touched`() = runTest {
        eventReader = mockk()
        for (source in listOf(Event.IMPORT_SOURCE_ICS_FILE, Event.IMPORT_SOURCE_ICS_CANCEL)) {
            coEvery { eventReader.getEventById(100L) } returns event(100L, status = "CANCELLED", importSource = source)

            assertFalse(source, newScheduler().shouldFireReminder(100L))
        }
    }

    @Test
    fun `shouldFireReminder keeps firing for a cancelled event no import touched`() = runTest {
        eventReader = mockk()
        coEvery { eventReader.getEventById(100L) } returns event(100L, status = "CANCELLED")

        assertTrue(newScheduler().shouldFireReminder(100L))
    }

    @Test
    fun `shouldFireReminder keeps firing for an imported event that is not cancelled`() = runTest {
        eventReader = mockk()
        coEvery { eventReader.getEventById(100L) } returns
            event(100L, status = "CONFIRMED", importSource = Event.IMPORT_SOURCE_ICS_CANCEL)

        assertTrue(newScheduler().shouldFireReminder(100L))
    }

    @Test
    fun `hasLiveOccurrenceForReminder is false for a cancelled exception an import touched`() = runTest {
        eventReader = mockk()
        occurrencesDao = mockk()
        coEvery { eventReader.getEventById(1L) } returns event(1L)
        coEvery { eventReader.getEventById(2L) } returns
            event(2L, status = "CANCELLED", importSource = Event.IMPORT_SOURCE_ICS_CANCEL, originalEventId = 1L)

        assertFalse(newScheduler().hasLiveOccurrenceForReminder(reminderFor(2L)))
    }

    @Test
    fun `hasLiveOccurrenceForReminder is false for a cancelled exception under an imported series`() = runTest {
        eventReader = mockk()
        occurrencesDao = mockk()
        coEvery { eventReader.getEventById(1L) } returns event(1L, importSource = Event.IMPORT_SOURCE_ICS_FILE)
        coEvery { eventReader.getEventById(2L) } returns event(2L, status = "CANCELLED", originalEventId = 1L)

        assertFalse(newScheduler().hasLiveOccurrenceForReminder(reminderFor(2L)))
    }

    @Test
    fun `hasLiveOccurrenceForReminder reads the occurrence for a cancelled exception no import touched`() = runTest {
        eventReader = mockk()
        occurrencesDao = mockk()
        coEvery { eventReader.getEventById(1L) } returns event(1L)
        coEvery { eventReader.getEventById(2L) } returns event(2L, status = "CANCELLED", originalEventId = 1L)
        coEvery { occurrencesDao.getByExceptionEventId(2L) } returns Occurrence(
            eventId = 1L, calendarId = 10L, startTs = tomorrow, endTs = tomorrow + 60_000,
            startDay = 0, endDay = 0, exceptionEventId = 2L,
        )

        assertTrue(newScheduler().hasLiveOccurrenceForReminder(reminderFor(2L)))
    }

    // ==================== isCalendarMutedForReminder ====================

    private fun calendar(muted: Boolean) = org.onekash.kashcal.data.db.entity.Calendar(
        id = 10L, accountId = 1L, caldavUrl = "local://fire-guard", displayName = "Work", color = 0,
        isNotificationMuted = muted
    )

    @Test
    fun `isCalendarMutedForReminder is true when the event's calendar is muted`() = runTest {
        coEvery { eventReader.getEventById(100L) } returns event(100L)
        coEvery { calendarsDao.getById(10L) } returns calendar(muted = true)

        assertTrue(newScheduler().isCalendarMutedForReminder(reminderFor(100L)))
    }

    @Test
    fun `isCalendarMutedForReminder is false when the event's calendar notifies`() = runTest {
        coEvery { eventReader.getEventById(100L) } returns event(100L)
        coEvery { calendarsDao.getById(10L) } returns calendar(muted = false)

        assertFalse(newScheduler().isCalendarMutedForReminder(reminderFor(100L)))
    }

    @Test
    fun `isCalendarMutedForReminder is false when the event or its calendar is gone`() = runTest {
        coEvery { eventReader.getEventById(100L) } returns null
        assertFalse(newScheduler().isCalendarMutedForReminder(reminderFor(100L)))

        coEvery { eventReader.getEventById(100L) } returns event(100L)
        coEvery { calendarsDao.getById(10L) } returns null
        assertFalse(newScheduler().isCalendarMutedForReminder(reminderFor(100L)))
    }

    @Test
    fun `isCalendarMutedForReminder reads the calendar on every call`() = runTest {
        coEvery { eventReader.getEventById(100L) } returns event(100L)
        coEvery { calendarsDao.getById(10L) } returnsMany listOf(calendar(muted = false), calendar(muted = true))
        val scheduler = newScheduler()

        assertFalse(scheduler.isCalendarMutedForReminder(reminderFor(100L)))
        assertTrue(scheduler.isCalendarMutedForReminder(reminderFor(100L)))
    }
}
