package org.onekash.kashcal.ui.components

import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.onekash.kashcal.data.db.entity.Event
import org.onekash.kashcal.ui.components.attendees.AttendeeStatus
import org.onekash.kashcal.ui.components.attendees.AttendeeUiModel
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * The receipt under [EventQuickViewSheet]'s Respond section: an imported invitation shows what
 * the server reported for the user's reply; a synced one, whose schedule status is the
 * organizer's, shows none.
 */
@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [34], qualifiers = "w360dp-h9999dp-mdpi")
class EventQuickViewSheetReplyReceiptTest {

    @get:Rule
    val composeTestRule = createComposeRule()

    private val attendees = listOf(
        AttendeeUiModel("Boss", "boss@example.test", AttendeeStatus.Accepted, isYou = false, isOrganizer = true, sortOrder = 0),
        AttendeeUiModel("Me", "me@example.test", AttendeeStatus.NeedsAction, isYou = true, isOrganizer = false, sortOrder = 1),
    )

    private fun render(importSource: String?, scheduleStatus: String?) {
        composeTestRule.setContent {
            MaterialTheme {
                EventQuickViewSheet(
                    event = Event(
                        uid = "u1", calendarId = 1, title = "Planning", startTs = 1_000, endTs = 4_600_000,
                        timezone = "UTC", dtstamp = 1_000, organizerEmail = "boss@example.test",
                        importSource = importSource, organizerScheduleStatus = scheduleStatus,
                    ),
                    calendarColor = 0xFF4CAF50.toInt(),
                    calendarName = "Work",
                    attendees = attendees,
                    isCurrentUserOnList = true,
                    onDismiss = {},
                    onEdit = {},
                    onDeleteSingle = {},
                )
            }
        }
    }

    private fun count(text: String) = composeTestRule.onAllNodes(hasText(text)).fetchSemanticsNodes().size

    @Test
    fun `an imported invitation shows the server's receipt for the reply`() {
        render(importSource = Event.IMPORT_SOURCE_ICS_FILE, scheduleStatus = "1.2")

        assertEquals(1, count("Reply sent"))
    }

    @Test
    fun `a synced invitation shows no receipt`() {
        render(importSource = null, scheduleStatus = "1.2")

        assertEquals(0, count("Reply sent"))
    }

    @Test
    fun `an imported invitation with no receipt shows none`() {
        render(importSource = Event.IMPORT_SOURCE_ICS_FILE, scheduleStatus = null)

        assertEquals(0, count("Reply pending") + count("Reply sent") + count("Reply not delivered"))
    }
}
