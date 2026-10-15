package org.onekash.kashcal.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalLocale
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.paneTitle
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import kotlinx.collections.immutable.ImmutableList
import org.onekash.kashcal.R
import org.onekash.kashcal.data.db.entity.Event
import org.onekash.kashcal.domain.model.DisplayEvent
import org.onekash.kashcal.util.DateTimeUtils
import java.text.SimpleDateFormat
import java.util.Date

/**
 * Shows a bottom sheet of every event on a day tapped in the full-height month view (in place
 * of the month view's `DayEventsPager`) or the Planner, with a New event button for that day.
 * It always opens at full height, so it doesn't stop half way and doesn't change size when the
 * day's events arrive after it opened (`DayEventsSheetSizeTest`).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun DayEventsSheet(
    dateMs: Long,
    events: ImmutableList<DisplayEvent>,
    showEventEmojis: Boolean,
    timePattern: String,
    onEventClick: (Event, Long?) -> Unit,
    onDeviceEventClick: (DisplayEvent.Device) -> Unit,
    onCreateEvent: (Long) -> Unit,
    onDismiss: () -> Unit,
    attendeesByEventId: Map<Long, List<org.onekash.kashcal.ui.components.attendees.AttendeeUiModel>> = emptyMap()
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)

    val locale = LocalLocale.current.platformLocale
    val dateLabel = SimpleDateFormat(DateTimeUtils.localizedPattern("yEEEEMMMMd"), locale)
        .format(Date(dateMs))

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        modifier = Modifier.fillMaxHeight()
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 16.dp)
                .padding(bottom = 16.dp)
                // Announce the sheet (by its date) when it opens.
                .semantics { paneTitle = dateLabel }
        ) {
            Text(
                text = dateLabel,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
                modifier = Modifier
                    .padding(bottom = 12.dp)
                    .semantics { heading() }
            )

            if (events.isEmpty()) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 32.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = stringResource(R.string.day_events_no_events),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            } else {
                // Takes only the room its events need, so New event follows the last one; a long
                // day scrolls here and keeps New event in view.
                LazyColumn(
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                    modifier = Modifier.weight(1f, fill = false)
                ) {
                    items(events, key = { event ->
                        when (event) {
                            is DisplayEvent.Room -> "room-${event.event.id}-${event.occurrence.startTs}"
                            is DisplayEvent.Device -> "device-${event.instance.instanceId}-${event.startTs}"
                        }
                    }) { displayEvent ->
                        val isPast = DateTimeUtils.isEventPast(
                            displayEvent.endTs, displayEvent.endDay, displayEvent.isAllDay
                        )

                        val rowAttendees = (displayEvent as? DisplayEvent.Room)
                            ?.let { attendeesByEventId[it.event.id] }
                            .orEmpty()
                        EventCard(
                            displayEvent = displayEvent,
                            isPast = isPast,
                            selectedDate = dateMs,
                            showEventEmojis = showEventEmojis,
                            timePattern = timePattern,
                            attendees = rowAttendees,
                            onClick = {
                                when (displayEvent) {
                                    is DisplayEvent.Room -> onEventClick(
                                        displayEvent.event,
                                        displayEvent.occurrence.startTs
                                    )
                                    is DisplayEvent.Device -> onDeviceEventClick(displayEvent)
                                }
                            }
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(8.dp))

            TextButton(
                onClick = { onCreateEvent(dateMs) },
                modifier = Modifier.fillMaxWidth()
            ) {
                Row(
                    horizontalArrangement = Arrangement.Center,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(
                        Icons.Default.Add,
                        contentDescription = null,
                        modifier = Modifier.padding(end = 4.dp)
                    )
                    Text(stringResource(R.string.shortcut_new_event))
                }
            }
        }
    }
}
