package org.onekash.kashcal.ui.screens.settings

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.onekash.kashcal.data.calendar_provider.DeviceCalendar
import org.onekash.kashcal.data.db.entity.Account
import org.onekash.kashcal.data.db.entity.Calendar
import org.onekash.kashcal.domain.model.AccountProvider
import org.onekash.kashcal.ui.model.CalendarGroup

/**
 * Tests [buildCalendarNotificationSections] and [mutedCalendarCount]: the sheet lists every
 * calendar KashCal shows in the drawer's order (the Offline calendars last), with the right
 * switch value and Hidden line, and the Settings subtitle counts muted calendars of both kinds
 * among those listed, every device calendar counting as muted while device reminders are off.
 */
class CalendarNotificationsModelTest {

    private val local = Account(id = 1, provider = AccountProvider.LOCAL, email = "local")
    private val icloud = Account(id = 2, provider = AccountProvider.ICLOUD, email = "alex@example.test", displayName = "alex@example.test")
    private val feeds = Account(id = 3, provider = AccountProvider.ICS, email = "ics")
    private val birthdays = Account(id = 4, provider = AccountProvider.CONTACTS, email = "birthdays", displayName = "Birthdays")
    private val anniversaries = Account(id = 5, provider = AccountProvider.CONTACTS, email = "anniversaries", displayName = "Anniversaries")

    private fun calendar(id: Long, accountId: Long, name: String, muted: Boolean = false, visible: Boolean = true) =
        Calendar(
            id = id, accountId = accountId, caldavUrl = "cal://$id", displayName = name, color = id.toInt(),
            isNotificationMuted = muted, isVisible = visible
        )

    private val roomGroups = CalendarGroup.fromCalendarsAndAccounts(
        calendars = listOf(
            calendar(10, local.id, "My calendar"),
            calendar(20, icloud.id, "Work", muted = true),
            calendar(21, icloud.id, "Home", visible = false),
            calendar(30, feeds.id, "US Holidays", muted = true),
            calendar(40, birthdays.id, "Birthdays"),
            calendar(50, anniversaries.id, "Anniversaries"),
        ),
        accounts = listOf(local, icloud, feeds, birthdays, anniversaries),
        localLabel = "Offline",
        icsLabel = "Calendar Feeds",
    )

    private fun device(id: Long, account: String, name: String, writable: Boolean = true) = DeviceCalendar(
        id = id, displayName = name, color = id.toInt(), accountName = account, accountType = "org.example",
        visible = true, accessLevel = if (writable) 700 else 200
    )

    private val deviceCalendars = listOf(
        device(100, "alex@gmail.example", "alex@gmail.example"),
        device(101, "alex@gmail.example", "Holidays in United States", writable = false),
        device(102, "", "Phone"),
        device(103, "alex@gmail.example", "Not enabled"),
    )

    private fun sections(
        deviceCalendarsEnabled: Boolean = true,
        enabled: Set<Long> = setOf(100, 101, 102),
        hidden: Set<Long> = setOf(102),
        muted: Set<Long> = setOf(101),
        devices: List<DeviceCalendar> = deviceCalendars,
    ) = buildCalendarNotificationSections(
        calendarGroups = roomGroups,
        deviceCalendarsEnabled = deviceCalendarsEnabled,
        deviceCalendars = devices,
        enabledDeviceIds = enabled,
        hiddenDeviceIds = hidden,
        mutedDeviceIds = muted,
        contactsHeader = "Contacts",
        deviceHeader = "Device calendars",
        deviceLocalLabel = "On this device",
    )

    @Test
    fun `sections follow the drawer's order with the Offline calendars last`() {
        assertEquals(
            listOf("alex@example.test", "Calendar Feeds", "Contacts", "Device calendars", "Offline"),
            sections().map { it.header }
        )
    }

    @Test
    fun `Offline still comes last with device calendars off`() {
        assertEquals(
            listOf("alex@example.test", "Calendar Feeds", "Contacts", "Offline"),
            sections(deviceCalendarsEnabled = false).map { it.header }
        )
    }

    private fun sectionsOf(calendars: List<Calendar>, accounts: List<Account>) = buildCalendarNotificationSections(
        calendarGroups = CalendarGroup.fromCalendarsAndAccounts(calendars, accounts, localLabel = "Offline", icsLabel = "Calendar Feeds"),
        deviceCalendarsEnabled = false,
        deviceCalendars = emptyList(),
        enabledDeviceIds = emptySet(),
        hiddenDeviceIds = emptySet(),
        mutedDeviceIds = emptySet(),
        contactsHeader = "Contacts",
        deviceHeader = "Device calendars",
        deviceLocalLabel = "On this device",
    )

    @Test
    fun `only Offline calendars give one Offline section`() {
        assertEquals(listOf("Offline"), sectionsOf(listOf(calendar(10, local.id, "My calendar")), listOf(local)).map { it.header })
    }

    @Test
    fun `without Offline calendars the order is unchanged`() {
        assertEquals(
            listOf("alex@example.test", "Contacts"),
            sectionsOf(
                listOf(calendar(20, icloud.id, "Work"), calendar(40, birthdays.id, "Birthdays")),
                listOf(icloud, birthdays),
            ).map { it.header }
        )
    }

    @Test
    fun `an account that is only named Offline keeps its place`() {
        val named = Account(id = 6, provider = AccountProvider.CALDAV, email = "o@example.test", displayName = "Offline")
        val headers = sectionsOf(
            listOf(calendar(10, local.id, "My calendar"), calendar(60, named.id, "Shared"), calendar(20, icloud.id, "Work")),
            listOf(local, named, icloud),
        )
        assertEquals(listOf("room_${icloud.id}", "room_${named.id}", "room_${local.id}"), headers.map { it.key })
    }

