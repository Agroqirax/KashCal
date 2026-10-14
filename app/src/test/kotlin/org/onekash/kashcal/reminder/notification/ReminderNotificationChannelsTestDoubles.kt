package org.onekash.kashcal.reminder.notification

import io.mockk.every
import io.mockk.mockk

/**
 * Returns a [ReminderNotificationChannels] whose two reads come from `appOn` and `channelOn`, read
 * on each call so a test can change them between steps. Each read is stubbed: a relaxed mock would
 * answer false silently.
 */
fun reminderNotificationChannels(
    appOn: () -> Boolean = { true },
    channelOn: () -> Boolean = { true },
): ReminderNotificationChannels = mockk {
    every { areNotificationsEnabled() } answers { appOn() }
    every { isChannelEnabled() } answers { channelOn() }
}

/** Returns reminder channels that can show, for ViewModel tests that don't exercise them. */
fun allowedReminderNotificationChannels(): ReminderNotificationChannels = reminderNotificationChannels()
