package org.onekash.kashcal.ui.util

import org.onekash.kashcal.ui.components.weekview.WeekViewUtils
import java.time.LocalDate

/**
 * Returns the first day of the Planner page [page]'s week. Pages count weeks from the week
 * starting on [anchorDayCode] (page [WeekViewUtils.CENTER_WEEK_PAGE]), not from today, so the
 * ViewModel's query and the pager's pages keep meaning the same week when the app stays open
 * across a week boundary.
 */
fun plannerWeekStart(anchorDayCode: Int, page: Int, firstDayOfWeek: Int): LocalDate =
    WeekViewUtils.weekPageToStartDate(page, firstDayOfWeek, referenceDate = DayPagerUtils.dayCodeToLocalDate(anchorDayCode))

/** Returns the Planner page whose week holds [date], counted from [anchorDayCode]. */
fun plannerPageForDate(anchorDayCode: Int, date: LocalDate, firstDayOfWeek: Int): Int =
    WeekViewUtils.dateToWeekPage(date, firstDayOfWeek, referenceDate = DayPagerUtils.dayCodeToLocalDate(anchorDayCode))

/**
 * Returns the first day of the week the Planner shows on [page], counted from [anchorDayCode],
 * or from the week holding [today] before the Planner has an anchor.
 */
fun plannerShownWeekStart(
    anchorDayCode: Int?,
    page: Int,
    firstDayOfWeek: Int,
    today: LocalDate = LocalDate.now(),
): LocalDate {
    val anchor = anchorDayCode ?: DayPagerUtils.localDateToDayCode(WeekViewUtils.getWeekStart(today, firstDayOfWeek))
    return plannerWeekStart(anchor, page, firstDayOfWeek)
}
