package org.onekash.kashcal.ui.components

import org.junit.Assert.assertEquals
import org.junit.Test
import org.onekash.kashcal.ui.viewmodels.ViewMode

/**
 * Tests [TopBarTitleAction.forViewMode], what tapping the top-bar title does per view: AGENDA
 * and DAY toggle their inline week bar, the other time-grid views (THREE_DAYS, WEEK) and the
 * Planner open the modal date picker, and MONTH and MONTH_FULL take the month-header action,
 * which toggles the year overlay (YEAR and INSIGHTS take it too, not asserted here). A silent
 * regression would route DAY to the date picker in place of its toggle, or the Planner to the
 * year overlay.
 */
class TopBarTitleActionTest {

    @Test
    fun `AGENDA toggles the agenda week bar`() {
        assertEquals(TopBarTitleAction.TOGGLE_AGENDA_WEEK_BAR, TopBarTitleAction.forViewMode(ViewMode.AGENDA))
    }

    @Test
    fun `DAY toggles the day week bar`() {
        assertEquals(TopBarTitleAction.TOGGLE_DAY_WEEK_BAR, TopBarTitleAction.forViewMode(ViewMode.DAY))
    }

    @Test
    fun `THREE_DAYS opens the date picker`() {
        assertEquals(TopBarTitleAction.OPEN_DATE_PICKER, TopBarTitleAction.forViewMode(ViewMode.THREE_DAYS))
    }

    @Test
    fun `WEEK opens the date picker`() {
        assertEquals(TopBarTitleAction.OPEN_DATE_PICKER, TopBarTitleAction.forViewMode(ViewMode.WEEK))
    }

    @Test
    fun `PLANNER opens the date picker`() {
        // The Planner is not a time grid, so without its own branch it would take the
        // month-header action.
        assertEquals(TopBarTitleAction.OPEN_DATE_PICKER, TopBarTitleAction.forViewMode(ViewMode.PLANNER))
    }

    @Test
    fun `DAY does not open the date picker (regression guard)`() {
        // DAY is a time-grid view, so without its own branch it would fall through to
        // OPEN_DATE_PICKER.
        val action = TopBarTitleAction.forViewMode(ViewMode.DAY)
        assertEquals(TopBarTitleAction.TOGGLE_DAY_WEEK_BAR, action)
    }

    @Test
    fun `month-family views jump to the month header`() {
        assertEquals(TopBarTitleAction.MONTH_HEADER, TopBarTitleAction.forViewMode(ViewMode.MONTH))
        assertEquals(TopBarTitleAction.MONTH_HEADER, TopBarTitleAction.forViewMode(ViewMode.MONTH_FULL))
    }
}
