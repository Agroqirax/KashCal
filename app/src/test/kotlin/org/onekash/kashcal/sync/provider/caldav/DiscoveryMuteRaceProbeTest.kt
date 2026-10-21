package org.onekash.kashcal.sync.provider.caldav

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.onekash.kashcal.data.credential.AccountCredentials
import org.onekash.kashcal.data.db.KashCalDatabase
import org.onekash.kashcal.data.db.entity.Account
import org.onekash.kashcal.data.db.entity.Calendar
import org.onekash.kashcal.data.repository.AccountRepository
import org.onekash.kashcal.data.repository.CalendarRepository
import org.onekash.kashcal.data.repository.CalendarRepositoryImpl
import org.onekash.kashcal.domain.model.AccountProvider
import org.onekash.kashcal.sync.client.CalDavClient
import org.onekash.kashcal.sync.client.CalDavClientFactory
import org.onekash.kashcal.sync.client.model.CalDavCalendar
import org.onekash.kashcal.sync.client.model.CalDavResult
import org.onekash.kashcal.sync.discovery.DiscoveryResult
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Probe: can a calendar refresh undo a mute the user just set?
 *
 * CalDavAccountDiscoveryService.refreshCalendars (the account sheet's "Discover calendars" and a
 * password change) reads each calendar row, copies it with the server's name and color, and writes
 * the whole row back. Runs the real service over the real CalendarRepositoryImpl and an in-memory
 * database; the CalDAV client is a mock returning one listed calendar. A wrapper over the real
 * repository injects the user's mute right after the read, and times the read-to-write window.
 */
@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [34])
class DiscoveryMuteRaceProbeTest {

    private lateinit var db: KashCalDatabase
    private lateinit var real: CalendarRepositoryImpl
    private var calendarId = 0L
    private val url = "https://dav.example.test/calendars/me/work/"

    private val accountRepository = mockk<AccountRepository>()
    private val client = mockk<CalDavClient>(relaxed = true)
    private val clientFactory = mockk<CalDavClientFactory>()

    @Before
    fun setup() = runTest {
        val context = ApplicationProvider.getApplicationContext<Context>()
        db = Room.inMemoryDatabaseBuilder(context, KashCalDatabase::class.java).allowMainThreadQueries().build()
        val account = Account(
            provider = AccountProvider.CALDAV, email = "me@dav.example.test", displayName = "dav.example.test",
            homeSetUrl = "https://dav.example.test/calendars/me/", isEnabled = true,
        )
        val accountId = db.accountsDao().insert(account)
        calendarId = db.calendarsDao().insert(
            Calendar(accountId = accountId, caldavUrl = url, displayName = "Work", color = -1)
        )
        real = CalendarRepositoryImpl(db.calendarsDao())
        coEvery { accountRepository.getAccountById(accountId) } returns account.copy(id = accountId)
        coEvery { accountRepository.getCredentials(accountId) } returns
            AccountCredentials(username = "me", password = "pw", serverUrl = "https://dav.example.test")
        every { clientFactory.createClient(any(), any()) } returns client
        coEvery { client.listCalendars(any()) } returns CalDavResult.Success(
            listOf(CalDavCalendar(href = "/calendars/me/work/", url = url, displayName = "Work", color = null, ctag = "1", isReadOnly = false))
        )
    }

    @After
    fun tearDown() = db.close()

    private suspend fun accountId() = db.calendarsDao().getById(calendarId)!!.accountId

    private suspend fun muted() = db.calendarsDao().getById(calendarId)!!.isNotificationMuted

    @Test
    fun `a mute set before the refresh survives it`() = runTest {
        db.calendarsDao().setNotificationMuted(calendarId, true)

        val result = CalDavAccountDiscoveryService(clientFactory, accountRepository, real).refreshCalendars(accountId())

        assertTrue(result is DiscoveryResult.Success)
        assertEquals(true, muted())
    }

    @Test
    fun `a mute landing between the refresh's read and write is undone`() = runTest {
        val raced = object : CalendarRepository by real {
            override suspend fun getCalendarByUrl(caldavUrl: String): Calendar? =
                real.getCalendarByUrl(caldavUrl).also { db.calendarsDao().setNotificationMuted(calendarId, true) }
        }

        CalDavAccountDiscoveryService(clientFactory, accountRepository, raced).refreshCalendars(accountId())

        val after = muted()
        println("PROBE mute after a mute that landed mid-refresh: $after")
        assertEquals("the refresh writes the stale copy back over the mute", false, after)
    }

    @Test
    fun `measure the read-to-write window`() = runTest {
        val gapsNs = mutableListOf<Long>()
        var readAt = 0L
        val timed = object : CalendarRepository by real {
            override suspend fun getCalendarByUrl(caldavUrl: String): Calendar? =
                real.getCalendarByUrl(caldavUrl).also { readAt = System.nanoTime() }

            override suspend fun updateCalendar(calendar: Calendar) {
                gapsNs += System.nanoTime() - readAt
                real.updateCalendar(calendar)
            }
        }
        val service = CalDavAccountDiscoveryService(clientFactory, accountRepository, timed)
        repeat(200) { service.refreshCalendars(accountId()) }

        val sorted = gapsNs.sorted()
        println(
            "PROBE read-to-write window over ${sorted.size} refreshes: median=${sorted[sorted.size / 2] / 1000} us, " +
                "p99=${sorted[(sorted.size * 99) / 100] / 1000} us, max=${sorted.last() / 1000} us"
        )
        assertEquals(200, sorted.size)
    }
}
