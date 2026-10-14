package org.onekash.kashcal.data.ics

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import io.mockk.coEvery
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.onekash.kashcal.data.db.entity.IcsSubscription
import org.onekash.kashcal.domain.generator.OccurrenceGenerator
import org.onekash.kashcal.reminder.ReminderAlarmFixture
import org.onekash.kashcal.reminder.ReminderAlarmFixture.Companion.DAY
import org.onekash.kashcal.testutil.TestDataStoreFactory
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter

/**
 * Tests resuming a paused calendar feed whose server returns a body: the feed's event keeps one
 * reminder alarm, at the event's new time when the feed moved it. Pausing keeps reminders armed
 * ([PausedFeedReminderTest]), so the refresh on resume must replace them, not add a second.
 *
 * Runs the real [IcsSubscriptionRepository] refresh, the real occurrence generator and the real
 * scheduler over an in-memory database, reading armed alarms back from Robolectric's AlarmManager.
 */
@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [34])
class PausedFeedResumeReminderTest {

    private lateinit var fixture: ReminderAlarmFixture
    private lateinit var repository: IcsSubscriptionRepository
    private val icsFetcher = mockk<IcsFetcher>()
    private var subscriptionId = 0L

    // A whole minute two days ahead, so the 15-minute reminder is inside the arming window.
    private val start = (System.currentTimeMillis() + 2 * DAY) / 60_000 * 60_000

    private fun feed(startMs: Long): String {
        val format = DateTimeFormatter.ofPattern("yyyyMMdd'T'HHmmss'Z'").withZone(ZoneOffset.UTC)
        return """
            BEGIN:VCALENDAR
            VERSION:2.0
            PRODID:-//Example//Fixtures//EN
            BEGIN:VEVENT
            UID:match-1@feeds.example.test
            DTSTAMP:20261001T000000Z
            DTSTART:${format.format(Instant.ofEpochMilli(startMs))}
            DTEND:${format.format(Instant.ofEpochMilli(startMs + 2 * 3_600_000))}
            SUMMARY:Match
            BEGIN:VALARM
            ACTION:DISPLAY
            DESCRIPTION:Match
            TRIGGER:-PT15M
            END:VALARM
            END:VEVENT
            END:VCALENDAR
        """.trimIndent().replace("\n", "\r\n")
    }

    private fun serves(content: String, etag: String) {
        coEvery { icsFetcher.fetch(any()) } returns IcsFetcher.FetchResult.Success(content, etag, null)
    }

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
            occurrenceGenerator = OccurrenceGenerator(
                db, db.occurrencesDao(), db.eventsDao(), TestDataStoreFactory.createDefault()
            ),
            icsFetcher = icsFetcher,
            reminderScheduler = fixture.scheduler,
            eventReader = fixture.eventReader,
            context = context,
        )
        serves(feed(start), etag = "v1")
        repository.refreshSubscription(subscriptionId)
        assertEquals("armed by the first sync", listOf(start - 15 * 60_000), fixture.reminderAlarms().map { it.second })
    }

    @After
    fun tearDown() = fixture.close()

    @Test
    fun `resuming a feed that moved its event re-arms the reminder once, at the new time`() = runTest {
        repository.setSubscriptionEnabled(subscriptionId, false)
        fixture.scheduler.scheduleUpcomingReminders()

        val moved = start + 3_600_000
        serves(feed(moved), etag = "v2")
        repository.setSubscriptionEnabled(subscriptionId, true)

        assertEquals(listOf(moved - 15 * 60_000), fixture.reminderAlarms().map { it.second })
    }

    @Test
    fun `resuming a feed whose body is unchanged keeps one alarm`() = runTest {
        repository.setSubscriptionEnabled(subscriptionId, false)
        fixture.scheduler.scheduleUpcomingReminders()

        serves(feed(start), etag = "v1-again")
        repository.setSubscriptionEnabled(subscriptionId, true)

        assertEquals(listOf(start - 15 * 60_000), fixture.reminderAlarms().map { it.second })
    }
}
