package org.onekash.kashcal.sync.notification

import android.util.Log
import org.onekash.kashcal.data.db.dao.AttendeesDao
import org.onekash.kashcal.data.db.dao.CalendarsDao
import org.onekash.kashcal.data.db.entity.Account
import org.onekash.kashcal.data.db.entity.Event
import org.onekash.kashcal.domain.identity.matchesAttendee
import org.onekash.kashcal.reminder.notification.InviteNotificationManager
import org.onekash.kashcal.util.AddressNormalizer
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Fires the system notification for a pulled invite: an event where the user's attendee row
 * has PARTSTAT=NEEDS-ACTION and no `notified_at` yet.
 *
 * [AttendeesDao.replaceForEvent] owns the dedup: it keeps `notified_at` from the prior row with
 * the same canonical address. The notifier sets `notified_at` after firing so later pulls skip
 * the row.
 */
@Singleton
class InviteNotifier @Inject constructor(
    private val attendeesDao: AttendeesDao,
    private val notificationManager: InviteNotificationManager,
    private val calendarsDao: CalendarsDao
) {
    companion object {
        private const val TAG = "InviteNotifier"
    }

    /**
     * Notifies for each of [event]'s attendee rows that has `partstat = NEEDS-ACTION`, matches
     * [account] via `matchesAttendee`, and has `notified_at IS NULL`. An event with no organizer
     * name or address gets no notification. When the event's calendar is muted
     * ([org.onekash.kashcal.data.db.entity.Calendar.isNotificationMuted]) the row is marked
     * without showing, so unmuting replays nothing.
     *
     * Takes the event and account the pull already holds, to save DAO reads on the pull path; the
     * calendar is read only once a row would notify.
     */
    suspend fun notifyNew(event: Event, account: Account) {
        val rows = attendeesDao.getForEventOnce(event.id)
        var calendarMuted: Boolean? = null
        for (row in rows) {
            if (row.notifiedAt != null) continue
            if (row.partstat?.uppercase() != "NEEDS-ACTION") continue
            if (!account.matchesAttendee(row.address)) continue

            val organizerLabel = resolveOrganizerLabel(event) ?: continue

            try {
                val muted = calendarMuted
                    ?: (calendarsDao.getById(event.calendarId)?.isNotificationMuted == true)
                        .also { calendarMuted = it }
                if (!muted) {
                    notificationManager.showInvite(
                        event = event,
                        attendeeRowId = row.id,
                        organizerLabel = organizerLabel
                    )
                }
                attendeesDao.markNotified(row.id, System.currentTimeMillis())
                Log.d(TAG, "Recorded invite for event ${event.id}${if (muted) " (calendar muted, not shown)" else ""}")
            } catch (e: Exception) {
                // Notification failure must not break attendee persistence.
                Log.w(TAG, "Failed to notify invite for event ${event.id}: ${e.message}")
            }
        }
    }

    /**
     * Cancels [eventId]'s invite notifications. Called once the user's RSVP is recorded, from
     * any in-app surface, so the system notification clears.
     */
    suspend fun cancelForEvent(eventId: Long) {
        val rows = attendeesDao.getForEventOnce(eventId)
        for (row in rows) {
            notificationManager.cancelForEvent(eventId, row.id)
        }
    }

    /**
     * Returns the events among [eventIds] with an attendee row marked notified at or after
     * [since]: [notifyNew] marks the row it notified for (an imported invitation and a muted
     * calendar's invitation are marked too, with nothing shown). Empty when invite notifications
     * are off: [notifyNew] marks a row even when the manager skipped the notification.
     */
    suspend fun eventsNotifiedSince(eventIds: Collection<Long>, since: Long): Set<Long> {
        if (!notificationManager.areNotificationsEnabled()) return emptySet()
        return attendeesDao.getEventIdsNotifiedSince(since).toSet().intersect(eventIds.toSet())
    }

    private fun resolveOrganizerLabel(event: Event): String? {
        event.organizerName?.takeIf { it.isNotBlank() }?.let { return it }
        return event.organizerEmail?.let { AddressNormalizer.stripMailto(it) }
            ?.takeIf { it.isNotBlank() }
    }
}
