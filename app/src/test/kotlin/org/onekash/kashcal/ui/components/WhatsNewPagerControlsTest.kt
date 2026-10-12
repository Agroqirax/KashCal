package org.onekash.kashcal.ui.components

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Tests [WhatsNewPagerControls.at] and [whatsNewBackAction], the What's New pager's button rules.
 *
 * Skip shows on every page but the last, where Done ends the sheet. Back shows from page 2 on.
 * One page shows only Done. System back pages back, and dismisses from page 1.
 */
class WhatsNewPagerControlsTest {

    @Test
    fun `single page shows only Done`() {
        assertEquals(
            WhatsNewPagerControls(showSkip = false, showBack = false, showDots = false, isLast = true),
            WhatsNewPagerControls.at(page = 0, count = 1),
        )
    }

    @Test
    fun `first of three shows Skip and Next, no Back`() {
        assertEquals(
            WhatsNewPagerControls(showSkip = true, showBack = false, showDots = true, isLast = false),
            WhatsNewPagerControls.at(page = 0, count = 3),
        )
    }

    @Test
    fun `middle page shows Skip, Back and Next`() {
        assertEquals(
            WhatsNewPagerControls(showSkip = true, showBack = true, showDots = true, isLast = false),
            WhatsNewPagerControls.at(page = 1, count = 3),
        )
    }

    @Test
    fun `last of three shows Back and Done, no Skip`() {
        assertEquals(
            WhatsNewPagerControls(showSkip = false, showBack = true, showDots = true, isLast = true),
            WhatsNewPagerControls.at(page = 2, count = 3),
        )
    }

    @Test
    fun `five pages follow the same rules at both ends`() {
        assertEquals(
            WhatsNewPagerControls(showSkip = true, showBack = false, showDots = true, isLast = false),
            WhatsNewPagerControls.at(page = 0, count = 5),
        )
        assertEquals(
            WhatsNewPagerControls(showSkip = false, showBack = true, showDots = true, isLast = true),
            WhatsNewPagerControls.at(page = 4, count = 5),
        )
    }

    @Test
    fun `system back pages back after the first page and dismisses on it`() {
        assertEquals(WhatsNewBackAction.DISMISS, whatsNewBackAction(page = 0))
        assertEquals(WhatsNewBackAction.PREVIOUS_PAGE, whatsNewBackAction(page = 1))
        assertEquals(WhatsNewBackAction.PREVIOUS_PAGE, whatsNewBackAction(page = 4))
    }
}
