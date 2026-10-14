package org.onekash.kashcal.sync.notification

import android.app.Notification
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationCompat
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.onekash.kashcal.MainActivity
import org.onekash.kashcal.data.preferences.KashCalDataStore
import org.onekash.kashcal.domain.changes.RecentChangeItem
import org.onekash.kashcal.domain.reader.EventReader
import org.onekash.kashcal.sync.model.ChangeType
import org.onekash.kashcal.sync.model.ChangedField
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/**
 * Tests [ChangeNotifier] over the Robolectric NotificationManager: when it posts, which batch rows
 * it words (invites and muted calendars left out), the three content tiers, the lock-screen
 * version and the tap intent [ChangeNotifier.parseTap] reads back.
 */
@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [34])
class ChangeNotifierTest {

    private lateinit var context: Context
    private lateinit var notificationManager: NotificationManager
    private lateinit var channels: ChangeNotificationChannels
    private lateinit var dataStore: KashCalDataStore
    private lateinit var eventReader: EventReader
    private lateinit var inviteNotifier: InviteNotifier
    private lateinit var notifier: ChangeNotifier

    private val batchAt = 1_760_000_000_000L
    private val syncStartedAt = batchAt - 5_000
    private var rows: List<RecentChangeItem> = emptyList()
    private var invited: Set<Long> = emptySet()
    private var muted: Set<Long> = emptySet()

    @Before
    fun setup() {
        context = RuntimeEnvironment.getApplication()
        notificationManager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        channels = ChangeNotificationChannels(context)
        channels.createChannels()
        dataStore = mockk()
        every { dataStore.changeNotificationsEnabled } returns flowOf(true)
        coEvery { dataStore.getTimeFormat() } returns "12h"
        eventReader = mockk()
        every { eventReader.observeRecentChanges(any()) } answers { flowOf(rows) }
        coEvery { eventReader.getNotificationMutedCalendarIds() } answers { muted }
        inviteNotifier = mockk()
        coEvery { inviteNotifier.eventsNotifiedSince(any(), any()) } answers { invited }
        notifier = ChangeNotifier(context, channels, dataStore, eventReader, inviteNotifier)
    }

    @After
    fun tearDown() {
        notificationManager.cancelAll()
        notificationManager.deleteNotificationChannel(ChangeNotificationChannels.CHANNEL_ID)
    }

    private fun row(
        id: Long,
        title: String,
        type: ChangeType = ChangeType.MODIFIED,
        eventId: Long? = id + 100,
        calendar: String = "Work",
        detectedAt: Long = batchAt,
        fields: Set<ChangedField> = emptySet(),
        previousStartTs: Long? = null,
        instanceTs: Long = 0,
        calendarId: Long = 1,
    ) = RecentChangeItem(
        id = id, calendarId = calendarId, eventUid = "uid-$id", instanceTs = instanceTs, eventId = eventId,
        changeType = type, title = title, startTs = batchAt + 86_400_000, endTs = batchAt + 90_000_000,
        isAllDay = false, isRecurring = false, detectedAt = detectedAt, changedFields = fields,
        previousStartTs = previousStartTs, previousIsAllDay = previousStartTs?.let { false },
        calendarName = calendar, calendarColor = 0
    )

    private fun posted(): Notification? = shadowOf(notificationManager).getNotification(
        ChangeNotificationChannels.NOTIFICATION_TAG, ChangeNotificationChannels.NOTIFICATION_ID
    )

    private fun Notification.title() = extras.getCharSequence(NotificationCompat.EXTRA_TITLE)?.toString()
    private fun Notification.text() = extras.getCharSequence(NotificationCompat.EXTRA_TEXT)?.toString()
    private fun Notification.lines() =
        extras.getCharSequenceArray(NotificationCompat.EXTRA_TEXT_LINES)?.map { it.toString() }

    private fun Notification.tapIntent(): Intent = shadowOf(contentIntent).savedIntent

    @Test
    fun `nothing is read or posted while the setting is off`() = runTest {
        every { dataStore.changeNotificationsEnabled } returns flowOf(false)
        rows = listOf(row(1, "Design review"))

        assertFalse(notifier.notify(batchAt, syncStartedAt))
        assertNull(posted())
        verify(exactly = 0) { eventReader.observeRecentChanges(any()) }
    }

    @Test
    fun `nothing is read or posted when notifications can't show`() = runTest {
        shadowOf(notificationManager).setNotificationsEnabled(false)
        rows = listOf(row(1, "Design review"))

        assertFalse(notifier.notify(batchAt, syncStartedAt))
        assertNull(posted())
        verify(exactly = 0) { eventReader.observeRecentChanges(any()) }
    }

