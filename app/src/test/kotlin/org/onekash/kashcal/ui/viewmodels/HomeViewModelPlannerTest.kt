package org.onekash.kashcal.ui.viewmodels

import app.cash.turbine.test
import io.mockk.coEvery
import io.mockk.coJustRun
import io.mockk.coVerify
import io.mockk.every
import io.mockk.verify
import kotlinx.collections.immutable.ImmutableList
import kotlinx.collections.immutable.ImmutableMap
import kotlinx.collections.immutable.persistentListOf
import kotlinx.collections.immutable.persistentMapOf
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.onekash.kashcal.data.calendar_provider.FakeCalendarProviderRepository
import org.onekash.kashcal.data.calendar_provider.deviceEventReader
import org.onekash.kashcal.data.calendar_provider.deviceEventWriter
import org.onekash.kashcal.data.db.entity.Event
import org.onekash.kashcal.data.preferences.KashCalDataStore
import org.onekash.kashcal.domain.model.DisplayEvent
import org.onekash.kashcal.domain.planner.PlannerTestEvents
import org.onekash.kashcal.ui.components.weekview.WeekViewUtils
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.time.LocalDate
import java.time.ZoneId
import java.util.Calendar

/**
 * Tests the Planner's state in [HomeViewModel]: which week it shows and loads, how paging,
 * Today, the date picker and the first-day setting move it, that leaving it stops its query,
 * the series look-up for the "was" cue, and the saved pinch zoom. Collaborators are the strict
 * mocks of [DeviceHomeViewModelTestFactory], with each Planner read stubbed here.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [33])
class HomeViewModelPlannerTest {

    private val dispatcher = StandardTestDispatcher()
    private lateinit var factory: DeviceHomeViewModelTestFactory
    private val firstDay = MutableStateFlow(Calendar.MONDAY)
    private val stripSetting = MutableStateFlow(false)

    /** Every (start, end) day-code range the Planner asked the repository for, in order. */
    private val requested = mutableListOf<Pair<Int, Int>>()

    /** The flow the next range request returns; a test swaps it before paging. */
    private var nextRangeFlow: Flow<ImmutableMap<Int, ImmutableList<DisplayEvent>>> =
        MutableStateFlow(persistentMapOf())

    private val zone = ZoneId.systemDefault()

    @Before
    fun setup() {
        Dispatchers.setMain(dispatcher)
        factory = DeviceHomeViewModelTestFactory()
        // Startup's account check reads this; the factory doesn't stub it because its other
        // tests don't need startup to finish, and the Planner's default-view and zoom seeding do.
        coEvery { factory.accountRepository.getAllAccounts() } returns emptyList()
        every { factory.dataStore.firstDayOfWeek } returns firstDay
        every { factory.dataStore.showMultiDayTimedInAllDayStrip } returns stripSetting
        every { factory.displayEventRepository.getDisplayEventsForDateRange(any(), any()) } answers {
            requested += firstArg<Int>() to secondArg<Int>()
            nextRangeFlow
        }
    }

    @After
    fun tearDown() = Dispatchers.resetMain()

    private fun createViewModel(): HomeViewModel {
        val device = FakeCalendarProviderRepository()
        return factory.create(device.deviceEventReader(), device.deviceEventWriter(factory.dataStore), dispatcher)
    }

    private fun dayCode(date: LocalDate) = PlannerTestEvents.dayCode(date)

    private fun thisWeekStart(firstDayOfWeek: Int = Calendar.MONDAY) =
        WeekViewUtils.getWeekStart(LocalDate.now(), firstDayOfWeek)

    private fun rangeFor(weekStart: LocalDate) = dayCode(weekStart.minusDays(7)) to dayCode(weekStart.plusDays(13))

    private fun oneEventMap(date: LocalDate, title: String): ImmutableMap<Int, ImmutableList<DisplayEvent>> {
        val start = PlannerTestEvents.at(date, 9, zone = zone)
        val event = PlannerTestEvents.room(id = 1, startTs = start, endTs = start + PlannerTestEvents.HOUR, zone = zone, title = title)
        return persistentMapOf(dayCode(date) to persistentListOf<DisplayEvent>(event))
    }

    @Test
    fun `the planner state is null until its first load, then holds the loaded week`() = runTest {
        val pending = MutableSharedFlow<ImmutableMap<Int, ImmutableList<DisplayEvent>>>()
        nextRangeFlow = pending
        val vm = createViewModel()
        advanceUntilIdle()

        vm.plannerState.test {
            assertNull(awaitItem())
            vm.setViewMode(ViewMode.PLANNER)
            advanceUntilIdle()
            expectNoEvents()

            pending.emit(oneEventMap(LocalDate.now(), "Swim"))
            val state = awaitItem()
            assertNotNull(state)
            val (start, end) = rangeFor(thisWeekStart())
            assertEquals(start, state!!.rangeStartDayCode)
            assertEquals(end, state.rangeEndDayCode)
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `entering loads this week with a week on either side`() = runTest {
        val vm = createViewModel()
        advanceUntilIdle()
        vm.setViewMode(ViewMode.PLANNER)
        vm.plannerState.test {
            advanceUntilIdle()
            cancelAndIgnoreRemainingEvents()
        }
        assertEquals(listOf(rangeFor(thisWeekStart())), requested)
    }

    @Test
    fun `paging re-keys and keeps the previous week until the next one loads`() = runTest {
        nextRangeFlow = MutableStateFlow(oneEventMap(LocalDate.now(), "This week"))
        val vm = createViewModel()
        advanceUntilIdle()
        vm.setViewMode(ViewMode.PLANNER)

        vm.plannerState.test {
            advanceUntilIdle()
            val first = expectMostRecentItem()!!
            val pending = MutableSharedFlow<ImmutableMap<Int, ImmutableList<DisplayEvent>>>()
            nextRangeFlow = pending

            vm.onPlannerPageChanged(WeekViewUtils.CENTER_WEEK_PAGE + 1)
            advanceUntilIdle()
            expectNoEvents() // no null and no empty state while the next week loads

            pending.emit(oneEventMap(LocalDate.now().plusWeeks(1), "Next week"))
            val second = awaitItem()!!
            assertEquals(rangeFor(thisWeekStart().plusWeeks(1)).first, second.rangeStartDayCode)
            assertTrue(second.rangeStartDayCode > first.rangeStartDayCode)
            cancelAndIgnoreRemainingEvents()
        }
        assertEquals(WeekViewUtils.CENTER_WEEK_PAGE + 1, vm.uiState.value.plannerWeekPagerPosition)
    }

    @Test
    fun `a Sunday-first or Saturday-first week starts on that day`() = runTest {
        for (day in listOf(Calendar.SUNDAY, Calendar.SATURDAY)) {
            requested.clear()
            firstDay.value = day
            val vm = createViewModel()
            advanceUntilIdle()
            vm.setViewMode(ViewMode.PLANNER)
            vm.plannerState.test {
                advanceUntilIdle()
                cancelAndIgnoreRemainingEvents()
            }
            assertEquals(rangeFor(thisWeekStart(day)), requested.last())
        }
    }

    @Test
    fun `a first-day change keeps the page and loads the week it now starts`() = runTest {
        val vm = createViewModel()
        advanceUntilIdle()
        vm.setViewMode(ViewMode.PLANNER)
        vm.plannerState.test {
            advanceUntilIdle()
            vm.onPlannerPageChanged(WeekViewUtils.CENTER_WEEK_PAGE + 1)
            advanceUntilIdle()

            firstDay.value = Calendar.SUNDAY
            advanceUntilIdle()
            cancelAndIgnoreRemainingEvents()
        }
        assertEquals(WeekViewUtils.CENTER_WEEK_PAGE + 1, vm.uiState.value.plannerWeekPagerPosition)
        assertEquals(rangeFor(thisWeekStart(Calendar.SUNDAY).plusWeeks(1)), requested.last())
    }

    @Test
    fun `leaving the planner stops its query and a trip to Insights doesn't`() = runTest {
        val vm = createViewModel()
        advanceUntilIdle()
        vm.setViewMode(ViewMode.PLANNER)
        vm.plannerState.test {
            advanceUntilIdle()
            assertNotNull(expectMostRecentItem())

            vm.setViewMode(ViewMode.INSIGHTS)
            advanceUntilIdle()
            expectNoEvents()

            vm.setViewMode(ViewMode.MONTH)
            advanceUntilIdle()
            assertNull(expectMostRecentItem())
            cancelAndIgnoreRemainingEvents()
        }
        assertEquals(1, requested.size)
    }

    @Test
    fun `startup with the planner as the default lands on this week`() = runTest {
        coEvery { factory.dataStore.getDefaultCalendarView() } returns KashCalDataStore.VIEW_PLANNER
        val vm = createViewModel()
        vm.plannerState.test {
            advanceUntilIdle()
            assertNotNull(expectMostRecentItem())
            cancelAndIgnoreRemainingEvents()
        }
        assertEquals(ViewMode.PLANNER, vm.uiState.value.viewMode)
        assertEquals(rangeFor(thisWeekStart()), requested.last())
    }

    @Test
    fun `Today moves the planner back each time it is tapped`() = runTest {
        val vm = createViewModel()
        advanceUntilIdle()
        vm.setViewMode(ViewMode.PLANNER)
        advanceUntilIdle()

        vm.onPlannerPageChanged(WeekViewUtils.CENTER_WEEK_PAGE + 2)
        vm.goToToday()
        assertEquals(WeekViewUtils.CENTER_WEEK_PAGE, vm.uiState.value.pendingPlannerPagerPosition)
        vm.clearPendingPlannerPagerPosition()
        assertNull(vm.uiState.value.pendingPlannerPagerPosition)

        vm.onPlannerPageChanged(WeekViewUtils.CENTER_WEEK_PAGE + 2)
        vm.goToToday()
        assertEquals(WeekViewUtils.CENTER_WEEK_PAGE, vm.uiState.value.pendingPlannerPagerPosition)
    }

    @Test
    fun `Today loads this week again after paging away`() = runTest {
        val vm = createViewModel()
        advanceUntilIdle()
        vm.setViewMode(ViewMode.PLANNER)
        vm.plannerState.test {
            advanceUntilIdle()
            vm.onPlannerPageChanged(WeekViewUtils.CENTER_WEEK_PAGE + 3)
            advanceUntilIdle()
            vm.goToToday()
            advanceUntilIdle()
            cancelAndIgnoreRemainingEvents()
        }
        assertEquals(rangeFor(thisWeekStart()), requested.last())
    }

    @Test
    fun `a swipe that interrupts Today's scroll lets the next Today scroll again`() = runTest {
        val vm = createViewModel()
        advanceUntilIdle()
        vm.setViewMode(ViewMode.PLANNER)
        advanceUntilIdle()
        vm.onPlannerPageChanged(WeekViewUtils.CENTER_WEEK_PAGE + 5)

        vm.goToToday()
        assertEquals(WeekViewUtils.CENTER_WEEK_PAGE, vm.uiState.value.pendingPlannerPagerPosition)
        // The user swipes mid-scroll and the pager settles elsewhere; the scroll never finishes.
        vm.onPlannerPageChanged(WeekViewUtils.CENTER_WEEK_PAGE + 4)
        assertNull(vm.uiState.value.pendingPlannerPagerPosition)

        vm.goToToday()
        assertEquals(WeekViewUtils.CENTER_WEEK_PAGE, vm.uiState.value.pendingPlannerPagerPosition)
    }

    @Test
    fun `settling on the pending page keeps it until the pager reports it consumed`() = runTest {
        val vm = createViewModel()
        advanceUntilIdle()
        vm.setViewMode(ViewMode.PLANNER)
        advanceUntilIdle()
        vm.onPlannerPageChanged(WeekViewUtils.CENTER_WEEK_PAGE + 2)
        vm.goToToday()
        vm.onPlannerPageChanged(WeekViewUtils.CENTER_WEEK_PAGE)
        assertEquals(WeekViewUtils.CENTER_WEEK_PAGE, vm.uiState.value.pendingPlannerPagerPosition)
    }

    @Test
    fun `a failing events query leaves a loaded empty week, not a stuck loading state`() = runTest {
        nextRangeFlow = kotlinx.coroutines.flow.flow { throw IllegalStateException("provider gone") }
        val vm = createViewModel()
        advanceUntilIdle()
        vm.setViewMode(ViewMode.PLANNER)
        vm.plannerState.test {
            advanceUntilIdle()
            val state = expectMostRecentItem()!!
            assertTrue(state.eventsByDayCode.isEmpty())
            assertEquals(rangeFor(thisWeekStart()).first, state.rangeStartDayCode)
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `a picked date moves the planner and leaves the time grid alone`() = runTest {
        val vm = createViewModel()
        advanceUntilIdle()
        vm.setViewMode(ViewMode.PLANNER)
        advanceUntilIdle()
        val weekPositionBefore = vm.uiState.value.weekViewPagerPosition

        vm.showWeekViewDatePicker()
        val picked = LocalDate.now().plusWeeks(3)
        vm.onWeekViewDateSelected(WeekViewUtils.dateToEpochMs(picked))
        advanceUntilIdle()

        assertEquals(WeekViewUtils.CENTER_WEEK_PAGE + 3, vm.uiState.value.pendingPlannerPagerPosition)
        assertEquals(false, vm.uiState.value.showWeekViewDatePicker)
        assertEquals(weekPositionBefore, vm.uiState.value.weekViewPagerPosition)
        assertEquals(null, vm.uiState.value.pendingWeekViewPagerPosition)
        verify(exactly = 0) { factory.displayEventRepository.getDisplayEventsForRange(any(), any()) }
    }

    @Test
    fun `a Room changed occurrence's series kind is looked up once`() = runTest {
        val today = LocalDate.now()
        val start = PlannerTestEvents.at(today, 16, zone = zone)
        val exception = PlannerTestEvents.room(
            id = 50, startTs = start, endTs = start + PlannerTestEvents.HOUR, zone = zone,
            seriesId = 5, originalInstanceTime = start + PlannerTestEvents.HOUR,
        )
        nextRangeFlow = MutableStateFlow(persistentMapOf(dayCode(today) to persistentListOf<DisplayEvent>(exception)))
        coEvery { factory.eventReader.getEventsByIds(listOf(5L)) } returns
            mapOf(5L to Event(id = 5, uid = "s", calendarId = 1, title = "Soccer", startTs = 0, endTs = 1, isAllDay = false, dtstamp = 0))

        val vm = createViewModel()
        advanceUntilIdle()
        vm.setViewMode(ViewMode.PLANNER)
        vm.plannerState.test {
            advanceUntilIdle()
            assertEquals(mapOf(5L to false), expectMostRecentItem()!!.roomSeriesAllDayById)
            cancelAndIgnoreRemainingEvents()
        }
        coVerify(exactly = 1) { factory.eventReader.getEventsByIds(any()) }
    }

    @Test
    fun `a failing series look-up still shows the week's events`() = runTest {
        val today = LocalDate.now()
        val start = PlannerTestEvents.at(today, 16, zone = zone)
        val exception = PlannerTestEvents.room(
            id = 50, startTs = start, endTs = start + PlannerTestEvents.HOUR, zone = zone,
            seriesId = 5, originalInstanceTime = start + PlannerTestEvents.HOUR,
        )
        nextRangeFlow = MutableStateFlow(persistentMapOf(dayCode(today) to persistentListOf<DisplayEvent>(exception)))
        coEvery { factory.eventReader.getEventsByIds(any()) } throws IllegalStateException("db closed")

        val vm = createViewModel()
        advanceUntilIdle()
        vm.setViewMode(ViewMode.PLANNER)
        vm.plannerState.test {
            advanceUntilIdle()
            val state = expectMostRecentItem()!!
            assertEquals(1, state.eventsByDayCode[dayCode(today)]?.size)
            assertTrue(state.roomSeriesAllDayById.isEmpty())
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `a week without changed occurrences makes no series look-up`() = runTest {
        nextRangeFlow = MutableStateFlow(oneEventMap(LocalDate.now(), "Swim"))
        val vm = createViewModel()
        advanceUntilIdle()
        vm.setViewMode(ViewMode.PLANNER)
        vm.plannerState.test {
            advanceUntilIdle()
            cancelAndIgnoreRemainingEvents()
        }
        coVerify(exactly = 0) { factory.eventReader.getEventsByIds(any()) }
    }

    @Test
    fun `the multi-day strip setting re-emits the state without a new query`() = runTest {
        val vm = createViewModel()
        advanceUntilIdle()
        vm.setViewMode(ViewMode.PLANNER)
        vm.plannerState.test {
            advanceUntilIdle()
            assertEquals(false, expectMostRecentItem()!!.showMultiDayTimedInAllDayStrip)
            stripSetting.value = true
            advanceUntilIdle()
            assertEquals(true, expectMostRecentItem()!!.showMultiDayTimedInAllDayStrip)
            cancelAndIgnoreRemainingEvents()
        }
        assertEquals(1, requested.size)
    }

    @Test
    fun `an event created in the loaded weeks appears without a reload`() = runTest {
        val rangeFlow = MutableStateFlow<ImmutableMap<Int, ImmutableList<DisplayEvent>>>(persistentMapOf())
        nextRangeFlow = rangeFlow
        val vm = createViewModel()
        advanceUntilIdle()
        vm.setViewMode(ViewMode.PLANNER)
        vm.plannerState.test {
            advanceUntilIdle()
            assertTrue(expectMostRecentItem()!!.eventsByDayCode.isEmpty())
            rangeFlow.value = oneEventMap(LocalDate.now(), "Dentist")
            advanceUntilIdle()
            assertEquals("Dentist", expectMostRecentItem()!!.eventsByDayCode.values.single().single().title)
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `startup seeds the saved zoom, clamped`() = runTest {
        for ((saved, expected) in listOf(2.0f to 1.6f, Float.NaN to 1.0f, 0f to 1.0f, 1.25f to 1.25f)) {
            coEvery { factory.dataStore.getPlannerZoom() } returns saved
            val vm = createViewModel()
            advanceUntilIdle()
            assertEquals("saved $saved", expected, vm.uiState.value.plannerZoom)
        }
    }

    @Test
    fun `a pinch updates the zoom at once and saves it after a pause`() = runTest {
        coJustRun { factory.dataStore.setPlannerZoom(any()) }
        val vm = createViewModel()
        advanceUntilIdle()

        vm.setPlannerZoom(1.2f)
        vm.setPlannerZoom(1.3f)
        assertEquals(1.3f, vm.uiState.value.plannerZoom)
        coVerify(exactly = 0) { factory.dataStore.setPlannerZoom(any()) }

        advanceTimeBy(1_100)
        advanceUntilIdle()
        coVerify(exactly = 1) { factory.dataStore.setPlannerZoom(1.3f) }
    }
}
