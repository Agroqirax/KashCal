package org.onekash.kashcal.data.calendar_provider

import androidx.compose.runtime.Immutable

/**
 * One reminder of one device-event occurrence: the next to arm
 * ([CalendarProviderRepository.getNextUpcomingReminder]) or one due on a wake-up
 * ([CalendarProviderRepository.getDueAndNextReminders]).
 *
 * The occurrence is keyed by (eventId, occurrenceStartTs). An Instances `_ID` can't be the key:
 * it changes with the queried range. Reminders are ordered by [cursor].
 */
@Immutable
data class UpcomingDeviceReminder(
    /** The Events row id. */
    val eventId: Long,

    /** The occurrence's start; with [eventId], the key. */
    val occurrenceStartTs: Long,

    /** Shown in the notification. */
    val title: String,

    val location: String?,

    val isAllDay: Boolean,

    /** Minutes before the start; negative means after. */
    val reminderMinutes: Int,

    /**
     * When the alarm fires: [occurrenceStartTs] minus [reminderMinutes] for a timed event; for an
     * all-day event, local midnight of the day minus [reminderMinutes].
     */
    val triggerTime: Long,

    /** The event's color override if set, else the calendar's color. */
    val calendarColor: Int,

    val calendarId: Long
)

/** Where this reminder sits in the [DeviceReminderCursor] order. */
val UpcomingDeviceReminder.cursor: DeviceReminderCursor
    get() = DeviceReminderCursor(triggerTime, eventId, occurrenceStartTs)

/**
 * A position in the order of device reminders: by trigger time, then event id, then occurrence
 * start. Reminders due at the same instant still have a fixed order, so after one fires the next
 * due ones are exactly those after it.
 */
data class DeviceReminderCursor(
    val triggerTime: Long,
    val eventId: Long,
    val occurrenceStartTs: Long,
) : Comparable<DeviceReminderCursor> {
    override fun compareTo(other: DeviceReminderCursor): Int =
        compareValuesBy(this, other, { it.triggerTime }, { it.eventId }, { it.occurrenceStartTs })
}

/**
 * What [CalendarProviderRepository.getDueAndNextReminders] found in one read: the reminders due
 * now, the next one to arm, and whether more were due than [due] holds. After an overflow, [next]
 * is the first due reminder left out, so the next delivery carries on from it.
 */
data class DeviceReminderBatch(
    val due: List<UpcomingDeviceReminder>,
    val next: UpcomingDeviceReminder?,
    val overflowed: Boolean,
) {
    companion object {
        val EMPTY = DeviceReminderBatch(emptyList(), null, overflowed = false)
    }
}
