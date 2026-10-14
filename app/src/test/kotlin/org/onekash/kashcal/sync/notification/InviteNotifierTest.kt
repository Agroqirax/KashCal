package org.onekash.kashcal.sync.notification

import io.mockk.Runs
import io.mockk.clearAllMocks
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.just
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.onekash.kashcal.data.db.dao.AttendeesDao
import org.onekash.kashcal.data.db.dao.CalendarsDao
import org.onekash.kashcal.data.db.entity.Account
import org.onekash.kashcal.data.db.entity.Attendee
import org.onekash.kashcal.data.db.entity.Calendar
import org.onekash.kashcal.data.db.entity.Event
import org.onekash.kashcal.data.db.entity.SyncStatus
import org.onekash.kashcal.domain.model.AccountProvider
import org.onekash.kashcal.reminder.notification.InviteNotificationManager

class InviteNotifierTest {

    private lateinit var attendeesDao: AttendeesDao
    private lateinit var notificationManager: InviteNotificationManager
    private lateinit var calendarsDao: CalendarsDao
    private lateinit var notifier: InviteNotifier

    private val account = Account(
        id = 1L,
        provider = AccountProvider.CALDAV,
        email = "self@example.test",
        calendarUserAddresses = listOf("mailto:self@example.test")
    )
    private val calendar = Calendar(
        id = 9L,
        accountId = account.id,
        caldavUrl = "https://server/cal/",
        displayName = "Work",
        color = -1
    )
    private val event = Event(
        id = 100L,
        uid = "evt-uid",
        calendarId = calendar.id,
        title = "Quarterly review",
        startTs = 1_700_000_000_000L,
        endTs = 1_700_003_600_000L,
        dtstamp = 1_700_000_000_000L,
        organizerEmail = "boss@example.test",
        organizerName = "Boss",
        syncStatus = SyncStatus.SYNCED
    )

    @Before
    fun setup() {
        attendeesDao = mockk(relaxed = true)
        notificationManager = mockk(relaxed = true)
        calendarsDao = mockk()
        coEvery { calendarsDao.getById(calendar.id) } returns calendar
        notifier = InviteNotifier(
            attendeesDao = attendeesDao,
            notificationManager = notificationManager,
            calendarsDao = calendarsDao
        )
    }

    @After
    fun tearDown() = clearAllMocks()

    private fun selfRow(notifiedAt: Long? = null, partstat: String = "NEEDS-ACTION") = Attendee(
        id = 50L,
        eventId = event.id,
        address = "mailto:self@example.test",
        partstat = partstat,
        notifiedAt = notifiedAt
    )
    private fun aliceRow() = Attendee(
        id = 51L,
        eventId = event.id,
        address = "mailto:alice@example.test",
        partstat = "ACCEPTED"
    )

    @Test
    fun `notifyNew fires for newly-arrived NEEDS-ACTION self row`() = runTest {
        coEvery { attendeesDao.getForEventOnce(event.id) } returns listOf(selfRow(), aliceRow())
        coEvery { attendeesDao.markNotified(50L, any()) } just Runs

        notifier.notifyNew(event, account)

        coVerify(exactly = 1) {
            notificationManager.showInvite(event = event, attendeeRowId = 50L, organizerLabel = "Boss")
        }
        coVerify { attendeesDao.markNotified(50L, any()) }
    }

    @Test
    fun `notifyNew falls back to organizer email when name is blank`() = runTest {
        val noNameEvent = event.copy(organizerName = null)
        coEvery { attendeesDao.getForEventOnce(event.id) } returns listOf(selfRow())
        coEvery { attendeesDao.markNotified(any(), any()) } just Runs

        notifier.notifyNew(noNameEvent, account)

        coVerify {
            notificationManager.showInvite(
                event = noNameEvent,
                attendeeRowId = 50L,
                organizerLabel = "boss@example.test"
            )
        }
    }

    @Test
    fun `notifyNew skips when notified_at is already set (dedup)`() = runTest {
        coEvery { attendeesDao.getForEventOnce(event.id) } returns listOf(selfRow(notifiedAt = 999L))

        notifier.notifyNew(event, account)

        coVerify(exactly = 0) { notificationManager.showInvite(any(), any(), any()) }
        coVerify(exactly = 0) { attendeesDao.markNotified(any(), any()) }
    }

    @Test
    fun `notifyNew does not fire for ACCEPTED self row`() = runTest {
        coEvery { attendeesDao.getForEventOnce(event.id) } returns listOf(selfRow(partstat = "ACCEPTED"))

        notifier.notifyNew(event, account)

        coVerify(exactly = 0) { notificationManager.showInvite(any(), any(), any()) }
    }

