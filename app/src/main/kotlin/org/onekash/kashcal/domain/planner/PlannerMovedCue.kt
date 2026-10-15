package org.onekash.kashcal.domain.planner

import org.onekash.kashcal.domain.model.DisplayEvent
import org.onekash.kashcal.util.DateTimeUtils
import java.time.ZoneId

/** Where a changed occurrence of a series used to be, for the Planner's "was" cue. */
sealed interface MovedCue {
    /** Moved within its day; [originalMs] is the old start. */
    data class Time(val originalMs: Long) : MovedCue

    /** Moved from another day of the shown week. */
    data class Day(val originalDayCode: Int) : MovedCue

    /** Moved from a day outside the shown week. */
    data class Date(val originalDayCode: Int) : MovedCue
}

/** Returns true for a changed occurrence of a series (an exception row). */
fun DisplayEvent.isChangedOccurrence(): Boolean = when (this) {
    is DisplayEvent.Room -> event.isException || occurrence.exceptionEventId != null
    is DisplayEvent.Device -> instance.originalId != null || instance.originalInstanceTime != null
}

/**
 * Returns where a changed occurrence used to be, or null when it didn't move or the old start
 * can't be read safely.
 *
 * The original start (RECURRENCE-ID) is stored in the series' value type: UTC midnight of the
 * day for an all-day series, the instant otherwise. The series' all-day flag comes from
 * [roomSeriesAllDayById] for a Room event (keyed by the series row, `Occurrence.eventId`) and
 * from `ORIGINAL_ALL_DAY` for a device event; the two id spaces are separate, so the Room map
 * is never read for a device event. When the flag is unknown it is inferred from the original
 * start, and the cue is null where that would guess: a timed occurrence whose original is
 * exactly UTC midnight (a 9:00 series in Tokyo looks the same as an all-day one), and an all-day
 * occurrence whose original isn't UTC midnight.
 *
 * @param weekDayCodes the shown week's day codes; a move from one of them is a [MovedCue.Day],
 *   from any other day a [MovedCue.Date].
 */
fun movedCue(
    event: DisplayEvent,
    roomSeriesAllDayById: Map<Long, Boolean>,
    weekDayCodes: List<Int>,
    zone: ZoneId = ZoneId.systemDefault(),
): MovedCue? {
    if (!event.isChangedOccurrence()) return null
    val (original, start, knownSeriesAllDay) = when (event) {
        is DisplayEvent.Room -> Triple(
            event.event.originalInstanceTime ?: return null,
            event.occurrence.startTs,
            roomSeriesAllDayById[event.occurrence.eventId],
        )
        is DisplayEvent.Device -> Triple(
            event.instance.originalInstanceTime ?: return null,
            event.instance.startTs,
            event.instance.originalAllDay,
        )
    }
    if (original == start) return null

    val originalAtUtcMidnight = original == DateTimeUtils.normalizeToUtcMidnight(original)
    val seriesAllDay = knownSeriesAllDay ?: when {
        event.isAllDay && originalAtUtcMidnight -> true
        !event.isAllDay && !originalAtUtcMidnight -> false
        else -> return null
    }

    val originalDayCode = DateTimeUtils.eventTsToDayCode(original, seriesAllDay, zone)
    val startDayCode = event.startDay
    return when {
        originalDayCode == startDayCode -> if (seriesAllDay) null else MovedCue.Time(original)
        originalDayCode in weekDayCodes -> MovedCue.Day(originalDayCode)
        else -> MovedCue.Date(originalDayCode)
    }
}
