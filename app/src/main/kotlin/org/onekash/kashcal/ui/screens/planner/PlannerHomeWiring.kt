package org.onekash.kashcal.ui.screens.planner

import kotlinx.collections.immutable.ImmutableList
import kotlinx.collections.immutable.persistentListOf
import org.onekash.kashcal.domain.model.DisplayEvent
import org.onekash.kashcal.domain.planner.PlannerWeekState
import org.onekash.kashcal.ui.viewmodels.ViewMode

/**
 * Returns the events the day sheet lists for [dayCode]: the Planner's while the Planner shows,
 * the full-height month grid's otherwise. Each view's query runs only while that view shows,
 * so reading the other one would list nothing.
 */
fun dayDetailEvents(
    viewMode: ViewMode,
    monthEvents: Map<Int, ImmutableList<DisplayEvent>>,
    plannerState: PlannerWeekState?,
    dayCode: Int,
): ImmutableList<DisplayEvent> {
    val events = if (viewMode == ViewMode.PLANNER) plannerState?.eventsByDayCode?.get(dayCode) else monthEvents[dayCode]
    return events ?: persistentListOf()
}
