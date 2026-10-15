package org.onekash.kashcal.domain.planner

import org.onekash.kashcal.domain.model.DisplayEvent
import org.onekash.kashcal.domain.model.isStripItem
import java.time.ZoneId

/** Why an event is in the "Different this week" list; also its order within a day. */
enum class PlannerSummaryKind { STRIP, MOVED, CANCELLED }

/** One line of the "Different this week" list: an event and the days of the week it covers. */
data class PlannerSummaryItem(
    val event: DisplayEvent,
    val firstDayCode: Int,
    val lastDayCode: Int,
    val kind: PlannerSummaryKind,
)

/**
 * Returns what makes the week differ from a usual one, for the Planner's eighth box.
 *
 * For the week containing [todayDayCode] only today and later count; for any other week every
 * day does. Listed: strip items ([isStripItem]) that aren't changed occurrences, unless their
 * own RRULE repeats daily or weekly; changed occurrences that moved ([movedCue]); and cancelled
 * events. A changed all-day occurrence that kept its day (a note or RSVP edit) isn't listed.
 * Declined events never are. A multi-day event is one entry with its first and last day.
 * Entries are ordered by first day, then [PlannerSummaryKind], then start.
 */
fun differentThisWeek(
    weekDayCodes: List<Int>,
    eventsByDayCode: Map<Int, List<DisplayEvent>>,
    todayDayCode: Int,
    showMultiDayTimedInAllDayStrip: Boolean,
    roomSeriesAllDayById: Map<Long, Boolean>,
    zone: ZoneId = ZoneId.systemDefault(),
): List<PlannerSummaryItem> {
    val days = if (todayDayCode in weekDayCodes) weekDayCodes.filter { it >= todayDayCode } else weekDayCodes
    val byKey = LinkedHashMap<String, PlannerSummaryItem>()
    for (day in days) {
        for (event in eventsByDayCode[day].orEmpty()) {
            if (event.isDeclinedByMe) continue
            val seen = byKey[event.stableKey]
            if (seen != null) {
                byKey[event.stableKey] = seen.copy(lastDayCode = day)
                continue
            }
            val kind = summaryKind(event, weekDayCodes, showMultiDayTimedInAllDayStrip, roomSeriesAllDayById, zone)
                ?: continue
            byKey[event.stableKey] = PlannerSummaryItem(event, day, day, kind)
        }
    }
    return byKey.values.sortedWith(
        compareBy<PlannerSummaryItem> { it.firstDayCode }.thenBy { it.kind.ordinal }.thenBy { it.event.startTs }
    )
}

private fun summaryKind(
    event: DisplayEvent,
    weekDayCodes: List<Int>,
    showStrip: Boolean,
    roomSeriesAllDayById: Map<Long, Boolean>,
    zone: ZoneId,
): PlannerSummaryKind? {
    if (event.isCancelled) return PlannerSummaryKind.CANCELLED
    if (movedCue(event, roomSeriesAllDayById, weekDayCodes, zone) != null) return PlannerSummaryKind.MOVED
    if (event.isStripItem(showStrip) && !event.isChangedOccurrence() && !isDailyOrWeekly(event.ownRrule())) {
        return PlannerSummaryKind.STRIP
    }
    return null
}

/** The event row's own RRULE: a series' rule, null for a one-off or a changed occurrence. */
private fun DisplayEvent.ownRrule(): String? = when (this) {
    is DisplayEvent.Room -> event.rrule
    is DisplayEvent.Device -> rrule
}
