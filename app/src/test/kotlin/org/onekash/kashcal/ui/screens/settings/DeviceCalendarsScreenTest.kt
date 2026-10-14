package org.onekash.kashcal.ui.screens.settings

import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.onekash.kashcal.data.calendar_provider.DeviceCalendar
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Tests the Device calendars screen's calendar list: tapping a calendar routes its id and new
 * value, and the list shows no enabled count and no reminders switch (device reminders are
 * switched in the Calendar notifications sheet). Run the class alone: Robolectric runs of more
 * than one class hit a native crash.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "en-rUS-w360dp-h2000dp-mdpi")
class DeviceCalendarsScreenTest {

    @get:Rule
    val composeTestRule = createComposeRule()

    private val calendars = listOf(
        DeviceCalendar(
            id = 100, displayName = "Calendar", color = 0xFF2196F3.toInt(), accountName = "alex@gmail.example",
            accountType = "org.example", visible = true, accessLevel = 700
        ),
        DeviceCalendar(
            id = 101, displayName = "Phone", color = 0xFF607D8B.toInt(), accountName = "",
            accountType = "LOCAL", visible = true, accessLevel = 700
        ),
    )

    private val toggled = mutableListOf<Pair<Long, Boolean>>()

    private fun show() {
        composeTestRule.setContent {
            MaterialTheme {
                DeviceCalendarsScreen(
                    isEnabled = true,
                    hasReadPermission = true,
                    hasWritePermission = true,
                    deviceCalendars = calendars,
                    enabledCalendarIds = setOf(100L),
                    onNavigateBack = {},
                    onToggle = {},
                    onToggleCalendar = { id, enabled -> toggled += id to enabled },
                    onRequestWritePermission = {},
                )
            }
        }
    }

    @Test
    fun `the list shows the calendars without an enabled count or a reminders switch`() {
        show()

        composeTestRule.onNodeWithText("Calendar").assertExists()
        composeTestRule.onNodeWithText("Phone").assertExists()
        composeTestRule.onNodeWithText("1 / 2 enabled").assertDoesNotExist()
        composeTestRule.onNodeWithText("Reminders").assertDoesNotExist()
        composeTestRule.onNodeWithText("Get notified for device calendar events").assertDoesNotExist()
    }

    @Test
    fun `tapping a calendar routes its id and new value`() {
        show()

        composeTestRule.onNodeWithText("Phone").performClick()

        assertEquals(listOf(101L to true), toggled)
    }
}
