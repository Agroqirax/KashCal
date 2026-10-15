package org.onekash.kashcal.domain.planner

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.onekash.kashcal.domain.model.DisplayEvent
import org.onekash.kashcal.domain.planner.PlannerTestEvents.at
import org.onekash.kashcal.domain.planner.PlannerTestEvents.dayCode
import org.onekash.kashcal.domain.planner.PlannerTestEvents.utcMidnight
import java.time.LocalDate
import java.time.ZoneId

/** Tests [differentThisWeek], the "Different this week" box's list. */
class PlannerWeekSummaryTest {

    private val ny = ZoneId.of("America/New_York")
    private val mon = LocalDate.of(2026, 10, 12)
    private val week = (0..6).map { dayCode(mon.plusDays(it.toLong())) }
    private val tue = mon.plusDays(1)
    private val wed = mon.plusDays(2)
    private val thu = mon.plusDays(3) // today
    private val fri = mon.plusDays(4)
    private val sat = mon.plusDays(5)
    private val today = dayCode(thu)

    private fun summary(
        vararg events: DisplayEvent,
        todayDayCode: Int = today,
        show: Boolean = false,
        roomSeries: Map<Long, Boolean> = emptyMap(),
    ) = differentThisWeek(week, PlannerTestEvents.bucketByDay(week, events.toList()), todayDayCode, show, roomSeries, ny)

    private fun titles(items: List<PlannerSummaryItem>) = items.map { it.event.title }

    @Test
    fun `the current week starts at today`() {
        val tuesday = PlannerTestEvents.roomAllDay(1, tue, ny).withTitle("No school Tue")
        val friday = PlannerTestEvents.roomAllDay(2, fri, ny).withTitle("No school Fri")
        assertEquals(listOf("No school Fri"), titles(summary(tuesday, friday)))
    }

    @Test
    fun `another week uses all seven days`() {
        val tuesday = PlannerTestEvents.roomAllDay(1, tue, ny).withTitle("No school Tue")
        val result = summary(tuesday, todayDayCode = dayCode(mon.plusDays(14)))
        assertEquals(listOf("No school Tue"), titles(result))
    }

    @Test
    fun `daily and weekly all-day series are the usual week`() {
        val events = arrayOf(
            PlannerTestEvents.roomAllDay(1, fri, ny, rrule = "FREQ=WEEKLY;INTERVAL=2").withTitle("Trash day"),
            PlannerTestEvents.roomAllDay(2, fri, ny, rrule = "FREQ=WEEKLY;UNTIL=bad").withTitle("Recycling"),
            PlannerTestEvents.roomAllDay(3, fri, ny, rrule = "FREQ=DAILY").withTitle("Daily"),
        )
        assertTrue(summary(*events).isEmpty())
    }

    @Test
    fun `yearly, monthly, one-off and unreadable all-day items are listed`() {
        val events = arrayOf(
            PlannerTestEvents.roomAllDay(1, sat, ny, rrule = "FREQ=YEARLY").withTitle("Grandpa's birthday"),
            PlannerTestEvents.roomAllDay(2, fri, ny, rrule = "FREQ=MONTHLY;BYMONTHDAY=16").withTitle("Rent"),
            PlannerTestEvents.roomAllDay(3, fri, ny).withTitle("No school"),
            PlannerTestEvents.roomAllDay(4, fri, ny, rrule = "BYDAY=FR").withTitle("Odd rule"),
        )
        assertEquals(setOf("Grandpa's birthday", "Rent", "No school", "Odd rule"), titles(summary(*events)).toSet())
    }

    @Test
    fun `an all-day changed occurrence counts only when it moved or was cancelled`() {
        val unmoved = PlannerTestEvents.roomAllDay(10, fri, ny, seriesId = 1, originalInstanceTime = utcMidnight(fri)).withTitle("Trash day (note edited)")
        val moved = PlannerTestEvents.roomAllDay(11, sat, ny, seriesId = 2, originalInstanceTime = utcMidnight(fri)).withTitle("Recycling moved")
        val cancelled = PlannerTestEvents.roomAllDay(12, fri, ny, seriesId = 3, originalInstanceTime = utcMidnight(fri), status = "CANCELLED").withTitle("Market cancelled")
        val result = summary(unmoved, moved, cancelled, roomSeries = mapOf(1L to true, 2L to true, 3L to true))
        assertEquals(setOf("Recycling moved", "Market cancelled"), titles(result).toSet())
        assertEquals(PlannerSummaryKind.MOVED, result.single { it.event.title == "Recycling moved" }.kind)
        assertEquals(PlannerSummaryKind.CANCELLED, result.single { it.event.title == "Market cancelled" }.kind)
    }