    @Test
    fun `notifyNew does not fire for non-self attendee`() = runTest {
        coEvery { attendeesDao.getForEventOnce(event.id) } returns listOf(aliceRow())

        notifier.notifyNew(event, account)

        coVerify(exactly = 0) { notificationManager.showInvite(any(), any(), any()) }
    }

    @Test
    fun `notifyNew does not re-fire on stale-pull (dedup via merge-preserve)`() = runTest {
        // The invite already fired (notified_at set) and the user RSVPed ACCEPTED. A pull that
        // races the server's REPLY queue returns NEEDS-ACTION, and replaceForEvent keeps the
        // prior notified_at, so the notifier sees NEEDS-ACTION with notified_at set and
        // doesn't re-fire.
        coEvery { attendeesDao.getForEventOnce(event.id) } returns listOf(
            selfRow(notifiedAt = 1_500_000L, partstat = "NEEDS-ACTION")
        )

        notifier.notifyNew(event, account)

        coVerify(exactly = 0) { notificationManager.showInvite(any(), any(), any()) }
    }

    @Test
    fun `cancelForEvent forwards to notification manager for every row`() = runTest {
        coEvery { attendeesDao.getForEventOnce(event.id) } returns listOf(selfRow(), aliceRow())

        notifier.cancelForEvent(event.id)

        coVerify { notificationManager.cancelForEvent(event.id, 50L) }
        coVerify { notificationManager.cancelForEvent(event.id, 51L) }
    }

    @Test
    fun `notifyNew aborts cleanly when organizer fields are blank`() = runTest {
        val anonEvent = event.copy(organizerName = null, organizerEmail = null)
        coEvery { attendeesDao.getForEventOnce(event.id) } returns listOf(selfRow())

        notifier.notifyNew(anonEvent, account)

        coVerify(exactly = 0) { notificationManager.showInvite(any(), any(), any()) }
    }

    @Test
    fun `eventsNotifiedSince keeps the given events that had an invite notification`() = runTest {
        every { notificationManager.areNotificationsEnabled() } returns true
        coEvery { attendeesDao.getEventIdsNotifiedSince(1_000L) } returns listOf(100L, 300L)

        assertEquals(setOf(100L), notifier.eventsNotifiedSince(listOf(100L, 200L), since = 1_000L))
    }

    @Test
    fun `eventsNotifiedSince is empty without reading when invite notifications are off`() = runTest {
        // notifyNew still sets notified_at when the invite channel is off, so the column alone
        // doesn't prove a notification was shown.
        every { notificationManager.areNotificationsEnabled() } returns false

        assertEquals(emptySet<Long>(), notifier.eventsNotifiedSince(listOf(100L), since = 1_000L))
        coVerify(exactly = 0) { attendeesDao.getEventIdsNotifiedSince(any()) }
    }

    // ==================== Muted calendar ====================

    @Test
    fun `a muted calendar's invite shows nothing but is still marked notified`() = runTest {
        coEvery { calendarsDao.getById(calendar.id) } returns calendar.copy(isNotificationMuted = true)
        coEvery { attendeesDao.getForEventOnce(event.id) } returns listOf(selfRow(), aliceRow())
        coEvery { attendeesDao.markNotified(50L, any()) } just Runs

        notifier.notifyNew(event, account)

        coVerify(exactly = 0) { notificationManager.showInvite(any(), any(), any()) }
        coVerify(exactly = 1) { attendeesDao.markNotified(50L, any()) }
    }

    @Test
    fun `an invite marked while muted is not shown after unmuting`() = runTest {
        // Unmuted again: the row already carries notified_at from the muted pull.
        coEvery { attendeesDao.getForEventOnce(event.id) } returns listOf(selfRow(notifiedAt = 1_500_000L))

        notifier.notifyNew(event, account)

        coVerify(exactly = 0) { notificationManager.showInvite(any(), any(), any()) }
    }

    @Test
    fun `the calendar is read only when a row would notify`() = runTest {
        coEvery { attendeesDao.getForEventOnce(event.id) } returns listOf(aliceRow(), selfRow(partstat = "ACCEPTED"))

        notifier.notifyNew(event, account)

        coVerify(exactly = 0) { calendarsDao.getById(any()) }
    }

    @Test
    fun `a missing calendar row counts as notifying`() = runTest {
        coEvery { calendarsDao.getById(calendar.id) } returns null
        coEvery { attendeesDao.getForEventOnce(event.id) } returns listOf(selfRow())
        coEvery { attendeesDao.markNotified(50L, any()) } just Runs

        notifier.notifyNew(event, account)

        coVerify(exactly = 1) { notificationManager.showInvite(event = event, attendeeRowId = 50L, organizerLabel = "Boss") }
    }
}
