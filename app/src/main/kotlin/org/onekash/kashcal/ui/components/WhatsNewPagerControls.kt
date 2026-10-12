package org.onekash.kashcal.ui.components

/**
 * Which controls the What's New pager shows on one page (WhatsNewPagerControlsTest).
 *
 * Skip shows on every page but the last, where Done does the same; Back from the second page on;
 * the dots only when there is more than one page.
 */
internal data class WhatsNewPagerControls(
    val showSkip: Boolean,
    val showBack: Boolean,
    val showDots: Boolean,
    val isLast: Boolean,
) {
    companion object {
        /** Returns the controls for [page] (0-based) of [count] pages. */
        fun at(page: Int, count: Int): WhatsNewPagerControls {
            val isLast = page >= count - 1
            return WhatsNewPagerControls(
                showSkip = !isLast,
                showBack = page > 0,
                showDots = count > 1,
                isLast = isLast,
            )
        }
    }
}

/** What system back does in the What's New pager. */
internal enum class WhatsNewBackAction { PREVIOUS_PAGE, DISMISS }

/** Returns what system back does on [page]: page back, or close the sheet from the first page. */
internal fun whatsNewBackAction(page: Int): WhatsNewBackAction =
    if (page > 0) WhatsNewBackAction.PREVIOUS_PAGE else WhatsNewBackAction.DISMISS
