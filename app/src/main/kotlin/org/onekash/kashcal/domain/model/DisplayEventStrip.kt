package org.onekash.kashcal.domain.model

/**
 * Minimum duration for a day-crossing timed event to count as a multi-day strip item.
 * `endDay > startDay` alone also holds for an ordinary event that spills past midnight (11pm to
 * 12:30am); without this floor every such event would leave the timed rows and show as a bar
 * across two days.
 */
private const val MIN_MULTIDAY_STRIP_DURATION_MS = 20L * 60 * 60 * 1000

/** Returns true for an event that crosses midnight and lasts at least the strip floor. */
private fun DisplayEvent.isLongMultiDay(): Boolean =
    endDay > startDay && (endTs - startTs) >= MIN_MULTIDAY_STRIP_DURATION_MS

/**
 * Returns true when this event renders as an all-day strip item rather than a timed row: every
 * all-day event, and a long multi-day timed event when [showMultiDayTimedInAllDayStrip] is on.
 * The week grids and the Planner both decide with this, so they agree on which events go where.
 */
fun DisplayEvent.isStripItem(showMultiDayTimedInAllDayStrip: Boolean): Boolean =
    isAllDay || (showMultiDayTimedInAllDayStrip && isLongMultiDay())
