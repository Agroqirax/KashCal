package org.onekash.kashcal.ui.components

import android.content.Context
import android.provider.Settings
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.onekash.kashcal.R
import org.onekash.kashcal.domain.whatsnew.WhatsNewHero
import org.onekash.kashcal.sync.model.ChangeType
import org.onekash.kashcal.sync.notification.buildChangeNotificationContent
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId

/**
 * Tests the What's New illustrations ([WhatsNewHeroArt]): the dates the icon turns over between,
 * the frame each starts on with and without motion ([initialHeroPhase], [heroMotionEnabled]), that
 * their sample text is worded by the same code as the real Recent changes row and notification,
 * and that TalkBack reads none of it.
 *
 * Runs under Robolectric in en-US with a 12-hour pattern, with native graphics so text has its
 * real size (the fit-at-large-text tests measure it); run the class alone given the repo's
 * multi-class native-crash flake.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "en-rUS-w360dp-h9999dp-mdpi")
class WhatsNewHeroesTest {

    @get:Rule
    val composeTestRule = createComposeRule()

    private val context: Context = ApplicationProvider.getApplicationContext()
    private val zone = ZoneId.systemDefault()
    private val now = LocalDateTime.of(2026, 10, 11, 9, 41).atZone(zone).toInstant().toEpochMilli()

    private fun render(hero: WhatsNewHero, playing: Boolean, motionEnabled: Boolean, fontScale: Float = 1f) {
        composeTestRule.setContent {
            val density = LocalDensity.current
            CompositionLocalProvider(LocalDensity provides Density(density.density, fontScale)) {
                MaterialTheme {
                    // The size of the frame the banner draws an illustration in.
                    Box(modifier = Modifier.size(312.dp, 188.dp).testTag(FRAME)) {
                        WhatsNewHeroArt(
                            hero = hero,
                            timePattern = "h:mm a",
                            now = now,
                            playing = playing,
                            motionEnabled = motionEnabled,
                        )
                    }
                }
            }
        }
        composeTestRule.waitForIdle()
    }

    private fun bounds(text: String) =
        composeTestRule.onNodeWithText(text, useUnmergedTree = true).fetchSemanticsNode().boundsInRoot

    private fun exists(text: String) =
        composeTestRule.onNodeWithText(text, useUnmergedTree = true).assertExists()

    @Test
    fun `icon turns over from yesterday's day of the month to today's`() {
        assertEquals(10 to 11, heroDays(LocalDate.of(2026, 10, 11)))
        assertEquals(31 to 1, heroDays(LocalDate.of(2026, 11, 1)))
        assertEquals(29 to 1, heroDays(LocalDate.of(2028, 3, 1)))
        assertEquals(28 to 1, heroDays(LocalDate.of(2027, 3, 1)))
    }

    @Test
    fun `without motion an illustration starts on its end frame`() {
        assertEquals(HeroPhase.END, initialHeroPhase(motionEnabled = false))
        assertEquals(HeroPhase.START, initialHeroPhase(motionEnabled = true))
    }

    @Test
    fun `motion follows the system animator duration scale`() {
        val resolver = context.contentResolver
        assertTrue(heroMotionEnabled(resolver))
        Settings.Global.putFloat(resolver, Settings.Global.ANIMATOR_DURATION_SCALE, 0f)
        assertFalse(heroMotionEnabled(resolver))
        Settings.Global.putFloat(resolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f)
        assertTrue(heroMotionEnabled(resolver))
    }

    @Test
    fun `date icon without motion shows today at midnight`() {
        render(WhatsNewHero.DATE_ICON, playing = true, motionEnabled = false)
        exists("12:00 AM")
        composeTestRule.onNodeWithText("11:59 PM", useUnmergedTree = true).assertDoesNotExist()
    }

    @Test
    fun `date icon off screen waits on yesterday at 11 59`() {
        render(WhatsNewHero.DATE_ICON, playing = false, motionEnabled = true)
        exists("11:59 PM")
        composeTestRule.onNodeWithText("12:00 AM", useUnmergedTree = true).assertDoesNotExist()
    }

    @Test
    fun `recent changes card words its rows like the sheet`() {
        render(WhatsNewHero.RECENT_CHANGES, playing = true, motionEnabled = false)
        exists(context.getString(R.string.whats_new_hero_sample_lunch))
        exists(context.getString(R.string.whats_new_hero_sample_review))
        exists(context.getString(R.string.whats_new_hero_sample_book_club))
        exists("Moved from 3:00 PM")
        exists(context.getString(R.string.label_today))
    }

    @Test
    fun `notification reads as the real change notification for the moved row`() {
        render(WhatsNewHero.CHANGE_NOTIFICATIONS, playing = true, motionEnabled = false)
        val moved = whatsNewSampleRows(context.resources, now, zone).single { it.changeType == ChangeType.MODIFIED }
        val content = buildChangeNotificationContent(
            listOf(moved),
            context.resources,
            RecentChangeFormatter(context.resources, "h:mm a"),
            now,
        )
        exists(content.title)
        exists(content.text)
    }

    @Test
    fun `TalkBack reads none of the sample text`() {
        render(WhatsNewHero.RECENT_CHANGES, playing = true, motionEnabled = false)
        composeTestRule.onNodeWithText(context.getString(R.string.whats_new_hero_sample_lunch)).assertDoesNotExist()
        composeTestRule.onNodeWithText("Moved from 3:00 PM").assertDoesNotExist()
    }

    @Test
    fun `recent changes card fits its frame at large text`() {
        render(WhatsNewHero.RECENT_CHANGES, playing = true, motionEnabled = false, fontScale = 2f)
        val frame = composeTestRule.onNodeWithTag(FRAME).fetchSemanticsNode().boundsInRoot
        val header = bounds(context.getString(R.string.label_today))
        val lastRow = bounds(context.getString(R.string.whats_new_hero_sample_book_club))
        assertTrue("header cut off: $header in $frame", header.top >= frame.top)
        assertTrue("last row cut off: $lastRow in $frame", lastRow.bottom <= frame.bottom)
    }

    @Test
    fun `notification card stays clear of the clock at large text`() {
        render(WhatsNewHero.CHANGE_NOTIFICATIONS, playing = true, motionEnabled = false, fontScale = 2f)
        val moved = whatsNewSampleRows(context.resources, now, zone).single { it.changeType == ChangeType.MODIFIED }
        val content = buildChangeNotificationContent(
            listOf(moved), context.resources, RecentChangeFormatter(context.resources, "h:mm a"), now,
        )
        val clock = java.time.Instant.ofEpochMilli(now).atZone(zone).toLocalTime()
            .format(java.time.format.DateTimeFormatter.ofPattern("h:mm a", java.util.Locale.US))
        assertTrue(bounds(content.text).bottom <= bounds(clock).top)
    }

    private companion object {
        const val FRAME = "frame"
    }
}
