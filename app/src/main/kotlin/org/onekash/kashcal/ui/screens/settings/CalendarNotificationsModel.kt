package org.onekash.kashcal.ui.screens.settings

import androidx.compose.runtime.Immutable
import org.onekash.kashcal.data.calendar_provider.DeviceCalendar
import org.onekash.kashcal.domain.model.AccountProvider
import org.onekash.kashcal.ui.model.CalendarGroup
import org.onekash.kashcal.ui.model.toDisplaySections

/** Where a calendar notifications row's mute is stored, so its switch routes to the right write. */
enum class CalendarNotificationSource { ROOM, DEVICE }

/** One calendar in the Calendar notifications sheet; `notifies` is its switch value. */
@Immutable
data class CalendarNotificationRow(
    val source: CalendarNotificationSource,
    val id: Long,
    val name: String,
    val color: Int,
    val notifies: Boolean,
    val hidden: Boolean,
)

/** Rows under one optional sub-header, a device account's name. */
@Immutable
data class CalendarNotificationGroup(
    val subheader: String?,
    val rows: List<CalendarNotificationRow>,
)

/**
 * One headed section of the sheet; [isDevice] marks the device calendars section. [key] is unique
 * across the sheet, unlike [header]: two logins on one server share the server's name.
 */
@Immutable
data class CalendarNotificationSection(
    val header: String,
    val groups: List<CalendarNotificationGroup>,
    val isDevice: Boolean = false,
    val key: String = header,
)

/**
 * Builds the Calendar notifications sheet's sections from every calendar KashCal shows, in the
 * drawer's order: each Room account but the Offline one (from [calendarGroups], already localized
 * and sorted), one [contactsHeader] section for birthdays and anniversaries, a [deviceHeader]
 * section with a sub-header per device account, then the Offline account
 * ([AccountProvider.LOCAL]), KashCal's own calendars.
 *
 * Device calendars are listed only when device calendars are on and their id is enabled: a
 * calendar KashCal doesn't show never notifies, so a switch for it would mean nothing. Read-only
 * device calendars are listed. An empty device account name gets [deviceLocalLabel].
 */
fun buildCalendarNotificationSections(
    calendarGroups: List<CalendarGroup>,
    deviceCalendarsEnabled: Boolean,
    deviceCalendars: List<DeviceCalendar>,
    enabledDeviceIds: Set<Long>,
    hiddenDeviceIds: Set<Long>,
    mutedDeviceIds: Set<Long>,
    contactsHeader: String,
    deviceHeader: String,
    deviceLocalLabel: String,
): List<CalendarNotificationSection> {
    fun roomRows(group: CalendarGroup) = group.calendars.map { calendar ->
        CalendarNotificationRow(
            source = CalendarNotificationSource.ROOM,
            id = calendar.id,
            name = calendar.displayName,
            color = calendar.color,
            notifies = !calendar.isNotificationMuted,
            hidden = !calendar.isVisible,
        )
    }

    fun roomSection(group: CalendarGroup) = CalendarNotificationSection(
        group.accountName,
        listOf(CalendarNotificationGroup(null, roomRows(group))),
        key = "room_${group.accountId}",
    )

    val (regularGroups, contactsGroups, offlineGroups) = calendarGroups.toDisplaySections()

    val sections = regularGroups.mapTo(mutableListOf()) { roomSection(it) }
    if (contactsGroups.isNotEmpty()) {
        sections += CalendarNotificationSection(
            contactsHeader,
            listOf(CalendarNotificationGroup(null, contactsGroups.flatMap { roomRows(it) })),
            key = "contacts",
        )
    }

    val shownDevice = if (deviceCalendarsEnabled) deviceCalendars.filter { it.id in enabledDeviceIds } else emptyList()
    if (shownDevice.isNotEmpty()) {
        val groups = CalendarGroup.fromDeviceCalendars(shownDevice, writableOnly = false, localLabel = deviceLocalLabel)
            .map { group ->
                CalendarNotificationGroup(
                    subheader = group.accountName,
                    rows = group.pickerCalendars.map { picker ->
                        CalendarNotificationRow(
                            source = CalendarNotificationSource.DEVICE,
                            id = picker.id,
                            name = picker.displayName,
                            color = picker.color,
                            notifies = picker.id !in mutedDeviceIds,
                            hidden = picker.id in hiddenDeviceIds,
                        )
                    }
                )
            }
        sections += CalendarNotificationSection(deviceHeader, groups, isDevice = true, key = "device")
    }
    offlineGroups.mapTo(sections) { roomSection(it) }
    return sections
}

/**
 * Returns how many listed calendars are muted, for the Settings row's subtitle. While device
 * reminders are off ([deviceRemindersEnabled] false) every listed device calendar counts as muted,
 * whatever its own switch says.
 */
fun mutedCalendarCount(sections: List<CalendarNotificationSection>, deviceRemindersEnabled: Boolean): Int =
    sections.sumOf { section ->
        section.groups.sumOf { group ->
            if (section.isDevice && !deviceRemindersEnabled) group.rows.size
            else group.rows.count { !it.notifies }
        }
    }
