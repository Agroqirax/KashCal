package org.onekash.kashcal.ui.components

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.state.ToggleableState
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertWidthIsEqualTo
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.unit.Density
import org.junit.Assert.assertEquals
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.test.assertHeightIsAtLeast
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.dp
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * Tests the drawer's calendar row ([CalendarCheckboxRow]): it stays a 48dp touch
 * target and a tap on it toggles the calendar. The row's small vertical padding
 * isn't asserted; any change to it must keep those two properties. The 48dp floor
 * comes from the Material3 Checkbox's minimum interactive size.
 *
 * A muted calendar gets a bell-off mark after its name that stays visible when a long
 * name is cut, keeps its size at a large font scale, and adds "notifications off" for
 * TalkBack without becoming a control.
 *
 * The row is rendered in isolation, not in the drawer, so a caller that wrapped
 * the drawer in a provider disabling minimum-interactive enforcement would not
 * be caught.
 *
 * Runs under Robolectric; run in isolation given the repo's multi-class
 * native-crash flake.
 */
@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [34], qualifiers = "w360dp-h9999dp-mdpi")
class CalendarDrawerRowTest {

    @get:Rule
    val composeTestRule = createComposeRule()

    @Test
    fun `calendar row stays at least 48dp tall`() {
        composeTestRule.setContent {
            MaterialTheme {
                CalendarCheckboxRow(
                    name = "Work",
                    color = Color(0xFF3F51B5),
                    checked = true,
                    onClick = {},
                )
            }
        }
        // The clickable Row merges its descendants' semantics, so the "Work"
        // node's bounds are the whole row's bounds.
        composeTestRule.onNodeWithText("Work").assertHeightIsAtLeast(48.dp)
    }

    @Test
    fun `tapping the row toggles the calendar`() {
        var clicked = false
        composeTestRule.setContent {
            MaterialTheme {
                CalendarCheckboxRow(
                    name = "Personal",
                    color = Color(0xFF009688),
                    checked = false,
                    onClick = { clicked = true },
                )
            }
        }
        composeTestRule.onNodeWithText("Personal").performClick()
        assertTrue("Tapping the row body should fire onClick", clicked)
    }

    // ==================== Muted mark ====================

    private val longName = "Premier League fixtures and results for the whole 2026/27 season"

    private fun showRow(name: String, muted: Boolean, fontScale: Float = 1f, onClick: () -> Unit = {}) {
        composeTestRule.setContent {
            val density = LocalDensity.current
            CompositionLocalProvider(LocalDensity provides Density(density.density, fontScale)) {
                MaterialTheme {
                    Box(Modifier.width(300.dp)) {
                        CalendarCheckboxRow(
                            name = name,
                            color = Color(0xFF795548),
                            checked = true,
                            onClick = onClick,
                            muted = muted,
                        )
                    }
                }
            }
        }
    }

    private fun bell() = composeTestRule.onNodeWithTag(CALENDAR_ROW_MUTED_ICON_TAG, useUnmergedTree = true)

    @Test
    fun `only a muted row shows the bell-off mark`() {
        showRow("Work", muted = false)

        bell().assertDoesNotExist()
    }

    // Legacy Robolectric text metrics don't measure, so nothing would ever be cut.
    @GraphicsMode(GraphicsMode.Mode.NATIVE)
    @Test
    fun `a muted row with a long name keeps the bell and cuts the name`() {
        showRow(longName, muted = true)

        bell().assertIsDisplayed()
        val node = composeTestRule.onNodeWithText(longName, useUnmergedTree = true).fetchSemanticsNode()
        val layouts = mutableListOf<TextLayoutResult>()
        node.config[SemanticsActions.GetTextLayoutResult].action?.invoke(layouts)
        assertTrue("a long muted name is cut with an ellipsis", layouts.single().isLineEllipsized(0))
    }

    @GraphicsMode(GraphicsMode.Mode.NATIVE)
    @Test
    fun `the bell keeps its size at a large font scale`() {
        showRow(longName, muted = true, fontScale = 2f)

        bell().assertIsDisplayed().assertWidthIsEqualTo(18.dp)
    }

    @Test
    fun `a muted row says notifications off after the name, not instead of it`() {
        showRow("US Holidays", muted = true)

        composeTestRule.onNodeWithText("US Holidays")
            .assert(SemanticsMatcher.expectValue(SemanticsProperties.StateDescription, "notifications off"))
            .assert(SemanticsMatcher.keyNotDefined(SemanticsProperties.ContentDescription))
        composeTestRule.onNode(SemanticsMatcher.keyIsDefined(SemanticsProperties.ToggleableState))
            .assert(SemanticsMatcher.expectValue(SemanticsProperties.ToggleableState, ToggleableState.On))
    }

    @Test
    fun `an unmuted row says nothing about notifications`() {
        showRow("US Holidays", muted = false)

        composeTestRule.onNodeWithText("US Holidays")
            .assert(SemanticsMatcher.keyNotDefined(SemanticsProperties.StateDescription))
    }

    @Test
    fun `tapping a muted row still toggles the calendar once`() {
        var clicks = 0
        showRow("US Holidays", muted = true, onClick = { clicks++ })

        composeTestRule.onNodeWithText("US Holidays").performClick()

        assertEquals(1, clicks)
    }
}
