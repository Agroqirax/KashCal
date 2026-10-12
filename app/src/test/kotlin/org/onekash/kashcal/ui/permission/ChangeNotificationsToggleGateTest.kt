package org.onekash.kashcal.ui.permission

import org.junit.Assert.assertEquals
import org.junit.Test

/** Tests [changeNotificationsToggleStep]: what a tap on the Change notifications switch does. */
class ChangeNotificationsToggleGateTest {

    private fun step(
        enable: Boolean = true,
        appEnabled: Boolean = true,
        channelEnabled: Boolean = true,
        permissionGranted: Boolean = true,
    ) = changeNotificationsToggleStep(enable, appEnabled, channelEnabled, permissionGranted)

    @Test
    fun `turning it off needs nothing`() {
        assertEquals(ChangeNotificationsStep.TurnOff, step(enable = false, appEnabled = false, permissionGranted = false))
    }

    @Test
    fun `turning it on with notifications working turns it on`() {
        assertEquals(ChangeNotificationsStep.TurnOn, step())
    }

    @Test
    fun `turning it on without the permission asks for it`() {
        assertEquals(
            ChangeNotificationsStep.RequestPermission,
            step(appEnabled = false, permissionGranted = false)
        )
    }

    @Test
    fun `with the permission but the app's notifications off it opens the app's settings`() {
        assertEquals(ChangeNotificationsStep.OpenAppSettings, step(appEnabled = false))
    }

    @Test
    fun `with the app's notifications on but the channel blocked it opens the channel's settings`() {
        assertEquals(ChangeNotificationsStep.OpenChannelSettings, step(channelEnabled = false))
    }
}
