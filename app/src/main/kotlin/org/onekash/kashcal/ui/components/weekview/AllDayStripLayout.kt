package org.onekash.kashcal.ui.components.weekview

import org.onekash.kashcal.domain.model.DisplayEvent

/**
 * Layout model for the week/day all-day strip. Multi-day events (whether genuine
 * all-day events or timed events long enough to span more than one visible day)
 * are laid out as single spanning bars across the day columns they cover, instead
 * of being duplicated as independent chips in every day column — mirroring the
 * spanning-bar approach used by the full month grid ([org.onekash.kashcal.ui.screens.monthfull.MonthFullSpanLayout]).
 */

data class AllDaySpan(
    val displayEvent: DisplayEvent,
    val startCol: Int,
    val endCol: Int,
    /** True when the event actually started before the visible window (no left cap on the bar). */
    val leftFlush: Boolean,
    /** True when the event actually ends after the visible window (no right cap on the bar). */
    val rightFlush: Boolean,
)

internal data class AllDaySpanLayout(
    val lanes: List<List<AllDaySpan>>,
    val placedEventKeys: Set<String>,
)

sealed interface AllDaySlot {
    data object Empty : AllDaySlot
    data class BarSegment(val span: AllDaySpan) : AllDaySlot
    data class CellEvent(val event: DisplayEvent) : AllDaySlot
    data class Overflow(val count: Int, val columnEvents: List<DisplayEvent>) : AllDaySlot
}

data class AllDayStripRender(
    val slots: List<List<AllDaySlot>>, // [rowIndex][col]
)

/**
 * Packs multi-day events (startDay != endDay) that overlap [visibleDayCodes] into
 * non-overlapping lanes, greedily, up to [maxLanes]. Events beyond capacity are
 * left unplaced (their [AllDaySpanLayout.placedEventKeys] omits them) and fall
 * back to per-day cell treatment in [computeAllDayStripRender].
 */
internal fun computeAllDaySpans(
    visibleDayCodes: List<Int>,
    allDayEvents: List<DisplayEvent>,
    maxLanes: Int,
): AllDaySpanLayout {
    if (visibleDayCodes.isEmpty()) return AllDaySpanLayout(emptyList(), emptySet())
    val rangeStart = visibleDayCodes.first()
    val rangeEnd = visibleDayCodes.last()

    val seen = mutableMapOf<String, DisplayEvent>()
    for (e in allDayEvents) {
        if (e.startDay == e.endDay) continue
        if (e.endDay < rangeStart || e.startDay > rangeEnd) continue
        seen.putIfAbsent(e.stableKey, e)
    }

    val rawSpans = seen.values.map { e ->
        val leftFlush = e.startDay < rangeStart
        val rightFlush = e.endDay > rangeEnd
        val startCol = if (leftFlush) 0 else visibleDayCodes.indexOf(e.startDay)
        val endCol = if (rightFlush) visibleDayCodes.lastIndex else visibleDayCodes.indexOf(e.endDay)
        AllDaySpan(e, startCol, endCol, leftFlush, rightFlush)
    }

    val sortedForPlacement = rawSpans.sortedWith(
        compareBy({ it.startCol }, { -(it.endCol - it.startCol) })
    )

    val lanes = mutableListOf<MutableList<AllDaySpan>>()
    for (span in sortedForPlacement) {
        val laneIndex = lanes.indexOfFirst { lane -> lane.last().endCol < span.startCol }
        when {
            laneIndex >= 0 -> lanes[laneIndex].add(span)
            lanes.size < maxLanes -> lanes.add(mutableListOf(span))
            else -> { /* Beyond lane capacity — left unplaced, handled as per-day cell content. */ }
        }
    }

    val placedKeys = lanes.flatten().map { it.displayEvent.stableKey }.toSet()
    return AllDaySpanLayout(lanes = lanes, placedEventKeys = placedKeys)
}

/**
 * Builds the full [rowIndex][col] render grid for the all-day strip: multi-day
 * spans occupy their lane's row across every column they cover, and each column's
 * remaining rows are filled with that day's single-day events, with any
 * remainder collapsed into an "+N" [AllDaySlot.Overflow] badge.
 */
fun computeAllDayStripRender(
    visibleDayCodes: List<Int>,
    allDayEvents: List<DisplayEvent>,
    maxRows: Int,
): AllDayStripRender {
    val numCols = visibleDayCodes.size
    if (numCols == 0 || maxRows == 0) return AllDayStripRender(emptyList())

    val layout = computeAllDaySpans(visibleDayCodes, allDayEvents, maxRows)
    val grid: Array<Array<AllDaySlot>> = Array(maxRows) { Array(numCols) { AllDaySlot.Empty } }

    for ((laneIndex, lane) in layout.lanes.withIndex()) {
        for (span in lane) {
            for (col in span.startCol..span.endCol) {
                grid[laneIndex][col] = AllDaySlot.BarSegment(span)
            }
        }
    }

    for (col in 0 until numCols) {
        val dayCode = visibleDayCodes[col]
        val allEventsForDay = allDayEvents
            .filter { it.startDay <= dayCode && it.endDay >= dayCode }
            .sortedBy { it.startTs }
        val columnEvents = allEventsForDay.filter { it.stableKey !in layout.placedEventKeys }

        val freeSlots = (0 until maxRows).filter { grid[it][col] === AllDaySlot.Empty }
        if (columnEvents.size <= freeSlots.size) {
            for ((i, event) in columnEvents.withIndex()) {
                grid[freeSlots[i]][col] = AllDaySlot.CellEvent(event)
            }
        } else if (freeSlots.isNotEmpty()) {
            val visibleCount = (freeSlots.size - 1).coerceAtLeast(0)
            for (i in 0 until visibleCount) {
                grid[freeSlots[i]][col] = AllDaySlot.CellEvent(columnEvents[i])
            }
            val overflowCount = columnEvents.size - visibleCount
            grid[freeSlots.last()][col] = AllDaySlot.Overflow(overflowCount, allEventsForDay)
        }
        // If there are no free slots at all, the column's cell events are silently
        // dropped from this row's grid (lanes-win policy) — unreachable in practice
        // since maxRows >= 1 and a column with only bars filling all rows has no
        // events left to place.
    }

    return AllDayStripRender(slots = grid.map { it.toList() })
}
