package org.onekash.kashcal.data.ics

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.onekash.kashcal.data.db.entity.IcsSubscription
import org.onekash.kashcal.reminder.ReminderAlarmFixture
import org.onekash.kashcal.reminder.ReminderAlarmFixture.Companion.DAY
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Tests that pausing a calendar feed ("Sync paused" in Settings > Calendar feeds) only stops it
 * syncing: its events keep their reminders through the window scans that follow (daily refresh,
 * after any other reminder fires, after boot), and resuming keeps them. Muting the feed's calendar
 * is what silences it.
 *
 * Runs the real [IcsSubscriptionRepository.setSubscriptionEnabled], the real scheduler and the real
 * mute path over an in-memory database, reading armed alarms back from Robolectric's AlarmManager.
 * The fetcher answers 304 on resume; the account and occurrence collaborators are never reached.
 */
@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [34])
class PausedFeedReminderTest {

    private lateinit var fixture: ReminderAlarmFixture
    private lateinit var repository: IcsSubscriptionRepository
    private val icsFetcher = mockk<IcsFetcher>()
    private var subscriptionId = 0L
    private val now = System.currentTimeMillis()

    @Before
    fun setup() = runTest {
        val context = ApplicationProvider.getApplicationContext<Context>()
        fixture = ReminderAlarmFixture(context)
        fixture.seedCalendar()
        val db = fixture.database
        subscriptionId = db.icsSubscriptionsDao().insert(
            IcsSubscription(
                url = "https://feeds.example.test/fixtures.ics",
                name = "Fixtures",
                color = -1,
                calendarId = fixture.calendarId,
            )
        )
        repository = IcsSubscriptionRepository(
            database = db,
            icsSubscriptionsDao = db.icsSubscriptionsDao(),
            accountRepository = mockk(),
            calendarsDao = db.calendarsDao(),
            eventsDao = db.eventsDao(),
            occurrenceGenerator = mockk(),
            icsFetcher = icsFetcher,
            reminderScheduler = fixture.scheduler,
            eventReader = fixture.eventReader,
            context = context,
        )
        coEvery { icsFetcher.fetch(any()) } returns IcsFetcher.FetchResult.NotModified
        fixture.insertEvent("Match", now + 2 * DAY, listOf("-PT15M"))
        fixture.scheduler.scheduleUpcomingReminders()
        assertEquals("armed before pausing", 1, fixture.armedReminders().size)
    }

    @After
    fun tearDown() = fixture.close()

    @Test
    fun `pausing a visible feed leaves its reminder armed through the next scan`() = runTest {
        repository.setSubscriptionEnabled(subscriptionId, false)
        assertEquals("armed after the pause", 1, fixture.armedReminders().size)

        fixture.scheduler.scheduleUpcomingReminders()
        assertEquals("armed after the next scan", 1, fixture.armedReminders().size)
    }

    @Test
    fun `pausing a hidden feed leaves its reminder armed through the next scan`() = runTest {
        fixture.eventReader.setCalendarVisibility(fixture.calendarId, false)

        repository.setSubscriptionEnabled(subscriptionId, false)
        assertEquals("armed after the pause", 1, fixture.armedReminders().size)

        fixture.scheduler.scheduleUpcomingReminders()
        assertEquals("armed after the next scan", 1, fixture.armedReminders().size)
    }

    @Test
    fun `resuming a paused feed refreshes it and keeps its reminder armed`() = runTest {
        repository.setSubscriptionEnabled(subscriptionId, false)
        repository.setSubscriptionEnabled(subscriptionId, true)

        coVerify(exactly = 1) { icsFetcher.fetch(any()) }
        assertEquals("armed after the resume", 1, fixture.armedReminders().size)
    }

    @Test
    fun `a paused feed whose calendar is muted arms nothing`() = runTest {
        repository.setSubscriptionEnabled(subscriptionId, false)
        fixture.coordinator().setCalendarMuted(fixture.calendarId, true)
        assertEquals("muting cancels", 0, fixture.armedReminders().size)

        fixture.scheduler.scheduleUpcomingReminders()
        assertEquals("the scan arms nothing for a muted calendar", 0, fixture.armedReminders().size)
    }
}
