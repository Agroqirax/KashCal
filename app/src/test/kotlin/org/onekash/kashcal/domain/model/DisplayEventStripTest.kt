package org.onekash.kashcal.domain.model

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.onekash.kashcal.domain.planner.PlannerTestEvents
import java.time.LocalDate
import java.time.ZoneId

/** Tests [isStripItem]: which events render as an all-day strip item rather than a timed row. */
class DisplayEventStripTest {

    private val zone = ZoneId.of("America/New_York")
    private val day = LocalDate.of(2026, 10, 16)

    private fun timed(startHour: Int, durationMs: Long) = PlannerTestEvents.room(
        id = 1,
        startTs = PlannerTestEvents.at(day, startHour, zone = zone),
        endTs = PlannerTestEvents.at(day, startHour, zone = zone) + durationMs,
        zone = zone,
    )

    @Test
    fun `an all-day event is a strip item with the setting on or off`() {
        val allDay = PlannerTestEvents.roomAllDay(1, day, zone)
        assertTrue(allDay.isStripItem(showMultiDayTimedInAllDayStrip = false))
        assertTrue(allDay.isStripItem(showMultiDayTimedInAllDayStrip = true))
    }

    @Test
    fun `a two-day timed event is a strip item only with the setting on`() {
        val trip = timed(startHour = 18, durationMs = 26 * PlannerTestEvents.HOUR)
        assertTrue(trip.isStripItem(showMultiDayTimedInAllDayStrip = true))
        assertFalse(trip.isStripItem(showMultiDayTimedInAllDayStrip = false))
    }

    @Test
    fun `a timed event that spills past midnight stays a row`() {
        val late = timed(startHour = 23, durationMs = 2 * PlannerTestEvents.HOUR)
        assertFalse(late.isStripItem(showMultiDayTimedInAllDayStrip = true))
    }

    @Test
    fun `the twenty hour floor is inclusive`() {
        val atFloor = timed(startHour = 12, durationMs = 20 * PlannerTestEvents.HOUR)
        val belowFloor = timed(startHour = 12, durationMs = 20 * PlannerTestEvents.HOUR - 1)
        assertTrue(atFloor.isStripItem(showMultiDayTimedInAllDayStrip = true))
        assertFalse(belowFloor.isStripItem(showMultiDayTimedInAllDayStrip = true))
    }
}
