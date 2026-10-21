package org.onekash.kashcal.ui.components.weekview

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performTouchInput
import kotlinx.collections.immutable.persistentListOf
import kotlinx.collections.immutable.toImmutableList
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.time.LocalDate

/**
 * Local probe, not committed: records where the grid drops the today part of a timed event that
 * began yesterday (22:00 yesterday to 03:00 today, drawn from 00:00), dragged straight down by an
 * hour from 2px below its top. Prints the result; asserts nothing.
 */
@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [34], qualifiers = "w360dp-h1600dp-mdpi")
class EarlierDayDragProbeTest {

    @get:Rule
    val rule = createComposeRule()

    @Test
    fun `probe the drop of an event's later-day part`() {
        val today = LocalDate.now()
        val base = roomDisplayEvent(id = 1, title = "Overnight", date = today.minusDays(1), hour = 22, durationMinutes = 300)
        val todayCode = today.year * 10000 + today.monthValue * 100 + today.dayOfMonth
        val event = base.copy(occurrence = base.occurrence.copy(endDay = todayCode))
        val drops = mutableListOf<Pair<LocalDate, Int>>()
        rule.setContent {
            MaterialTheme {
                WeekViewContent(
                    timedEvents = listOf(event).toImmutableList(),
                    allDayEvents = persistentListOf(),
                    isLoading = false,
                    error = null,
                    scrollPosition = 0,
                    savedScrollMinutes = 0,
                    hourHeight = 60f,
                    visibleDays = 1,
                    onDatePickerRequest = {},
                    onScrollPositionChange = {},
                    onEventClick = {},
                    onReschedule = { _, day, minutes -> drops += day to minutes },
                    modifier = Modifier.fillMaxSize(),
                )
            }
        }
        rule.waitForIdle()
        val block = rule.onAllNodesWithText("Overnight", substring = true).fetchSemanticsNodes().map { it.boundsInRoot }.also { println("PROBE nodes $it") }.first { it.width > 0f && it.left >= 0f && it.top < 300f }
        rule.mainClock.autoAdvance = false
        rule.onRoot().performTouchInput {
            down(Offset(block.left + 4f, block.top + 2f))
            advanceEventTime(700)
            moveBy(Offset(0f, 30f))
            moveBy(Offset(0f, 30f))
            up()
        }
        rule.mainClock.autoAdvance = true
        rule.waitForIdle()
        println("PROBE block top ${block.top} height ${block.height}; drops ${drops.map { "${it.first} %02d:%02d".format(it.second / 60, it.second % 60) }}")
    }
}
