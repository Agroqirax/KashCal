package org.onekash.kashcal.data.calendar_provider

import android.content.Context
import android.provider.CalendarContract.Events
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.util.TimeZone

/**
 * Pins [DeviceCalendarInstance.originalAllDay] as read by [AndroidCalendarProviderRepository]:
 * the all-day flag of the series an exception replaces an occurrence of. The app's own
 * [AndroidCalendarProviderRepository.createException] writes it from the series, and a sync
 * adapter may leave it unset, which reads as null.
 */
@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [34])
class AndroidCalendarProviderRepositoryOriginalAllDayTest {

    private lateinit var provider: SqliteCalendarProvider
    private lateinit var repo: AndroidCalendarProviderRepository
    private lateinit var context: Context
    private lateinit var savedZone: TimeZone

    @Before
    fun setup() {
        savedZone = TimeZone.getDefault()
        TimeZone.setDefault(TimeZone.getTimeZone("UTC"))
        context = ApplicationProvider.getApplicationContext()
        provider = SqliteCalendarProvider.install()
        repo = SqliteCalendarProvider.repository(context)
        provider.seedCalendar(CAL, accountName = DeviceRoundTripFixture.OWNER, displayName = "Family", color = 0xFF2F7BEA.toInt())
    }

    @After
    fun tearDown() {
        provider.db.close()
        TimeZone.setDefault(savedZone)
    }

    private fun allDaySeries(): Long = provider.insertRow(
        SqliteCalendarProvider.EVENTS,
        Events.CALENDAR_ID to CAL, Events.TITLE to "Trash day", Events.DTSTART to DAY0,
        Events.DURATION to "P1D", Events.RRULE to "FREQ=WEEKLY;COUNT=4", Events.ALL_DAY to 1,
        Events.EVENT_TIMEZONE to "UTC", Events._SYNC_ID to "trash-1", Events.STATUS to Events.STATUS_CONFIRMED,
    )

    private fun timedSeries(): Long = provider.insertRow(
        SqliteCalendarProvider.EVENTS,
        Events.CALENDAR_ID to CAL, Events.TITLE to "Soccer", Events.DTSTART to T0,
        Events.DURATION to "PT1H", Events.RRULE to "FREQ=WEEKLY;COUNT=4", Events.ALL_DAY to 0,
        Events.EVENT_TIMEZONE to "UTC", Events._SYNC_ID to "soccer-1", Events.STATUS to Events.STATUS_CONFIRMED,
    )

    private suspend fun instancesOn(dayCode: Int) =
        repo.getInstancesForDayRange(dayCode, dayCode, setOf(CAL), hideDeclined = false)

    @Test
    fun `an app exception of an all-day series reads originalAllDay true`() = runTest {
        val master = allDaySeries()
        val exId = repo.createException(
            calendarId = CAL, masterEventId = master, originalInstanceTime = DAY0 + WEEK,
            title = "Trash day (moved)", description = null, location = null,
            startTs = DAY0 + WEEK + DAY, endTs = DAY0 + WEEK + 2 * DAY - 1, isAllDay = true,
            timezone = "UTC", reminders = null, availability = Events.AVAILABILITY_BUSY, eventColor = null,
        ).getOrThrow()

        val ex = instancesOn(20240313).single { it.eventId == exId }
        assertEquals(true, ex.originalAllDay)
    }

    @Test
    fun `an app exception of a timed series reads originalAllDay false`() = runTest {
        val master = timedSeries()
        val exId = repo.createException(
            calendarId = CAL, masterEventId = master, originalInstanceTime = T0 + WEEK,
            title = "Soccer (moved)", description = null, location = null,
            startTs = T0 + WEEK + 2 * HOUR, endTs = T0 + WEEK + 3 * HOUR, isAllDay = false,
            timezone = "UTC", reminders = null, availability = Events.AVAILABILITY_BUSY, eventColor = null,
        ).getOrThrow()

        val ex = instancesOn(20240312).single { it.eventId == exId }
        assertEquals(false, ex.originalAllDay)
    }

    @Test
    fun `a sync adapter exception without the column reads null`() = runTest {
        val master = timedSeries()
        val exId = provider.insertRow(
            SqliteCalendarProvider.EVENTS,
            Events.CALENDAR_ID to CAL, Events.TITLE to "Soccer (moved)", Events.DTSTART to T0 + WEEK + 2 * HOUR,
            Events.DTEND to T0 + WEEK + 3 * HOUR, Events.EVENT_TIMEZONE to "UTC",
            Events.ORIGINAL_ID to master, Events.ORIGINAL_SYNC_ID to "soccer-1",
            Events.ORIGINAL_INSTANCE_TIME to T0 + WEEK, Events.STATUS to Events.STATUS_CONFIRMED,
        )

        val ex = instancesOn(20240312).single { it.eventId == exId }
        assertNull(ex.originalAllDay)
    }

    @Test
    fun `a series occurrence that isn't an exception reads null`() = runTest {
        val master = timedSeries()
        val occurrence = instancesOn(20240312).single { it.eventId == master }
        assertNull(occurrence.originalAllDay)
    }

    companion object {
        const val CAL = 7L
        const val HOUR = DeviceRoundTripFixture.HOUR
        const val DAY = DeviceRoundTripFixture.DAY
        const val WEEK = DeviceRoundTripFixture.WEEK
        const val T0 = DeviceRoundTripFixture.T0 // 2024-03-05T15:00:00Z
        const val DAY0 = DeviceRoundTripFixture.DAY0 // 2024-03-05T00:00:00Z
    }
}
