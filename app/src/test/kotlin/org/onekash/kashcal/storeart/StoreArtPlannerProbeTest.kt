package org.onekash.kashcal.storeart

import androidx.compose.foundation.text.input.TextFieldState
import androidx.compose.runtime.Composable
import androidx.compose.ui.test.junit4.createComposeRule
import com.github.takahirom.roborazzi.captureScreenRoboImage
import kotlinx.collections.immutable.persistentListOf
import kotlinx.collections.immutable.persistentSetOf
import kotlinx.collections.immutable.toImmutableList
import kotlinx.collections.immutable.toImmutableMap
import kotlinx.collections.immutable.toPersistentSet
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.onekash.kashcal.data.db.entity.Calendar
import org.onekash.kashcal.data.db.entity.Event
import org.onekash.kashcal.data.db.entity.Occurrence
import org.onekash.kashcal.domain.model.DisplayEvent
import org.onekash.kashcal.domain.planner.PlannerWeekState
import org.onekash.kashcal.domain.quickadd.QuickAddParser
import org.onekash.kashcal.domain.model.AccountProvider
import org.onekash.kashcal.ui.components.CalendarVisibilitySheet
import org.onekash.kashcal.ui.components.EventQuickViewSheet
import org.onekash.kashcal.ui.model.CalendarGroup
import org.onekash.kashcal.ui.components.QuickAddDialog
import org.onekash.kashcal.ui.components.attendees.AttendeeStatus
import org.onekash.kashcal.ui.components.attendees.AttendeeUiModel
import org.onekash.kashcal.ui.components.weekview.WeekViewUtils
import org.onekash.kashcal.ui.screens.HomeScreen
import org.onekash.kashcal.ui.theme.ColorSource
import org.onekash.kashcal.ui.theme.KashCalTheme
import org.onekash.kashcal.ui.theme.ThemeMode
import org.onekash.kashcal.ui.viewmodels.AgendaUiState
import org.onekash.kashcal.ui.viewmodels.HomeUiState
import org.onekash.kashcal.ui.viewmodels.ViewMode
import org.onekash.kashcal.ui.viewmodels.WeekEventsUiState
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import java.time.ZoneOffset
import java.util.TimeZone
import java.util.Calendar as JavaCalendar

