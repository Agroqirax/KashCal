package org.onekash.kashcal.sync.notification

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import androidx.core.app.NotificationCompat
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.onekash.kashcal.R
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/**
 * Tests [ChangeNotificationChannels]: the Calendar updates channel's settings, whether a change
 * notification can be posted, and that post and cancel act only on the tagged notification.
 */
@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [34])
class ChangeNotificationChannelsTest {

    private lateinit var context: Context
    private lateinit var notificationManager: NotificationManager
    private lateinit var channels: ChangeNotificationChannels

    @Before
    fun setup() {
        context = RuntimeEnvironment.getApplication()
        notificationManager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        channels = ChangeNotificationChannels(context)
    }

    @After
    fun tearDown() {
        notificationManager.cancelAll()
        notificationManager.deleteNotificationChannel(ChangeNotificationChannels.CHANNEL_ID)
    }

    private fun notification(): Notification =
        NotificationCompat.Builder(context, ChangeNotificationChannels.CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle("Design review")
            .build()

    @Test
    fun `createChannels creates the Calendar updates channel`() {
        channels.createChannels()

        val channel = notificationManager.getNotificationChannel(ChangeNotificationChannels.CHANNEL_ID)
        assertNotNull(channel)
        assertEquals("Calendar updates", channel.name)
        assertEquals(context.getString(R.string.settings_change_notifications_info), channel.description)
        assertEquals(NotificationManager.IMPORTANCE_DEFAULT, channel.importance)
        assertFalse(channel.shouldVibrate())
        assertFalse(channel.shouldShowLights())
        assertEquals(Notification.VISIBILITY_PRIVATE, channel.lockscreenVisibility)
    }

    @Test
    fun `canPost is true once the channel exists and notifications are on`() {
        channels.createChannels()

        assertTrue(channels.areAppNotificationsEnabled())
        assertTrue(channels.isChannelEnabled())
        assertTrue(channels.canPost())
    }

    @Test
    fun `canPost is false when the app's notifications are off`() {
        channels.createChannels()
        shadowOf(notificationManager).setNotificationsEnabled(false)

        assertFalse(channels.areAppNotificationsEnabled())
        assertFalse(channels.canPost())
    }

    @Test
    fun `canPost is false when the user blocked the channel`() {
        notificationManager.createNotificationChannel(
            NotificationChannel(ChangeNotificationChannels.CHANNEL_ID, "Calendar updates", NotificationManager.IMPORTANCE_NONE)
        )

        assertTrue(channels.areAppNotificationsEnabled())
        assertFalse(channels.isChannelEnabled())
        assertFalse(channels.canPost())
    }

    @Test
    fun `post shows the notification under the tag and cancel removes it`() {
        channels.createChannels()

        channels.post(notification())
        val posted = shadowOf(notificationManager).getNotification(
            ChangeNotificationChannels.NOTIFICATION_TAG, ChangeNotificationChannels.NOTIFICATION_ID
        )
        assertNotNull(posted)

        channels.cancel()
        assertEquals(0, notificationManager.activeNotifications.size)
    }

    @Test
    fun `cancel leaves an untagged notification with the same id`() {
        channels.createChannels()
        notificationManager.notify(ChangeNotificationChannels.NOTIFICATION_ID, notification())

        channels.post(notification())
        channels.cancel()

        val left = notificationManager.activeNotifications
        assertEquals(1, left.size)
        assertEquals(null, left.single().tag)
        assertEquals(ChangeNotificationChannels.NOTIFICATION_ID, left.single().id)
    }
}
