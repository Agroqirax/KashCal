package org.onekash.kashcal.ui.screens.planner

import org.junit.Assert.assertEquals
import org.junit.Test
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import java.util.Locale

/** Tests the Planner's shorter time line and moved cue: shared markers, ":00", zero length. */
class PlannerTimeFormatTest {

    private val zone = ZoneId.of("America/New_York")
    private val day = LocalDate.of(2026, 10, 15)
    private val twelve = "h:mm a"
    private val twentyFour = "HH:mm"
    private val join = { start: String, end: String -> "$start–$end" }

    private fun ms(hour: Int, minute: Int = 0, date: LocalDate = day): Long =
        date.atTime(LocalTime.of(hour, minute)).atZone(zone).toInstant().toEpochMilli()

    private fun range(start: Long, end: Long, pattern: String = twelve, locale: Locale = Locale.US) =
        plannerTimeRange(start, end, pattern, join, locale, zone)

    @Test
    fun `a shared marker is written once, at the end`() {
        assertEquals("4:30–6 pm", range(ms(16, 30), ms(18)))
    }

    @Test
    fun `times on the hour drop their minutes`() {
        assertEquals("4–5 pm", range(ms(16), ms(17)))
    }

    @Test
    fun `a range across noon keeps both markers`() {
        assertEquals("11 am–1:30 pm", range(ms(11), ms(13, 30)))
        assertEquals("11:30 am–12 pm", range(ms(11, 30), ms(12)))
    }

    @Test
    fun `a range ending at midnight keeps both markers`() {
        assertEquals("11 pm–12 am", range(ms(23), ms(0, date = day.plusDays(1))))
    }

    @Test
    fun `noon and midnight starts read as twelve`() {
        assertEquals("12–12:30 pm", range(ms(12), ms(12, 30)))
        assertEquals("12–12:30 am", range(ms(0), ms(0, 30)))
    }

    @Test
    fun `a zero-length event shows one time`() {
        assertEquals("4:30 pm", range(ms(16, 30), ms(16, 30)))
        assertEquals("16:30", range(ms(16, 30), ms(16, 30), twentyFour))
    }

    @Test
    fun `a day-long event from midnight to midnight is a range`() {
        assertEquals("12–12 am", range(ms(0), ms(0, date = day.plusDays(1))))
    }

    @Test
    fun `an hour across the autumn clock change is a range`() {
        // 1:30 EDT to 1:30 EST on the night the clocks go back.
        val fallBack = LocalDate.of(2026, 11, 1)
        val start = fallBack.atTime(1, 30).atZone(zone).withEarlierOffsetAtOverlap().toInstant().toEpochMilli()
        assertEquals("1:30–1:30 am", range(start, start + 3_600_000))
    }

    @Test
    fun `twenty-four hour time keeps its minutes`() {
        assertEquals("16:00–18:00", range(ms(16), ms(18), twentyFour))
    }

    @Test
    fun `the marker stays where the pattern puts it in another language`() {
        assertEquals("4:30–6 오후", range(ms(16, 30), ms(18), locale = Locale.KOREAN))
    }

    @Test
    fun `the joiner comes from the caller`() {
        assertEquals("4–5 pm", range(ms(16), ms(17)))
        assertEquals("4〜5 pm", plannerTimeRange(ms(16), ms(17), twelve, { a, b -> "$a〜$b" }, Locale.US, zone))
    }

    @Test
    fun `a single time drops minutes on the hour`() {
        assertEquals("5 pm", plannerTime(ms(17), twelve, Locale.US, zone))
        assertEquals("5:30 pm", plannerTime(ms(17, 30), twelve, Locale.US, zone))
        assertEquals("17:00", plannerTime(ms(17), twentyFour, Locale.US, zone))
    }
}