    // ==================== Muted calendars ====================

    @Test
    fun `a mixed batch posts only the rows of calendars that notify`() = runTest {
        rows = listOf(
            row(1, "Design review", fields = setOf(ChangedField.LOCATION)),
            row(2, "Standup", fields = setOf(ChangedField.LOCATION)),
            row(3, "Bank holiday", type = ChangeType.NEW, calendar = "Holidays", calendarId = 2),
        )
        muted = setOf(2L)

        assertTrue(notifier.notify(batchAt, syncStartedAt))
        val n = posted()!!
        assertEquals("2 events updated", n.title())
        assertEquals(listOf("Design review · Location changed", "Standup · Location changed"), n.lines())
        val shown = n.extras.keySet().joinToString { n.extras.get(it).toString() }
        assertFalse(shown, shown.contains("Holidays") || shown.contains("Bank holiday"))
    }

    @Test
    fun `an all-muted batch posts nothing and still counts as announced`() = runTest {
        // True keeps the background sync from listing these changes in its snackbar.
        rows = listOf(row(1, "Bank holiday", calendarId = 2), row(2, "Clocks change", calendarId = 2))
        muted = setOf(2L)

        assertTrue(notifier.notify(batchAt, syncStartedAt))
        assertNull(posted())
    }

    @Test
    fun `a muted row that was also invited posts nothing`() = runTest {
        rows = listOf(row(1, "Offsite", calendarId = 2))
        muted = setOf(2L)
        invited = setOf(101L)

        assertTrue(notifier.notify(batchAt, syncStartedAt))
        assertNull(posted())
    }

    @Test
    fun `the muted calendars are read on every call`() = runTest {
        rows = listOf(row(1, "Design review", calendarId = 2))
        muted = setOf(2L)
        assertTrue(notifier.notify(batchAt, syncStartedAt))
        assertNull(posted())

        muted = emptySet()

        assertTrue(notifier.notify(batchAt, syncStartedAt))
        assertEquals("Design review", posted()!!.title())
    }

    @Test
    fun `the muted calendars aren't read while the setting is off`() = runTest {
        every { dataStore.changeNotificationsEnabled } returns flowOf(false)
        rows = listOf(row(1, "Design review"))

        assertFalse(notifier.notify(batchAt, syncStartedAt))
        coVerify(exactly = 0) { eventReader.getNotificationMutedCalendarIds() }
    }

    @Test
    fun `a batch with no rows left in the log falls back to the snackbar`() = runTest {
        rows = listOf(row(1, "Earlier sync", detectedAt = batchAt - 60_000))

        assertFalse(notifier.notify(batchAt, syncStartedAt))
        assertNull(posted())
    }

    @Test
    fun `one moved event names the event, the move and its calendar`() = runTest {
        rows = listOf(
            row(1, "Design review", fields = setOf(ChangedField.TIME), previousStartTs = batchAt + 3_600_000)
        )

        assertTrue(notifier.notify(batchAt, syncStartedAt))
        val n = posted()!!
        assertEquals("Design review", n.title())
        assertTrue(n.text()!!, n.text()!!.startsWith("Moved from "))
        assertEquals("Work", n.extras.getCharSequence(NotificationCompat.EXTRA_SUB_TEXT)?.toString())
        assertEquals(ChangeNotificationChannels.CHANNEL_ID, n.channelId)
        assertEquals(ChangeTap(1L), ChangeNotifier.parseTap(n.tapIntent()))
    }

    @Test
    fun `one new event is labeled as new`() = runTest {
        rows = listOf(row(1, "Lunch with Sam", type = ChangeType.NEW))

        assertTrue(notifier.notify(batchAt, syncStartedAt))
        assertTrue(posted()!!.text()!!.startsWith("New event · "))
    }

    @Test
    fun `one removed event is labeled as removed`() = runTest {
        rows = listOf(row(1, "Standup", type = ChangeType.DELETED, eventId = null))

        assertTrue(notifier.notify(batchAt, syncStartedAt))
        assertTrue(posted()!!.text()!!.startsWith("Removed · "))
    }

    @Test
    fun `three changes get a count title, the titles, and one line each`() = runTest {
        rows = listOf(
            row(1, "Design review", fields = setOf(ChangedField.LOCATION)),
            row(2, "Standup", fields = setOf(ChangedField.LOCATION)),
            row(3, "Lunch with Sam", fields = setOf(ChangedField.TITLE)),
        )

        assertTrue(notifier.notify(batchAt, syncStartedAt))
        val n = posted()!!
        assertEquals("3 events updated", n.title())
        assertEquals("Design review, Standup, and Lunch with Sam", n.text())
        assertEquals(
            listOf("Design review · Location changed", "Standup · Location changed", "Lunch with Sam · Title changed"),
            n.lines()
        )
        assertEquals(ChangeTap(null), ChangeNotifier.parseTap(n.tapIntent()))
    }