    @Test
    fun `contacts calendars share one section without sub-headers`() {
        val contacts = sections().single { it.header == "Contacts" }
        assertEquals(listOf(null), contacts.groups.map { it.subheader })
        assertEquals(listOf("Anniversaries", "Birthdays"), contacts.groups.single().rows.map { it.name }.sorted())
    }

    @Test
    fun `each row says whether it notifies and whether it is hidden`() {
        val rows = sections().flatMap { s -> s.groups.flatMap { it.rows } }.associateBy { it.name }
        assertEquals(false, rows.getValue("Work").notifies)
        assertEquals(true, rows.getValue("Home").notifies)
        assertEquals(true, rows.getValue("Home").hidden)
        assertEquals(false, rows.getValue("Work").hidden)
        assertEquals(CalendarNotificationSource.ROOM, rows.getValue("Work").source)
        assertEquals(false, rows.getValue("Holidays in United States").notifies)
        assertEquals(true, rows.getValue("Phone").hidden)
        assertEquals(CalendarNotificationSource.DEVICE, rows.getValue("Phone").source)
        assertEquals(102L, rows.getValue("Phone").id)
    }

    @Test
    fun `device calendars are grouped by account, read-only ones included, unenabled ones left out`() {
        val device = sections().single { it.header == "Device calendars" }
        assertEquals(listOf("alex@gmail.example", "On this device"), device.groups.map { it.subheader })
        assertEquals(
            listOf("alex@gmail.example", "Holidays in United States"),
            device.groups.first().rows.map { it.name }
        )
        assertTrue(device.groups.flatMap { it.rows }.none { it.name == "Not enabled" })
    }

    @Test
    fun `the device section is absent when device calendars are off or unreadable`() {
        assertTrue(sections(deviceCalendarsEnabled = false).none { it.header == "Device calendars" })
        assertTrue(sections(devices = emptyList()).none { it.header == "Device calendars" })
    }

    @Test
    fun `the muted count covers both kinds among the listed calendars`() {
        assertEquals(3, mutedCalendarCount(sections(), deviceRemindersEnabled = true))
        assertEquals(2, mutedCalendarCount(sections(muted = emptySet()), deviceRemindersEnabled = true))
        // A muted id of a device calendar that isn't listed isn't counted.
        assertEquals(2, mutedCalendarCount(sections(muted = setOf(103)), deviceRemindersEnabled = true))
        assertEquals(2, mutedCalendarCount(sections(deviceCalendarsEnabled = false), deviceRemindersEnabled = true))
    }

    @Test
    fun `with device reminders off every listed device calendar counts as muted`() {
        // Work and US Holidays are muted Room calendars; three device calendars are listed.
        assertEquals(5, mutedCalendarCount(sections(), deviceRemindersEnabled = false))
        assertEquals(2, mutedCalendarCount(sections(deviceCalendarsEnabled = false), deviceRemindersEnabled = false))
    }

    @Test
    fun `a device mute alone counts one`() {
        val deviceOnly = buildCalendarNotificationSections(
            calendarGroups = CalendarGroup.fromCalendarsAndAccounts(
                listOf(calendar(10, local.id, "My calendar")), listOf(local), "Offline", "Calendar Feeds"
            ),
            deviceCalendarsEnabled = true,
            deviceCalendars = deviceCalendars,
            enabledDeviceIds = setOf(100),
            hiddenDeviceIds = emptySet(),
            mutedDeviceIds = setOf(100),
            contactsHeader = "Contacts",
            deviceHeader = "Device calendars",
            deviceLocalLabel = "Local",
        )
        assertEquals(1, mutedCalendarCount(deviceOnly, deviceRemindersEnabled = true))
    }

    @Test
    fun `section keys are unique even when account names repeat`() {
        val twins = CalendarGroup.fromCalendarsAndAccounts(
            listOf(calendar(60, 6, "Alex"), calendar(70, 7, "Sam")),
            listOf(
                Account(id = 6, provider = AccountProvider.CALDAV, email = "alex@dav.example.test", displayName = "dav.example.test"),
                Account(id = 7, provider = AccountProvider.CALDAV, email = "sam@dav.example.test", displayName = "dav.example.test"),
            ),
            "Offline", "Calendar Feeds",
        )
        val keys = buildCalendarNotificationSections(
            calendarGroups = twins, deviceCalendarsEnabled = true, deviceCalendars = deviceCalendars,
            enabledDeviceIds = setOf(100), hiddenDeviceIds = emptySet(), mutedDeviceIds = emptySet(),
            contactsHeader = "Contacts", deviceHeader = "Device calendars", deviceLocalLabel = "Local",
        ).map { it.key }
        assertEquals(keys.size, keys.toSet().size)
    }

    @Test
    fun `nothing muted counts zero`() {
        val none = buildCalendarNotificationSections(
            calendarGroups = CalendarGroup.fromCalendarsAndAccounts(
                listOf(calendar(10, local.id, "My calendar")), listOf(local), "Offline", "Calendar Feeds"
            ),
            deviceCalendarsEnabled = false,
            deviceCalendars = emptyList(),
            enabledDeviceIds = emptySet(),
            hiddenDeviceIds = emptySet(),
            mutedDeviceIds = emptySet(),
            contactsHeader = "Contacts",
            deviceHeader = "Device calendars",
            deviceLocalLabel = "Local",
        )
        assertEquals(0, mutedCalendarCount(none, deviceRemindersEnabled = true))
    }
}
