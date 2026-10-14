package org.onekash.kashcal.sync.notification

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.res.Resources
import android.icu.text.ListFormatter
import android.text.format.DateFormat
import androidx.core.app.NotificationCompat
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.first
import org.onekash.kashcal.MainActivity
import org.onekash.kashcal.R
import org.onekash.kashcal.data.preferences.KashCalDataStore
import org.onekash.kashcal.domain.changes.RecentChangeEntry
import org.onekash.kashcal.domain.changes.RecentChangeItem
import org.onekash.kashcal.domain.reader.EventReader
import org.onekash.kashcal.sync.model.ChangeType
import org.onekash.kashcal.ui.components.RecentChangeFormatter
import org.onekash.kashcal.ui.components.generateSnackbarMessage
import org.onekash.kashcal.util.DateTimeUtils
import javax.inject.Inject
import javax.inject.Singleton

/** What a change notification's tap opens: the row's quick view, or the sheet when null. */
data class ChangeTap(val recentChangeId: Long?)

/**
 * Posts the change notification for one sync's Recent changes, worded like the Recent changes
 * rows.
 *
 * One change names the event and what changed, and its tap opens that row; two or three list one
 * line each; more give the count and the calendars involved. Those taps open the Recent changes
 * sheet.
 */
@Singleton
class ChangeNotifier @Inject constructor(
    @ApplicationContext private val context: Context,
    private val channels: ChangeNotificationChannels,
    private val dataStore: KashCalDataStore,
    private val eventReader: EventReader,
    private val inviteNotifier: InviteNotifier
) {
    companion object {
        const val ACTION_SHOW_CHANGES = "org.onekash.kashcal.SHOW_RECENT_CHANGES"
        const val EXTRA_RECENT_CHANGE_ID = "recent_change_id"

        /** Batches up to this size list one line per change. */
        internal const val MAX_LISTED_CHANGES = 3

        private const val REQUEST_CODE_OPEN = 300

        /** `EXTRA_RECENT_CHANGE_ID` for a tap that opens the sheet. */
        private const val NO_ROW = -1L

        /** Returns the tap a change notification's [intent] carries, or null for another intent. */
        fun parseTap(intent: Intent?): ChangeTap? {
            if (intent?.action != ACTION_SHOW_CHANGES) return null
            val id = intent.getLongExtra(EXTRA_RECENT_CHANGE_ID, NO_ROW)
            return ChangeTap(id.takeIf { it != NO_ROW })
        }
    }

    /**
     * Posts the notification for the rows recorded at [batchAt] and returns true when the batch
     * is announced, so the caller skips the in-app snackbar.
     *
     * Returns false, posting nothing, when the setting is off, the notification can't show, or no
     * row recorded at [batchAt] is left in the log. Rows of muted calendars
     * ([org.onekash.kashcal.data.db.entity.Calendar.isNotificationMuted], read on each call) and
     * rows whose event got an invite notification since [syncStartedAt] are left out of the
     * notification, not the log. When that leaves none it returns true without posting: a muted
     * calendar wants no announcement, and an invite notification already told the user.
     */
    suspend fun notify(batchAt: Long, syncStartedAt: Long): Boolean {
        if (!dataStore.changeNotificationsEnabled.first() || !channels.canPost()) return false
        val batch = eventReader.observeRecentChanges(batchAt).first().filter { it.detectedAt == batchAt }
        if (batch.isEmpty()) return false
        val mutedCalendarIds = eventReader.getNotificationMutedCalendarIds()
        val notifying = batch.filterNot { it.calendarId in mutedCalendarIds }
        if (notifying.isEmpty()) return true
        val invited = inviteNotifier.eventsNotifiedSince(notifying.mapNotNull { it.eventId }, syncStartedAt)
        val rows = notifying.filterNot { it.eventId in invited }
        if (rows.isEmpty()) return true

        val is24Hour = DateFormat.is24HourFormat(context)
        val formatter = RecentChangeFormatter(
            context.resources, DateTimeUtils.getTimePattern(dataStore.getTimeFormat(), is24Hour)
        )
        val content = buildChangeNotificationContent(rows, context.resources, formatter, System.currentTimeMillis())
        channels.post(build(content))
        return true
    }

    private fun build(content: ChangeNotificationContent): android.app.Notification {
        val publicVersion = NotificationCompat.Builder(context, ChangeNotificationChannels.CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(context.getString(R.string.channel_calendar_updates))
            .build()
        val builder = NotificationCompat.Builder(context, ChangeNotificationChannels.CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(content.title)
            .setContentText(content.text)
            .setSubText(content.subText)
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .setCategory(NotificationCompat.CATEGORY_EVENT)
            .setAutoCancel(true)
            .setVisibility(NotificationCompat.VISIBILITY_PRIVATE)
            .setPublicVersion(publicVersion)
            .setContentIntent(openIntent(content.singleRowId))
        if (content.lines.isNotEmpty()) {
            builder.setStyle(
                NotificationCompat.InboxStyle().also { style -> content.lines.forEach { style.addLine(it) } }
            )
        } else {
            builder.setStyle(NotificationCompat.BigTextStyle().bigText(content.text))
        }
        return builder.build()
    }

    /**
     * Returns the tap intent. FLAG_UPDATE_CURRENT replaces the extras of the intent the earlier
     * notification still holds, and the row extra is always written so the update overwrites the
     * earlier row's id; otherwise the tap would open the previous batch's row.
     */
    private fun openIntent(rowId: Long?): PendingIntent {
        val intent = Intent(context, MainActivity::class.java).apply {
            action = ACTION_SHOW_CHANGES
            putExtra(EXTRA_RECENT_CHANGE_ID, rowId ?: NO_ROW)
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        return PendingIntent.getActivity(
            context,
            REQUEST_CODE_OPEN,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
    }
}

/** A change notification's text; `singleRowId` is set only for a one-change batch. */
internal data class ChangeNotificationContent(
    val title: String,
    val text: String,
    val subText: String?,
    val lines: List<String>,
    val singleRowId: Long?
)

/** Words [rows] (one sync's changes, at least one) for the notification. */
internal fun buildChangeNotificationContent(
    rows: List<RecentChangeItem>,
    resources: Resources,
    formatter: RecentChangeFormatter,
    now: Long
): ChangeNotificationContent {
    val separator = resources.getString(R.string.recent_changes_detail_separator)
    fun changeLine(item: RecentChangeItem): String = formatter.detail(item) ?: when (item.changeType) {
        ChangeType.NEW -> resources.getString(R.string.cd_recent_change_new)
        ChangeType.MODIFIED -> resources.getString(R.string.cd_recent_change_updated)
        ChangeType.DELETED -> resources.getString(R.string.cd_recent_change_removed)
    }
    val list = ListFormatter.getInstance()

    rows.singleOrNull()?.let { item ->
        return ChangeNotificationContent(
            title = item.title,
            text = changeLine(item) + separator + formatter.whenText(item, now),
            subText = item.calendarName,
            lines = emptyList(),
            singleRowId = item.id
        )
    }
    val count = generateSnackbarMessage(rows.map { it.toEntry() }, resources).orEmpty()
    return if (rows.size <= ChangeNotifier.MAX_LISTED_CHANGES) {
        ChangeNotificationContent(
            title = count,
            text = list.format(rows.map { it.title }),
            subText = null,
            lines = rows.map { it.title + separator + changeLine(it) },
            singleRowId = null
        )
    } else {
        ChangeNotificationContent(
            title = count,
            text = list.format(rows.map { it.calendarName }.distinct()),
            subText = null,
            lines = emptyList(),
            singleRowId = null
        )
    }
}

private fun RecentChangeItem.toEntry() = RecentChangeEntry(
    calendarId = calendarId, eventUid = eventUid, instanceTs = instanceTs, eventId = eventId,
    type = changeType, title = title, startTs = startTs, endTs = endTs, isAllDay = isAllDay,
    isRecurring = isRecurring, changedFields = changedFields, previousStartTs = previousStartTs,
    previousIsAllDay = previousIsAllDay
)
