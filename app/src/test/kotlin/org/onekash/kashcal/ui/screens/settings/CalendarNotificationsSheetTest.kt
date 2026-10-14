package org.onekash.kashcal.ui.screens.settings

import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.state.ToggleableState
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import org.onekash.kashcal.ui.permission.ChangeNotificationsStep
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.assertHasClickAction
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.performClick
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.util.Locale

/**
 * Compose tests for `CalendarNotificationsContent`, the body of the Calendar notifications sheet:
 * the intro line and its info button, which shows the muted-calendar detail inline and starts
 * collapsed on every open, section and account headers, the Hidden line, switch on means
 * notifying, the whole row as the switch's one target, the Device calendars switch and its info
 * button (switching it off disables the device rows, which keep their values), and the blocked
 * banner leaving every switch usable. Run the class
 * alone: Robolectric runs of more than one class hit a native crash.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "en-rUS-w360dp-h2000dp-mdpi")
class CalendarNotificationsSheetTest {

    @get:Rule
    val composeTestRule = createComposeRule()

    private val savedLocale = Locale.getDefault()

    @Before
    fun pinLocale() = Locale.setDefault(Locale.US)

    @After
    fun restoreLocale() = Locale.setDefault(savedLocale)

    private fun room(id: Long, name: String, notifies: Boolean = true, hidden: Boolean = false) =
        CalendarNotificationRow(CalendarNotificationSource.ROOM, id, name, 0xFF2196F3.toInt(), notifies, hidden)

    private fun device(id: Long, name: String, notifies: Boolean = true) =
        CalendarNotificationRow(CalendarNotificationSource.DEVICE, id, name, 0xFF3F51B5.toInt(), notifies, hidden = false)

    private val sections = listOf(
        CalendarNotificationSection(
            "alex@example.test",
            listOf(CalendarNotificationGroup(null, listOf(room(1, "Home"), room(2, "Work", notifies = false))))
        ),
        CalendarNotificationSection(
            "Calendar Feeds",
            listOf(CalendarNotificationGroup(null, listOf(room(3, "Premier League fixtures", notifies = false, hidden = true))))
        ),
        CalendarNotificationSection(
            "Device calendars",
            listOf(
                CalendarNotificationGroup("alex@gmail.example", listOf(device(100, "Holidays in United States", notifies = false))),
                CalendarNotificationGroup("Local", listOf(device(102, "Phone"))),
            ),
            isDevice = true,
        ),
    )

    private val toggles = mutableListOf<Pair<CalendarNotificationRow, Boolean>>()
    private val deviceReminderCalls = mutableListOf<Boolean>()
    private var fixCalls = 0

    private fun show(deviceRemindersEnabled: Boolean = true, blockedBy: ChangeNotificationsStep? = null) {
        composeTestRule.setContent {
            MaterialTheme {
                CalendarNotificationsContent(
                    sections = sections,
                    deviceRemindersEnabled = deviceRemindersEnabled,
                    notificationsBlockedBy = blockedBy,
                    onToggle = { row, muted -> toggles += row to muted },
                    onSetDeviceReminders = { deviceReminderCalls += it },
                    onFixNotifications = { fixCalls++ },
                )
            }
        }
    }

    private fun row(name: String) = composeTestRule.onNode(hasText(name) and SemanticsMatcher.keyIsDefined(SemanticsProperties.Role))

    @Test
    fun `the title, intro line, headers and account sub-headers show`() {
        show()

        composeTestRule.onNodeWithText("Calendar notifications").assertIsDisplayed()
        composeTestRule.onNodeWithText("Choose which calendars can notify you.").assertIsDisplayed()
        composeTestRule.onNodeWithText("invitations", substring = true).assertDoesNotExist()
        for (text in listOf("alex@example.test", "Calendar Feeds", "Device calendars", "alex@gmail.example", "Local")) {
            composeTestRule.onNodeWithText(text).assertIsDisplayed()
        }
    }

    @Test
    fun `the muted-calendar detail is hidden until the info button is tapped, and a second tap hides it`() {
        show()
        val detail = "Muted calendars still show and sync, but don't notify you."
        val info = composeTestRule.onNodeWithContentDescription("About muted calendars")

        composeTestRule.onNodeWithText(detail).assertDoesNotExist()
        info.assert(SemanticsMatcher.expectValue(SemanticsProperties.StateDescription, "collapsed"))
        info.assertHasClickAction().performClick()
        composeTestRule.onNodeWithText(detail).assertIsDisplayed()
        info.assert(SemanticsMatcher.expectValue(SemanticsProperties.StateDescription, "expanded"))
        info.performClick()
        composeTestRule.onNodeWithText(detail).assertDoesNotExist()
    }

    @Test
    fun `the detail starts hidden each time the content is shown again`() {
        // The sheet composes its content only while it is open, so a reopen is a fresh composition.
        var shown by mutableStateOf(true)
        composeTestRule.setContent {
            MaterialTheme {
                if (shown) {
                    CalendarNotificationsContent(
                        sections = sections,
                        deviceRemindersEnabled = true,
                        notificationsBlockedBy = null,
                        onToggle = { _, _ -> },
                        onSetDeviceReminders = {},
                        onFixNotifications = {},
                    )
                }
            }
        }
        val detail = "Muted calendars still show and sync, but don't notify you."
        composeTestRule.onNodeWithContentDescription("About muted calendars").performClick()
        composeTestRule.onNodeWithText(detail).assertIsDisplayed()

        shown = false
        composeTestRule.waitForIdle()
        shown = true
        composeTestRule.waitForIdle()

        composeTestRule.onNodeWithText(detail).assertDoesNotExist()
    }

    @Test
    fun `the title row carries the Beta badge`() {
        show()

        composeTestRule.onNodeWithText("Beta").assertIsDisplayed()
    }

    @Test
    fun `a switch is on when its calendar notifies`() {
        show()

        row("Home").assert(SemanticsMatcher.expectValue(SemanticsProperties.ToggleableState, ToggleableState.On))
        row("Work").assert(SemanticsMatcher.expectValue(SemanticsProperties.ToggleableState, ToggleableState.Off))
        row("Home").assert(SemanticsMatcher.expectValue(SemanticsProperties.Role, Role.Switch))
    }

    @Test
    fun `a hidden calendar is listed with a Hidden line`() {
        show()

        composeTestRule.onNodeWithText("Premier League fixtures").assertIsDisplayed()
        assertEquals(1, composeTestRule.onAllNodesWithText("Hidden").fetchSemanticsNodes().size)
    }

    @Test
    fun `tapping a row toggles it once and routes by source`() {
        show()

        row("Home").performClick()
        row("Holidays in United States").performClick()

        assertEquals(
            listOf(1L to true, 100L to false),
            toggles.map { (r, muted) -> r.id to muted }
        )
        assertEquals(
            listOf(CalendarNotificationSource.ROOM, CalendarNotificationSource.DEVICE),
            toggles.map { it.first.source }
        )
    }

    private fun deviceSwitch() = composeTestRule.onNode(
        hasContentDescription("Device calendar notifications") and SemanticsMatcher.expectValue(SemanticsProperties.Role, Role.Switch)
    )

    @Test
    fun `with the Device calendars switch off the device rows are disabled and keep their values, and the switch turns them on`() {
        show(deviceRemindersEnabled = false)

        deviceSwitch().assertIsEnabled()
            .assert(SemanticsMatcher.expectValue(SemanticsProperties.ToggleableState, ToggleableState.Off))
        row("Phone").assertIsNotEnabled()
        row("Phone").assert(SemanticsMatcher.expectValue(SemanticsProperties.ToggleableState, ToggleableState.On))
        row("Holidays in United States").assert(SemanticsMatcher.expectValue(SemanticsProperties.ToggleableState, ToggleableState.Off))
        row("Home").assertIsEnabled()

        row("Phone").performClick()
        assertEquals(emptyList<Pair<CalendarNotificationRow, Boolean>>(), toggles)

        deviceSwitch().performClick()
        assertEquals(listOf(true), deviceReminderCalls)
        composeTestRule.onNodeWithText("Turn on").assertDoesNotExist()
        composeTestRule.onNodeWithText("Device calendar reminders are off.").assertDoesNotExist()
    }

    @Test
    fun `the Device calendars switch is on while device reminders are on, and a tap turns them off`() {
        show()

        deviceSwitch().assert(SemanticsMatcher.expectValue(SemanticsProperties.ToggleableState, ToggleableState.On))
        row("Phone").assertIsEnabled()
        deviceSwitch().performClick()
        assertEquals(listOf(false), deviceReminderCalls)
    }

    @Test
    fun `the Device calendars header stays a heading next to its switch`() {
        show()

        composeTestRule.onNodeWithText("Device calendars")
            .assert(SemanticsMatcher.keyIsDefined(SemanticsProperties.Heading))
    }

    @Test
    fun `only the device section has a header switch and an info button`() {
        show()

        val switch = SemanticsMatcher.expectValue(SemanticsProperties.Role, Role.Switch)
        assertEquals(1, composeTestRule.onAllNodes(switch and hasContentDescription("Device calendar notifications")).fetchSemanticsNodes().size)
        // The switch says what it turns off, not just the heading's text.
        assertEquals(0, composeTestRule.onAllNodes(switch and hasContentDescription("Device calendars")).fetchSemanticsNodes().size)
        assertEquals(1, composeTestRule.onAllNodes(hasContentDescription("About device calendar notifications")).fetchSemanticsNodes().size)
        assertEquals(0, composeTestRule.onAllNodes(hasContentDescription("alex@example.test")).fetchSemanticsNodes().size)
    }

    private fun assertDeviceInfoToggles() {
        val detail = "Turn off to silence all device calendars, including ones you add later."
        val info = composeTestRule.onNodeWithContentDescription("About device calendar notifications")

        composeTestRule.onNodeWithText(detail).assertDoesNotExist()
        info.assert(SemanticsMatcher.expectValue(SemanticsProperties.StateDescription, "collapsed"))
        info.performClick()
        composeTestRule.onNodeWithText(detail).assertIsDisplayed()
        info.assert(SemanticsMatcher.expectValue(SemanticsProperties.StateDescription, "expanded"))
        info.performClick()
        composeTestRule.onNodeWithText(detail).assertDoesNotExist()
    }

    @Test
    fun `the device info is hidden until tapped while the switch is on`() {
        show()
        assertDeviceInfoToggles()
    }

    @Test
    fun `the device info is hidden until tapped while the switch is off`() {
        show(deviceRemindersEnabled = false)
        assertDeviceInfoToggles()
    }

    @Test
    fun `the blocked banner shows its action and leaves the switches usable`() {
        show(blockedBy = ChangeNotificationsStep.RequestPermission)

        composeTestRule.onNodeWithText("KashCal needs notification permission to remind you about upcoming events.")
            .assertIsDisplayed()
        composeTestRule.onNodeWithText("Enable").performClick()
        assertEquals(1, fixCalls)

        row("Work").assertIsEnabled().performClick()
        assertEquals(listOf(2L to false), toggles.map { (r, muted) -> r.id to muted })
    }

    @Test
    fun `the banner names the app's notifications when they are off in system settings`() {
        show(blockedBy = ChangeNotificationsStep.OpenAppSettings)

        composeTestRule.onNodeWithText(
            "Notifications are off for KashCal in system settings, so no calendar can notify you."
        ).assertIsDisplayed()
        assertEquals(
            0,
            composeTestRule.onAllNodesWithText("KashCal needs notification permission to remind you about upcoming events.")
                .fetchSemanticsNodes().size
        )
        composeTestRule.onNodeWithText("Open Settings").performClick()
        assertEquals(1, fixCalls)
    }

    @Test
    fun `the banner names event reminders when only their channel is off`() {
        show(blockedBy = ChangeNotificationsStep.OpenChannelSettings)

        composeTestRule.onNodeWithText("Event reminders are off in system settings, so no reminders can show.")
            .assertIsDisplayed()
        composeTestRule.onNodeWithText("Open Settings").performClick()
        assertEquals(1, fixCalls)
    }

    @Test
    fun `device account sub-headers are headings`() {
        show()

        for (subheader in listOf("alex@gmail.example", "Local")) {
            composeTestRule.onNodeWithText(subheader)
                .assert(SemanticsMatcher.keyIsDefined(SemanticsProperties.Heading))
        }
    }

    @Test
    fun `no banner while notifications can show`() {
        show()

        assertEquals(0, composeTestRule.onAllNodesWithText("Enable").fetchSemanticsNodes().size)
        assertEquals(0, composeTestRule.onAllNodesWithText("Open Settings").fetchSemanticsNodes().size)
    }

    @Test
    fun `two accounts with the same name and two same-named device accounts both render`() {
        // A second login on the same CalDAV server gets the same server name as its account name.
        val twins = buildCalendarNotificationSections(
            calendarGroups = org.onekash.kashcal.ui.model.CalendarGroup.fromCalendarsAndAccounts(
                listOf(
                    org.onekash.kashcal.data.db.entity.Calendar(
                        id = 1, accountId = 1, caldavUrl = "a", displayName = "Alex", color = 0
                    ),
                    org.onekash.kashcal.data.db.entity.Calendar(
                        id = 2, accountId = 2, caldavUrl = "b", displayName = "Sam", color = 0
                    ),
                ),
                listOf(
                    org.onekash.kashcal.data.db.entity.Account(
                        id = 1, provider = org.onekash.kashcal.domain.model.AccountProvider.CALDAV,
                        email = "alex@dav.example.test", displayName = "dav.example.test"
                    ),
                    org.onekash.kashcal.data.db.entity.Account(
                        id = 2, provider = org.onekash.kashcal.domain.model.AccountProvider.CALDAV,
                        email = "sam@dav.example.test", displayName = "dav.example.test"
                    ),
                ),
                localLabel = "Offline",
                icsLabel = "Calendar Feeds",
            ),
            deviceCalendarsEnabled = true,
            deviceCalendars = listOf(
                org.onekash.kashcal.data.calendar_provider.DeviceCalendar(
                    id = 100, displayName = "Phone", color = 0, accountName = "", accountType = "LOCAL",
                    visible = true, accessLevel = 700
                ),
                org.onekash.kashcal.data.calendar_provider.DeviceCalendar(
                    id = 101, displayName = "Tablet", color = 0, accountName = "Local", accountType = "LOCAL",
                    visible = true, accessLevel = 700
                ),
            ),
            enabledDeviceIds = setOf(100, 101),
            hiddenDeviceIds = emptySet(),
            mutedDeviceIds = emptySet(),
            contactsHeader = "Contacts",
            deviceHeader = "Device calendars",
            deviceLocalLabel = "Local",
        )
        composeTestRule.setContent {
            MaterialTheme {
                CalendarNotificationsContent(
                    sections = twins,
                    deviceRemindersEnabled = true,
                    notificationsBlockedBy = null,
                    onToggle = { _, _ -> },
                    onSetDeviceReminders = {},
                    onFixNotifications = {},
                )
            }
        }

        composeTestRule.onNodeWithText("Alex").assertIsDisplayed()
        composeTestRule.onNodeWithText("Sam").assertIsDisplayed()
        composeTestRule.onNodeWithText("Phone").assertIsDisplayed()
        composeTestRule.onNodeWithText("Tablet").assertIsDisplayed()
    }
}
