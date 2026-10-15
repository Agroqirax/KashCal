package org.onekash.kashcal.domain.planner

import androidx.compose.runtime.Immutable
import kotlinx.collections.immutable.ImmutableList
import kotlinx.collections.immutable.ImmutableMap
import kotlinx.collections.immutable.persistentMapOf
import org.onekash.kashcal.domain.model.DisplayEvent

/**
 * The Planner's loaded events: the shown week and its neighbours, so a page swiped into view
 * already has its days.
 *
 * @property eventsByDayCode events per day code, a multi-day event in each day it covers.
 * @property rangeStartDayCode first day code the query covered.
 * @property rangeEndDayCode last day code the query covered.
 * @property roomSeriesAllDayById the all-day flag of each Room series that has a changed
 *   occurrence in range, keyed by the series row id ([movedCue] reads it).
 * @property showMultiDayTimedInAllDayStrip the multi-day strip setting the state was built with.
 */
@Immutable
data class PlannerWeekState(
    val eventsByDayCode: ImmutableMap<Int, ImmutableList<DisplayEvent>>,
    val rangeStartDayCode: Int,
    val rangeEndDayCode: Int,
    val roomSeriesAllDayById: ImmutableMap<Long, Boolean> = persistentMapOf(),
    val showMultiDayTimedInAllDayStrip: Boolean = false,
) {
    /** Returns true when [dayCode] is in the loaded range, so an empty day there has no events. */
    fun covers(dayCode: Int): Boolean = dayCode in rangeStartDayCode..rangeEndDayCode
}