    @Test
    fun `moved timed occurrences and cancelled events are listed, plain timed events aren't`() {
        val moved = PlannerTestEvents.room(
            id = 20, startTs = at(thu, 16, 30, zone = ny), endTs = at(thu, 18, zone = ny), zone = ny,
            title = "Soccer", seriesId = 5, originalInstanceTime = at(thu, 17, zone = ny),
        )
        val plain = PlannerTestEvents.room(id = 21, startTs = at(fri, 10, zone = ny), endTs = at(fri, 12, zone = ny), zone = ny, title = "Trampoline")
        val cancelled = PlannerTestEvents.room(
            id = 22, startTs = at(fri, 18, zone = ny), endTs = at(fri, 20, zone = ny), zone = ny,
            title = "Movie night", status = "CANCELLED",
        )
        val result = summary(moved, plain, cancelled, roomSeries = mapOf(5L to false))
        assertEquals(listOf("Soccer", "Movie night"), titles(result))
    }

    @Test
    fun `declined events never appear, even when moved`() {
        val declinedMoved = PlannerTestEvents.room(
            id = 30, startTs = at(thu, 19, 30, zone = ny), endTs = at(thu, 21, zone = ny), zone = ny,
            title = "Book club", seriesId = 6, originalInstanceTime = at(thu, 19, zone = ny), declined = true,
        )
        val declinedAllDay = PlannerTestEvents.roomAllDay(31, fri, ny, declined = true).withTitle("Conference")
        assertTrue(summary(declinedMoved, declinedAllDay, roomSeries = mapOf(6L to false)).isEmpty())
    }

    @Test
    fun `a multi-day item is one line with its day range`() {
        val trip = PlannerTestEvents.roomAllDay(40, thu, ny, days = 2).withTitle("Sam away")
        val result = summary(trip)
        assertEquals(1, result.size)
        assertEquals(dayCode(thu), result[0].firstDayCode)
        assertEquals(dayCode(fri), result[0].lastDayCode)
    }

    @Test
    fun `a multi-day timed event follows the strip setting`() {
        val start = at(fri, 18, zone = ny)
        val weekend = PlannerTestEvents.room(id = 41, startTs = start, endTs = start + 26 * PlannerTestEvents.HOUR, zone = ny, title = "Camping")
        assertEquals(listOf("Camping"), titles(summary(weekend, show = true)))
        assertTrue(summary(weekend, show = false).isEmpty())
    }

    @Test
    fun `items are ordered by first day, then all-day before changes, then start`() {
        val satBirthday = PlannerTestEvents.roomAllDay(50, sat, ny, rrule = "FREQ=YEARLY").withTitle("Birthday")
        val friMoved = PlannerTestEvents.room(
            id = 51, startTs = at(fri, 9, zone = ny), endTs = at(fri, 10, zone = ny), zone = ny,
            title = "Dentist moved", seriesId = 7, originalInstanceTime = at(wed, 9, zone = ny),
        )
        val friNoSchool = PlannerTestEvents.roomAllDay(52, fri, ny).withTitle("No school")
        val thuCancelled = PlannerTestEvents.room(
            id = 53, startTs = at(thu, 20, zone = ny), endTs = at(thu, 21, zone = ny), zone = ny,
            title = "Choir cancelled", status = "CANCELLED",
        )
        val thuMoved = PlannerTestEvents.room(
            id = 54, startTs = at(thu, 16, 30, zone = ny), endTs = at(thu, 18, zone = ny), zone = ny,
            title = "Soccer", seriesId = 8, originalInstanceTime = at(thu, 17, zone = ny),
        )
        val result = summary(satBirthday, friMoved, friNoSchool, thuCancelled, thuMoved, roomSeries = mapOf(7L to false, 8L to false))
        assertEquals(listOf("Soccer", "Choir cancelled", "No school", "Dentist moved", "Birthday"), titles(result))
    }

    @Test
    fun `an empty week has nothing to list`() {
        assertTrue(summary().isEmpty())
    }

    private fun DisplayEvent.Room.withTitle(title: String) = copy(event = event.copy(title = title))
}
