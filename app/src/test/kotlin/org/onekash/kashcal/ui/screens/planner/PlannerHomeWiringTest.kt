package org.onekash.kashcal.ui.screens.planner

import kotlinx.collections.immutable.persistentListOf
import kotlinx.collections.immutable.persistentMapOf
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.onekash.kashcal.domain.model.DisplayEvent
import org.onekash.kashcal.domain.planner.PlannerTestEvents
import org.onekash.kashcal.domain.planner.PlannerWeekState
import org.onekash.kashcal.ui.components.weekview.WeekViewUtils
import org.onekash.kashcal.ui.util.plannerShownWeekStart
import org.onekash.kashcal.ui.viewmodels.ViewMode
import java.time.LocalDate
import java.time.ZoneId
import java.util.Calendar

/** Tests the Planner's wiring into the home screen: the day sheet's source and the shown week. */
class PlannerHomeWiringTest {

    private val zone = ZoneId.systemDefault()
    private val day = LocalDate.of(2026, 10, 15)
    private val dayCode = 20261015
    private val roomEvent = PlannerTestEvents.room(
        id = 1, startTs = PlannerTestEvents.at(day, 16, zone = zone), endTs = PlannerTestEvents.at(day, 17, zone = zone), zone = zone,
    )
    private val deviceEvent = PlannerTestEvents.device(
        instanceId = 2, eventId = 20, startTs = PlannerTestEvents.at(day, 9, zone = zone), endTs = PlannerTestEvents.at(day, 10, zone = zone), zone = zone,
    )

    private val plannerState = PlannerWeekState(
        eventsByDayCode = persistentMapOf(dayCode to persistentListOf<DisplayEvent>(roomEvent)),
        rangeStartDayCode = 20261005,
        rangeEndDayCode = 20261025,
    )
    private val monthEvents = persistentMapOf(dayCode to persistentListOf<DisplayEvent>(deviceEvent))

    @Test
    fun `the day sheet lists the Planner's events in the Planner`() {
        assertEquals(listOf<DisplayEvent>(roomEvent), dayDetailEvents(ViewMode.PLANNER, monthEvents, plannerState, dayCode))
    }

    @Test
    fun `the day sheet keeps listing the month grid's events in the month view`() {
        assertEquals(listOf<DisplayEvent>(deviceEvent), dayDetailEvents(ViewMode.MONTH_FULL, monthEvents, plannerState, dayCode))
    }

    @Test
    fun `a day nothing loaded for lists nothing`() {
        assertTrue(dayDetailEvents(ViewMode.PLANNER, monthEvents, plannerState, 20261016).isEmpty())
        assertTrue(dayDetailEvents(ViewMode.PLANNER, monthEvents, null, dayCode).isEmpty())
    }

    @Test
    fun `the shown week counts pages from the anchor`() {
        // What Back after a day-header tap or Jump to date restores, and what the title shows.
        assertEquals(
            LocalDate.of(2026, 10, 26),
            plannerShownWeekStart(20261012, WeekViewUtils.CENTER_WEEK_PAGE + 2, Calendar.MONDAY),
        )
    }

    @Test
    fun `without an anchor the shown week is this week`() {
        assertEquals(
            LocalDate.of(2026, 10, 11),
            plannerShownWeekStart(null, WeekViewUtils.CENTER_WEEK_PAGE, Calendar.SUNDAY, today = day),
        )
    }
}