/**
 * Spike: renders real app screens with demo events at phone resolution for store art.
 * Run with -Proborazzi.test.record=true; images land in store-art/roborazzi/.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "w360dp-h800dp-xxhdpi")
class StoreArtPlannerProbeTest {

    @get:Rule
    val rule = createComposeRule()

    // HomeScreen reads the wall clock for today and for past-event dimming, so the zone is picked
    // to put "now" at about 10:00, which leaves the morning dimmed and the rest of today live.
    private val zone: ZoneId = run {
        val utcHour = Instant.now().atZone(ZoneOffset.UTC).hour
        var offset = 10 - utcHour
        if (offset < -12) offset += 24
        if (offset > 14) offset -= 24
        ZoneOffset.ofHours(offset)
    }

    @Before
    fun pinZone() {
        TimeZone.setDefault(TimeZone.getTimeZone(zone))
    }

    private val work = Calendar(id = 1, accountId = 1, caldavUrl = "https://example.test/work/", displayName = "Work", color = 0xFF3F7FD9.toInt())
    private val family = Calendar(id = 2, accountId = 1, caldavUrl = "https://example.test/family/", displayName = "Family", color = 0xFF2E9E6A.toInt())
    private val personal = Calendar(id = 3, accountId = 1, caldavUrl = "https://example.test/me/", displayName = "Personal", color = 0xFFE07B39.toInt())

    private var nextId = 1L

    private fun today(): LocalDate = LocalDate.now(zone)
    private fun thisMonday(): LocalDate = today().with(DayOfWeek.MONDAY)
    private fun code(d: LocalDate) = d.year * 10000 + d.monthValue * 100 + d.dayOfMonth
    private fun dateOf(c: Int) = LocalDate.of(c / 10000, c / 100 % 100, c % 100)

    private fun timed(cal: Calendar, day: LocalDate, start: String, end: String, title: String, location: String? = null, description: String? = null): DisplayEvent {
        val s = day.atTime(LocalTime.parse(start)).atZone(zone).toInstant().toEpochMilli()
        val e = day.atTime(LocalTime.parse(end)).atZone(zone).toInstant().toEpochMilli()
        return build(cal, day, day, s, e, title, allDay = false, location, description)
    }

    private fun allDay(cal: Calendar, day: LocalDate, title: String, days: Int = 1): DisplayEvent {
        val s = day.atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli()
        val last = day.plusDays(days - 1L)
        val e = last.plusDays(1).atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli() - 1
        return build(cal, day, last, s, e, title, allDay = true)
    }

    private fun build(cal: Calendar, first: LocalDate, last: LocalDate, s: Long, e: Long, title: String, allDay: Boolean, location: String? = null, description: String? = null): DisplayEvent {
        val id = nextId++
        val event = Event(id = id, uid = "demo-$id@example.test", calendarId = cal.id, title = title, location = location, description = description, startTs = s, endTs = e, isAllDay = allDay, dtstamp = s, timezone = zone.id)
        val occ = Occurrence(id = id, eventId = id, calendarId = cal.id, startTs = s, endTs = e, startDay = code(first), endDay = code(last))
        return DisplayEvent.Room(event, occ, cal)
    }

    /** One busy week, Monday to Sunday, around today. */
    private fun weekEvents(): List<DisplayEvent> {
        val mon = thisMonday()
        return listOf(
            timed(work, mon, "09:00", "09:15", "Standup"),
            timed(work, mon, "13:00", "14:00", "Design review"),
            timed(personal, mon, "18:30", "19:30", "Gym"),
            timed(work, mon.plusDays(1), "09:00", "09:15", "Standup"),
            timed(family, mon.plusDays(1), "16:00", "17:00", "Piano lesson"),
            timed(work, mon.plusDays(2), "09:00", "09:15", "Standup"),
            timed(work, mon.plusDays(2), "11:00", "12:00", "1:1 with Priya"),
            timed(personal, mon.plusDays(2), "18:30", "19:30", "Gym"),
            timed(work, mon.plusDays(3), "09:00", "09:15", "Standup"),
            timed(personal, mon.plusDays(3), "12:30", "13:30", "Lunch with Sam", location = "Blue Door Cafe"),
            timed(family, mon.plusDays(3), "19:00", "21:00", "Parents' evening"),
            timed(work, mon.plusDays(4), "09:00", "09:15", "Standup"),
            timed(work, mon.plusDays(4), "15:00", "16:00", "Sprint demo"),
            timed(family, mon.plusDays(4), "19:30", "22:00", "Dinner at Nonna's"),
            allDay(family, mon.plusDays(5), "Camping trip", days = 2),
            timed(personal, mon.plusDays(5), "08:00", "09:00", "Farmers market"),
            timed(family, mon.plusDays(6), "10:00", "11:30", "Soccer game"),
        )
    }

    /** The week plus events earlier and later, for the month and agenda views. */
    private fun monthEvents(): List<DisplayEvent> {
        val mon = thisMonday()
        return weekEvents() + listOf(
            timed(work, mon.minusDays(7), "10:00", "11:00", "Quarterly planning"),
            timed(personal, mon.minusDays(5), "18:30", "19:30", "Gym"),
            allDay(family, mon.minusDays(3), "Mia's birthday"),
            timed(personal, mon.minusDays(9), "20:00", "22:00", "Book club"),
            timed(work, mon.plusDays(8), "14:00", "15:00", "Customer call"),
            timed(personal, mon.plusDays(9), "18:30", "19:30", "Gym"),
            allDay(work, mon.plusDays(10), "Team offsite", days = 2),
            timed(family, mon.plusDays(13), "09:00", "10:00", "Dentist"),
            timed(personal, mon.plusDays(15), "19:00", "21:00", "Concert"),
        )
    }

    private fun byDay(events: List<DisplayEvent>): Map<Int, List<DisplayEvent>> {
        val out = mutableMapOf<Int, MutableList<DisplayEvent>>()
        for (ev in events) {
            val occ = (ev as DisplayEvent.Room).occurrence
            var d = occ.startDay
            while (d <= occ.endDay) {
                out.getOrPut(d) { mutableListOf() } += ev
                d = code(dateOf(d).plusDays(1))
            }
        }
        return out.mapValues { it.value.sortedBy { e -> e.startTs } }
    }

    private fun plannerState() = PlannerWeekState(
        eventsByDayCode = byDay(weekEvents()).mapValues { it.value.toImmutableList() }.toImmutableMap(),
        rangeStartDayCode = code(thisMonday().minusDays(28)),
        rangeEndDayCode = code(thisMonday().plusDays(35)),
    )

    private fun homeState(view: ViewMode): HomeUiState {
        val today = today()
        val days = byDay(monthEvents())
        val dots = days.entries
            .groupBy { String.format(java.util.Locale.ROOT, "%04d-%02d", dateOf(it.key).year, dateOf(it.key).monthValue) }
            .mapValues { (_, entries) ->
                entries.associate { (c, evs) -> dateOf(c).dayOfMonth to evs.map { it.calendarColor }.distinct().toImmutableList() }.toImmutableMap()
            }.toImmutableMap()
        return HomeUiState(
            viewMode = view,
            viewingYear = today.year,
            viewingMonth = today.monthValue - 1,
            selectedDate = today.atStartOfDay(zone).toInstant().toEpochMilli(),
            calendars = persistentListOf(work, family, personal),
            eventDots = dots,
            loadedMonths = (-2..2).map { today.plusMonths(it.toLong()).let { m -> m.year * 12 + m.monthValue - 1 } }.toPersistentSet(),
            dayEventsCache = days.mapValues { it.value.toImmutableList() }.toImmutableMap(),
            loadedDayCodes = (-40..40).map { code(today.plusDays(it.toLong())) }.toPersistentSet(),
            plannerAnchorDayCode = code(thisMonday()),
            // Week-scale views page by week, day-scale views (Day, 3-day) by day.
            weekViewPagerPosition = if (view == ViewMode.WEEK) WeekViewUtils.CENTER_WEEK_PAGE else WeekViewUtils.CENTER_DAY_PAGE,
            weekViewSavedScrollMinutes = 8 * 60,
            firstDayOfWeek = JavaCalendar.MONDAY,
            showEventEmojis = true,
        )
    }

    @Composable
    private fun Home(view: ViewMode) {
        val week = weekEvents()
        HomeScreen(
            uiState = homeState(view),
            weekEvents = WeekEventsUiState(
                timedEvents = week.filter { !it.isAllDay }.toImmutableList(),
                allDayEvents = week.filter { it.isAllDay }.toImmutableList(),
            ),
            agendaEvents = AgendaUiState(
                events = monthEvents().filter { (it as DisplayEvent.Room).occurrence.startDay >= code(today()) }
                    .sortedBy { it.startTs }.toImmutableList(),
            ),
            plannerState = plannerState(),
            onDateSelected = {},
            onGoToToday = {},
            onSetViewingMonth = { _, _ -> },
            onClearNavigateToToday = {},
            onClearNavigateToMonth = {},
        )
    }

    private fun shoot(mode: ThemeMode, file: String, content: @Composable () -> Unit) {
        rule.setContent {
            KashCalTheme(themeMode = mode, colorSource = ColorSource.SEED, accentSeed = 0xFF3F7FD9.toInt()) {
                content()
            }
        }
        rule.waitForIdle()
        captureScreenRoboImage("../store-art/roborazzi/$file.png")
    }

    @Test fun home_planner_light() = shoot(ThemeMode.LIGHT, "home_planner_light") { Home(ViewMode.PLANNER) }
    @Test fun home_planner_dark() = shoot(ThemeMode.DARK, "home_planner_dark") { Home(ViewMode.PLANNER) }
    @Test fun home_month_light() = shoot(ThemeMode.LIGHT, "home_month_light") { Home(ViewMode.MONTH) }
    @Test
    fun calendars_light() = shoot(ThemeMode.LIGHT, "calendars_light") {
        fun cal(id: Long, account: Long, name: String, color: Long) =
            Calendar(id = id, accountId = account, caldavUrl = "https://example.test/$id/", displayName = name, color = color.toInt())
        Home(ViewMode.MONTH)
        CalendarVisibilitySheet(
            calendarGroups = listOf(
                CalendarGroup("iCloud", 1, listOf(cal(11, 1, "Family", 0xFF2E9E6A)), provider = AccountProvider.ICLOUD),
                CalendarGroup("Nextcloud", 2, listOf(cal(21, 2, "Work", 0xFF3F7FD9)), provider = AccountProvider.CALDAV),
                CalendarGroup("Fastmail", 3, listOf(cal(31, 3, "Personal", 0xFFD81B60)), provider = AccountProvider.CALDAV),
                CalendarGroup("Calendar Feeds", 4, listOf(cal(41, 4, "Public holidays", 0xFFF4B400)), provider = AccountProvider.ICS),
                CalendarGroup("Contacts", 5, listOf(cal(51, 5, "Birthdays", 0xFFEC407A)), provider = AccountProvider.CONTACTS),
            ),
            onToggleCalendar = { _, _ -> },
            onShowAll = {},
            onDismiss = {},
        )
    }

    @Test fun home_week_light() = shoot(ThemeMode.LIGHT, "home_week_light") { Home(ViewMode.WEEK) }
    @Test fun home_three_days_dark() = shoot(ThemeMode.DARK, "home_three_days_dark") { Home(ViewMode.THREE_DAYS) }
    @Test fun home_day_dark() = shoot(ThemeMode.DARK, "home_day_dark") { Home(ViewMode.DAY) }
    @Test fun home_agenda_dark() = shoot(ThemeMode.DARK, "home_agenda_dark") { Home(ViewMode.AGENDA) }

    @Test
    fun quick_add_light() = shoot(ThemeMode.LIGHT, "quick_add_light") {
        val text = "Dinner with Alex Friday 7pm at Luigi's"
        val result = QuickAddParser.parse(text, reference = today().atTime(10, 0), firstDayOfWeek = JavaCalendar.MONDAY)
        Home(ViewMode.MONTH)
        QuickAddDialog(
            textFieldState = TextFieldState(text),
            parseResult = result,
            isSaveEnabled = true,
            isSaving = false,
            onDismiss = {},
            onSave = {},
            onExpand = {},
        )
    }

    @Test
    fun event_detail_light() = shoot(ThemeMode.LIGHT, "event_detail_light") {
        val ev = weekEvents().first { it.title == "Lunch with Sam" } as DisplayEvent.Room
        Home(ViewMode.PLANNER)
        EventQuickViewSheet(
            event = ev.event.copy(description = "Catch up before Sam's trip. Bring the hiking map."),
            calendarColor = personal.color,
            calendarName = personal.displayName,
            occurrenceTs = ev.startTs,
            attendees = listOf(
                AttendeeUiModel(displayName = "Sam Rivera", bareAddress = "sam@example.test", status = AttendeeStatus.Accepted, isYou = false, isOrganizer = true, sortOrder = 0),
                AttendeeUiModel(displayName = "You", bareAddress = "you@example.test", status = AttendeeStatus.NeedsAction, isYou = true, isOrganizer = false, sortOrder = 1),
                AttendeeUiModel(displayName = "Jordan Lee", bareAddress = "jordan@example.test", status = AttendeeStatus.Tentative, isYou = false, isOrganizer = false, sortOrder = 2),
            ),
            isCurrentUserOnList = true,
            onDismiss = {},
            onEdit = {},
            onDeleteSingle = {},
        )
    }
}