    @Test
    fun `four or more changes get the count and the calendars involved, no lines`() = runTest {
        rows = listOf(
            row(1, "A", type = ChangeType.NEW, calendar = "Work"),
            row(2, "B", calendar = "Family"),
            row(3, "C", calendar = "Work"),
            row(4, "D", calendar = "Shared"),
            row(5, "E", type = ChangeType.DELETED, eventId = null, calendar = "Family"),
        )

        assertTrue(notifier.notify(batchAt, syncStartedAt))
        val n = posted()!!
        assertEquals("5 calendar updates", n.title())
        assertEquals("Work, Family, and Shared", n.text())
        assertNull(n.lines())
        assertEquals(ChangeTap(null), ChangeNotifier.parseTap(n.tapIntent()))
    }

    @Test
    fun `rows from other syncs are left out`() = runTest {
        rows = listOf(row(1, "This sync"), row(2, "Earlier", detectedAt = batchAt - 1))

        assertTrue(notifier.notify(batchAt, syncStartedAt))
        assertEquals("This sync", posted()!!.title())
    }

    @Test
    fun `an event that got its invite notification is left out`() = runTest {
        rows = listOf(row(1, "Quarterly planning", type = ChangeType.NEW), row(2, "Design review"))
        invited = setOf(101L)

        assertTrue(notifier.notify(batchAt, syncStartedAt))
        val n = posted()!!
        assertEquals("Design review", n.title())
        assertEquals(ChangeTap(2L), ChangeNotifier.parseTap(n.tapIntent()))
    }

    @Test
    fun `a batch of invites only posts nothing but counts as announced`() = runTest {
        rows = listOf(row(1, "Quarterly planning", type = ChangeType.NEW))
        invited = setOf(101L)

        assertTrue(notifier.notify(batchAt, syncStartedAt))
        assertNull(posted())
    }

    @Test
    fun `the public version for the lock screen shows only Calendar updates`() = runTest {
        rows = listOf(row(1, "Design review"))

        notifier.notify(batchAt, syncStartedAt)
        val n = posted()!!
        assertEquals(Notification.VISIBILITY_PRIVATE, n.visibility)
        val public = n.publicVersion
        assertNotNull(public)
        assertEquals("Calendar updates", public.extras.getCharSequence(NotificationCompat.EXTRA_TITLE)?.toString())
        assertNull(public.extras.getCharSequence(NotificationCompat.EXTRA_TEXT))
        assertEquals(NotificationCompat.CATEGORY_EVENT, n.category)
    }

    @Test
    fun `the tap opens MainActivity explicitly with an immutable intent`() = runTest {
        rows = listOf(row(1, "Design review"))

        notifier.notify(batchAt, syncStartedAt)
        val n = posted()!!
        val intent = n.tapIntent()
        assertEquals(MainActivity::class.java.name, intent.component?.className)
        assertEquals(ChangeNotifier.ACTION_SHOW_CHANGES, intent.action)
        assertEquals(
            Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP,
            intent.flags and (Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        )
        assertTrue(shadowOf(n.contentIntent).flags and PendingIntent.FLAG_IMMUTABLE != 0)
        assertTrue("a tap removes it", n.flags and Notification.FLAG_AUTO_CANCEL != 0)
    }

    @Test
    fun `a two-change batch replacing a one-change one opens the sheet`() = runTest {
        rows = listOf(row(1, "Design review"))
        notifier.notify(batchAt, syncStartedAt)

        rows = listOf(row(2, "Standup", detectedAt = batchAt + 1), row(3, "Lunch", detectedAt = batchAt + 1))
        notifier.notify(batchAt + 1, syncStartedAt)

        assertEquals(ChangeTap(null), ChangeNotifier.parseTap(posted()!!.tapIntent()))
    }

    @Test
    fun `a one-change batch replacing another opens the newer event`() = runTest {
        rows = listOf(row(1, "Design review"))
        notifier.notify(batchAt, syncStartedAt)

        rows = listOf(row(9, "Standup", detectedAt = batchAt + 1))
        notifier.notify(batchAt + 1, syncStartedAt)

        assertEquals(ChangeTap(9L), ChangeNotifier.parseTap(posted()!!.tapIntent()))
    }

    @Test
    fun `parseTap ignores other intents`() {
        assertNull(ChangeNotifier.parseTap(null))
        assertNull(ChangeNotifier.parseTap(Intent("org.onekash.kashcal.SHOW_REMINDER_EVENT")))
    }
}
