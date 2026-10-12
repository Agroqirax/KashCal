package org.onekash.kashcal.sync.notification

import io.mockk.every
import io.mockk.justRun
import io.mockk.mockk

/**
 * Returns a [ChangeNotificationChannels] whose notifications can't show, for ViewModel tests
 * that don't exercise Change notifications. Each read is stubbed: a relaxed mock would answer
 * the same today, silently.
 */
fun blockedChangeNotificationChannels(): ChangeNotificationChannels = mockk {
    every { canPost() } returns false
    every { areAppNotificationsEnabled() } returns false
    every { isChannelEnabled() } returns false
    justRun { cancel() }
}
