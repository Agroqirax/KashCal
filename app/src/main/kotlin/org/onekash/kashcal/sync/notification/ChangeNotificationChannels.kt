package org.onekash.kashcal.sync.notification

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import androidx.core.app.NotificationManagerCompat
import dagger.hilt.android.qualifiers.ApplicationContext
import org.onekash.kashcal.R
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Owns the Calendar updates channel and the one change notification posted on it.
 *
 * The notification goes under `NOTIFICATION_TAG` with `NOTIFICATION_ID`. Android tells
 * notifications apart by tag and ID together, so it can't replace or cancel any of the app's
 * untagged ones. Each post replaces the previous one.
 *
 * Default importance with vibration and lights off, like invitations. Private on the lock
 * screen: one set to hide sensitive content shows only the notification's public version.
 * `KashCalApplication` creates the channel at app start.
 */
@Singleton
class ChangeNotificationChannels @Inject constructor(
    @ApplicationContext private val context: Context
) {
    companion object {
        const val CHANNEL_ID = "calendar_updates"
        const val NOTIFICATION_TAG = "calendar_updates"
        const val NOTIFICATION_ID = 1
    }

    private val notificationManager: NotificationManager by lazy {
        context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
    }

    fun createChannels() {
        val channel = NotificationChannel(
            CHANNEL_ID,
            context.getString(R.string.channel_calendar_updates),
            NotificationManager.IMPORTANCE_DEFAULT
        ).apply {
            description = context.getString(R.string.settings_change_notifications_info)
            setShowBadge(true)
            enableVibration(false)
            enableLights(false)
            lockscreenVisibility = Notification.VISIBILITY_PRIVATE
        }
        notificationManager.createNotificationChannel(channel)
    }

    /** Returns false when the user turned off the app's notifications or hasn't granted them. */
    fun areAppNotificationsEnabled(): Boolean =
        NotificationManagerCompat.from(context).areNotificationsEnabled()

    /** Returns true if the channel exists and the user hasn't blocked it. */
    fun isChannelEnabled(): Boolean {
        val channel = notificationManager.getNotificationChannel(CHANNEL_ID) ?: return false
        return channel.importance != NotificationManager.IMPORTANCE_NONE
    }

    /** Returns whether a change notification posted now would show. */
    fun canPost(): Boolean = areAppNotificationsEnabled() && isChannelEnabled()

    /** Posts [notification], replacing the change notification already shown, if any. */
    fun post(notification: Notification) {
        notificationManager.notify(NOTIFICATION_TAG, NOTIFICATION_ID, notification)
    }

    /** Removes the change notification, if one is shown. */
    fun cancel() {
        notificationManager.cancel(NOTIFICATION_TAG, NOTIFICATION_ID)
    }
}
