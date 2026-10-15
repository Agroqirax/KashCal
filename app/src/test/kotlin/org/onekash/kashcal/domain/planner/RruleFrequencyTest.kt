package org.onekash.kashcal.domain.planner

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Tests [rruleFrequency] and [isDailyOrWeekly], which read only the FREQ part of a rule. */
class RruleFrequencyTest {

    @Test
    fun `INTERVAL doesn't change the frequency`() {
        assertEquals("WEEKLY", rruleFrequency("FREQ=WEEKLY;INTERVAL=2;BYDAY=TU"))
    }

    @Test
    fun `a malformed UNTIL doesn't hide a weekly rule`() {
        assertEquals("WEEKLY", rruleFrequency("FREQ=WEEKLY;UNTIL=not-a-date"))
        assertTrue(isDailyOrWeekly("FREQ=WEEKLY;UNTIL=not-a-date"))
    }

    @Test
    fun `a lowercase rule and an RRULE prefix are read`() {
        assertEquals("WEEKLY", rruleFrequency("freq=weekly;byday=mo"))
        assertEquals("DAILY", rruleFrequency("RRULE:FREQ=DAILY;COUNT=5"))
    }

    @Test
    fun `a rule without FREQ or no rule has no frequency`() {
        assertNull(rruleFrequency("INTERVAL=2;BYDAY=MO"))
        assertNull(rruleFrequency(""))
        assertNull(rruleFrequency(null))
        assertFalse(isDailyOrWeekly(null))
    }

    @Test
    fun `monthly and yearly are not daily or weekly`() {
        assertFalse(isDailyOrWeekly("FREQ=MONTHLY;BYMONTHDAY=1"))
        assertFalse(isDailyOrWeekly("FREQ=YEARLY"))
        assertTrue(isDailyOrWeekly("FREQ=DAILY"))
    }
}
