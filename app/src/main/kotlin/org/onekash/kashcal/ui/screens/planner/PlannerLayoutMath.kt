package org.onekash.kashcal.ui.screens.planner

import org.onekash.kashcal.ui.util.DayPagerUtils
import java.time.Instant
import java.time.ZoneId
import java.time.ZonedDateTime

/** Columns and rows of the Planner's eight boxes. */
data class PlannerGridShape(val columns: Int, val rows: Int)

/**
 * Returns the Planner's grid: 2 x 4 on a portrait phone, 4 x 2 when the window is short
 * ([isCompactHeight], the home screen's landscape-phone check) or at least 600dp wide.
 */
fun plannerGridShape(widthDp: Int, isCompactHeight: Boolean): PlannerGridShape =
    if (isCompactHeight || widthDp >= 600) PlannerGridShape(4, 2) else PlannerGridShape(2, 4)

/**
 * Returns how many leading rows of [itemHeightsPx] fit whole in [availablePx]. No line is held
 * back for the "+N" count, which sits in the corner beside the last row. [itemHeightsPx] may stop
 * at the first row that doesn't fit; later rows can't change the answer.
 */
fun fitCount(itemHeightsPx: List<Int>, availablePx: Int): Int {
    var used = 0
    var count = 0
    for (height in itemHeightsPx) {
        if (used + height > availablePx) break
        used += height
        count++
    }
    return count
}

/**
 * Returns [dayCode]'s day at the current local time of day, the timestamp the month view's day
 * cells hand to the day sheet and the event form, so a new event starts on that day at the time
 * it is now.
 */
fun plannerDayMs(dayCode: Int, nowMs: Long, zone: ZoneId = ZoneId.systemDefault()): Long {
    val date = DayPagerUtils.dayCodeToLocalDate(dayCode)
    val time = Instant.ofEpochMilli(nowMs).atZone(zone).toLocalTime()
    return ZonedDateTime.of(date, time, zone).toInstant().toEpochMilli()
}
