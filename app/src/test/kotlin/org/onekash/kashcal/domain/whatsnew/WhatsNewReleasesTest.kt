package org.onekash.kashcal.domain.whatsnew

import androidx.test.core.app.ApplicationProvider
import android.content.Context
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.onekash.kashcal.R
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Tests [ALL_RELEASE_NOTES] as [WhatsNewGate] serves it: which notes an upgrade shows, in what
 * order, and that every string they name exists.
 *
 * The three notes for versionCode 772 announce the Today's date icon, Recent changes and Change
 * notifications as consecutive pages. Their "Find it in" labels are the menu's own strings, so the
 * expected ids here are the ones the hub and Settings render.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "en-rUS")
class WhatsNewReleasesTest {

    private val context: Context = ApplicationProvider.getApplicationContext()

    @Test
    fun `upgrading from 771 shows the three feature notes in order`() {
        val out = WhatsNewGate.releasesToShow(ALL_RELEASE_NOTES, lastShownVersion = 771, currentVersion = 772)

        assertEquals(
            listOf(WhatsNewHero.DATE_ICON, WhatsNewHero.RECENT_CHANGES, WhatsNewHero.CHANGE_NOTIFICATIONS),
            out.map { it.hero },
        )
        assertEquals(listOf(772, 772, 772), out.map { it.versionCode })
    }

    @Test
    fun `feature notes name the menu path to their option`() {
        val out = WhatsNewGate.releasesToShow(ALL_RELEASE_NOTES, lastShownVersion = 771, currentVersion = 772)

        assertEquals(
            listOf(
                listOf(R.string.hub_section_make_it_yours, R.string.settings_app_icon),
                listOf(R.string.hub_section_own_your_calendar, R.string.recent_changes_title),
                listOf(
                    R.string.hub_accounts_and_settings,
                    R.string.settings_section_sync,
                    R.string.settings_change_notifications,
                ),
            ),
            out.map { it.findInRes },
        )
    }

    @Test
    fun `change notifications page is titled with the setting's own name`() {
        val note = ALL_RELEASE_NOTES.single { it.hero == WhatsNewHero.CHANGE_NOTIFICATIONS }
        assertEquals(R.string.settings_change_notifications, note.titleRes)
    }

    @Test
    fun `upgrading from before 598 shows the older notes first`() {
        val out = WhatsNewGate.releasesToShow(ALL_RELEASE_NOTES, lastShownVersion = 597, currentVersion = 772)

        assertEquals(listOf(598, 732, 772, 772, 772), out.map { it.versionCode })
        assertEquals(listOf(null, null), out.take(2).map { it.hero })
        assertTrue(out.take(2).all { it.findInRes.isEmpty() })
    }

    @Test
    fun `every string a note names resolves to text`() {
        ALL_RELEASE_NOTES.forEach { note ->
            val ids = (
                listOf(note.titleRes, note.bodyRes, note.captionRes, note.actionLabelRes, note.actionUrlRes) +
                    note.findInRes
                ).filter { it != 0 }
            ids.forEach { id ->
                assertTrue(
                    "note ${note.versionCode} names blank string $id",
                    context.getString(id).isNotBlank(),
                )
            }
        }
    }
}
