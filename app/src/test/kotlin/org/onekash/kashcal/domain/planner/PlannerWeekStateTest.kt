package org.onekash.kashcal.domain.planner

import kotlinx.collections.immutable.persistentMapOf
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Tests [PlannerWeekState.covers], which tells a page whether its days have loaded. */
class PlannerWeekStateTest {

    private val state = PlannerWeekState(
        eventsByDayCode = persistentMapOf(),
        rangeStartDayCode = 20261005,
        rangeEndDayCode = 20261025,
    )

    @Test
    fun `the covered range is inclusive at both ends`() {
        assertTrue(state.covers(20261005))
        assertTrue(state.covers(20261025))
        assertTrue(state.covers(20261015))
    }

    @Test
    fun `days outside the range aren't covered, across a month boundary too`() {
        assertFalse(state.covers(20261004))
        assertFalse(state.covers(20261026))
        assertFalse(state.covers(20260930))
    }
}
