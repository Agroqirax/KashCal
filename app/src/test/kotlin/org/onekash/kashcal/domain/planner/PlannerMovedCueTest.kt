package org.onekash.kashcal.domain.planner

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.onekash.kashcal.domain.planner.PlannerTestEvents.at
import org.onekash.kashcal.domain.planner.PlannerTestEvents.dayCode
import org.onekash.kashcal.domain.planner.PlannerTestEvents.utcMidnight
import java.time.LocalDate
import java.time.ZoneId

/**
 * Tests [movedCue]: what a changed occurrence's "was" cue says, for Room and device events, and
 * when it stays silent because the original start can't be read safely.
 */
class PlannerMovedCueTest {

    private val ny = ZoneId.of("America/New_York")
    private val tokyo = ZoneId.of("Asia/Tokyo")

    // Monday 12 Oct 2026 to Sunday 18 Oct 2026.
    private val week = (0..6).map { dayCode(LocalDate.of(2026, 10, 12).plusDays(it.toLong())) }
    private val tue = LocalDate.of(2026, 10, 13)
    private val thu = LocalDate.of(2026, 10, 15)
    private val fri = LocalDate.of(2026, 10, 16)
    private val sat = LocalDate.of(2026, 10, 17)

    private fun roomTimedException(
        original: Long,
        start: Long,
        zone: ZoneId = ny,
        seriesId: Long = 1,
    ) = PlannerTestEvents.room(
        id = 50, startTs = start, endTs = start + PlannerTestEvents.HOUR, zone = zone,
        seriesId = seriesId, originalInstanceTime = original,
    )

    @Test
    fun `a timed series moved within its day reads as the old time`() {
        val original = at(thu, 17, zone = ny)
        val ex = roomTimedException(original, start = at(thu, 16, 30, zone = ny))
        assertEquals(MovedCue.Time(original), movedCue(ex, mapOf(1L to false), week, ny))
    }

    @Test
    fun `a move from another day of the week reads as that day`() {
        val ex = roomTimedException(original = at(tue, 17, zone = ny), start = at(thu, 17, zone = ny))
        assertEquals(MovedCue.Day(dayCode(tue)), movedCue(ex, mapOf(1L to false), week, ny))
    }

    @Test
    fun `a move from outside the shown week reads as a date`() {
        val lastFri = LocalDate.of(2026, 10, 9)
        val ex = roomTimedException(original = at(lastFri, 17, zone = ny), start = at(thu, 17, zone = ny))
        assertEquals(MovedCue.Date(dayCode(lastFri)), movedCue(ex, mapOf(1L to false), week, ny))
    }

    @Test
    fun `an edit that keeps the start has no cue`() {
        val start = at(thu, 17, zone = ny)
        val ex = roomTimedException(original = start, start = start)
        assertNull(movedCue(ex, mapOf(1L to false), week, ny))
    }

    @Test
    fun `an event that isn't a changed occurrence has no cue`() {
        val plain = PlannerTestEvents.room(id = 3, startTs = at(thu, 9, zone = ny), endTs = at(thu, 10, zone = ny), zone = ny)
        assertNull(movedCue(plain, emptyMap(), week, ny))
    }

    @Test
    fun `an all-day series moved to another day reads as that day`() {
        val ex = PlannerTestEvents.roomAllDay(id = 60, date = sat, zone = ny, seriesId = 2, originalInstanceTime = utcMidnight(fri))
        assertEquals(MovedCue.Day(dayCode(fri)), movedCue(ex, mapOf(2L to true), week, ny))
    }

    @Test
    fun `an all-day series occurrence given a time on the same day has no cue`() {
        val ex = PlannerTestEvents.room(
            id = 61, startTs = at(fri, 10, zone = ny), endTs = at(fri, 11, zone = ny), zone = ny,
            seriesId = 2, originalInstanceTime = utcMidnight(fri),
        )
        assertNull(movedCue(ex, mapOf(2L to true), week, ny))
    }

    @Test
    fun `a 9am Tokyo series sits on UTC midnight and still reads as the old time when the series is known`() {
        val original = at(thu, 9, zone = tokyo)
        assertEquals(utcMidnight(thu), original)
        val ex = roomTimedException(original, start = at(thu, 10, zone = tokyo), zone = tokyo)
        assertEquals(MovedCue.Time(original), movedCue(ex, mapOf(1L to false), week, tokyo))
    }

    @Test
    fun `an unknown series with a UTC midnight original has no cue`() {
        val original = at(thu, 9, zone = tokyo)
        val ex = roomTimedException(original, start = at(thu, 10, zone = tokyo), zone = tokyo)
        assertNull(movedCue(ex, emptyMap(), week, tokyo))
    }

    @Test
    fun `an unknown series with a non-midnight original is read as timed`() {
        val original = at(thu, 17, zone = ny)
        val ex = roomTimedException(original, start = at(thu, 16, zone = ny))
        assertEquals(MovedCue.Time(original), movedCue(ex, emptyMap(), week, ny))
    }

    @Test
    fun `an unknown all-day occurrence with a non-midnight original has no cue`() {
        val ex = PlannerTestEvents.roomAllDay(id = 62, date = sat, zone = ny, seriesId = 3, originalInstanceTime = at(fri, 17, zone = ny))
        assertNull(movedCue(ex, emptyMap(), week, ny))
    }

    @Test
    fun `device exceptions use their own original all-day flag`() {
        val timed = PlannerTestEvents.device(
            instanceId = 1, eventId = 70, startTs = at(thu, 16, zone = ny), endTs = at(thu, 17, zone = ny), zone = ny,
            originalId = 9, originalInstanceTime = at(thu, 17, zone = ny), originalAllDay = false,
        )
        val allDay = PlannerTestEvents.device(
            instanceId = 2, eventId = 71, startTs = utcMidnight(sat), endTs = utcMidnight(sat.plusDays(1)) - 1,
            isAllDay = true, zone = ny, originalId = 10, originalInstanceTime = utcMidnight(fri), originalAllDay = true,
        )
        val unknownTokyo = PlannerTestEvents.device(
            instanceId = 3, eventId = 72, startTs = at(thu, 10, zone = tokyo), endTs = at(thu, 11, zone = tokyo), zone = tokyo,
            originalId = 11, originalInstanceTime = at(thu, 9, zone = tokyo), originalAllDay = null,
        )
        assertEquals(MovedCue.Time(at(thu, 17, zone = ny)), movedCue(timed, emptyMap(), week, ny))
        assertEquals(MovedCue.Day(dayCode(fri)), movedCue(allDay, emptyMap(), week, ny))
        assertNull(movedCue(unknownTokyo, emptyMap(), week, tokyo))
    }

    @Test
    fun `a Room series and a device series sharing id 1 don't share a kind`() {
        // Room event ids and CalendarProvider event ids are separate sequences that both start
        // at 1, so the Room map must never answer for a device event.
        val roomEx = roomTimedException(original = at(thu, 17, zone = ny), start = at(thu, 16, zone = ny), seriesId = 1)
        val deviceEx = PlannerTestEvents.device(
            instanceId = 4, eventId = 73, startTs = utcMidnight(sat), endTs = utcMidnight(sat.plusDays(1)) - 1,
            isAllDay = true, zone = ny, originalId = 1, originalInstanceTime = utcMidnight(fri), originalAllDay = true,
        )
        val roomMap = mapOf(1L to false)
        assertEquals(MovedCue.Time(at(thu, 17, zone = ny)), movedCue(roomEx, roomMap, week, ny))
        assertEquals(MovedCue.Day(dayCode(fri)), movedCue(deviceEx, roomMap, week, ny))
    }
}
