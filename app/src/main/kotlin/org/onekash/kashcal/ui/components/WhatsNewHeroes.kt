package org.onekash.kashcal.ui.components

import android.content.ContentResolver
import android.content.res.Resources
import android.provider.Settings
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.wrapContentHeight
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.res.colorResource
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay
import org.onekash.kashcal.R
import org.onekash.kashcal.domain.changes.RecentChangeItem
import org.onekash.kashcal.domain.whatsnew.WhatsNewHero
import org.onekash.kashcal.sync.model.ChangeType
import org.onekash.kashcal.sync.model.ChangedField
import org.onekash.kashcal.sync.notification.buildChangeNotificationContent
import org.onekash.kashcal.ui.appicon.AppIconPreset
import org.onekash.kashcal.ui.appicon.DayIcons
import org.onekash.kashcal.util.DateTimeUtils
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

/** Which frame an illustration shows while it isn't playing. */
internal enum class HeroPhase { START, END }

/**
 * Returns the frame an illustration rests on: the start, ready to play, or with motion off the
 * end, which shows the whole idea in one still.
 */
internal fun initialHeroPhase(motionEnabled: Boolean): HeroPhase =
    if (motionEnabled) HeroPhase.START else HeroPhase.END

/** Returns false when the system's "Remove animations" sets the animator duration scale to 0. */
internal fun heroMotionEnabled(resolver: ContentResolver): Boolean =
    Settings.Global.getFloat(resolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f) != 0f

/** Returns yesterday's and today's day of the month, the two dates the icon turns over between. */
internal fun heroDays(today: LocalDate): Pair<Int, Int> =
    today.minusDays(1).dayOfMonth to today.dayOfMonth

/**
 * Returns the sample Recent changes rows the illustrations show: a new event, a moved one and a
 * removed one, timed from [now] in [zone]. They are display-only; their ids reach no database.
 */
internal fun whatsNewSampleRows(resources: Resources, now: Long, zone: ZoneId): List<RecentChangeItem> {
    val today = Instant.ofEpochMilli(now).atZone(zone).toLocalDate()
    fun at(days: Long, hour: Int) = today.plusDays(days).atTime(hour, 0).atZone(zone).toInstant().toEpochMilli()
    val calendar = resources.getString(R.string.whats_new_hero_sample_calendar)
    fun row(
        id: Long,
        type: ChangeType,
        titleRes: Int,
        start: Long,
        previousStart: Long? = null,
        changed: Set<ChangedField> = emptySet(),
    ) = RecentChangeItem(
        id = id, calendarId = 0, eventUid = "", instanceTs = 0, eventId = null, changeType = type,
        title = resources.getString(titleRes), startTs = start, endTs = start + HOUR_MS,
        isAllDay = false, isRecurring = false, detectedAt = now, changedFields = changed,
        previousStartTs = previousStart, previousIsAllDay = previousStart?.let { false },
        calendarName = calendar, calendarColor = SAMPLE_CALENDAR_COLOR,
    )
    return listOf(
        row(1, ChangeType.NEW, R.string.whats_new_hero_sample_lunch, at(2, 12)),
        row(
            2, ChangeType.MODIFIED, R.string.whats_new_hero_sample_review, at(1, 16),
            previousStart = at(1, 15), changed = setOf(ChangedField.TIME),
        ),
        row(3, ChangeType.DELETED, R.string.whats_new_hero_sample_book_club, at(3, 19)),
    )
}

private const val HOUR_MS = 60 * 60 * 1000L

/** The dot color of the one sample calendar every sample row sits in. */
private const val SAMPLE_CALENDAR_COLOR = 0xFF22A06B.toInt()

/**
 * Draws [hero]'s illustration, decorative and hidden from TalkBack. It loops while [playing]
 * and [motionEnabled]; otherwise it rests on [initialHeroPhase]'s frame. Times use [timePattern];
 * sample events are placed relative to [now]. Its text ignores the font scale, as a picture's
 * would: the frame has a fixed height, and larger text would push the sample rows out of it.
 */
@Composable
internal fun WhatsNewHeroArt(
    hero: WhatsNewHero,
    timePattern: String,
    now: Long,
    playing: Boolean,
    motionEnabled: Boolean,
    modifier: Modifier = Modifier,
) {
    val density = LocalDensity.current
    CompositionLocalProvider(LocalDensity provides Density(density.density, fontScale = 1f)) {
        Box(
            modifier = modifier
                .fillMaxSize()
                .clearAndSetSemantics {},
            contentAlignment = Alignment.Center,
        ) {
            val rest = initialHeroPhase(motionEnabled)
            val play = playing && motionEnabled
            when (hero) {
                WhatsNewHero.DATE_ICON -> DateIconArt(timePattern, now, play, rest)
                WhatsNewHero.RECENT_CHANGES -> RecentChangesArt(timePattern, now, play, rest)
                WhatsNewHero.CHANGE_NOTIFICATIONS -> NotificationArt(timePattern, now, play, rest)
            }
        }
    }
}

/**
 * Runs [frames] in a loop while [play] is true, holding each for its delay in milliseconds, and
 * returns the current frame index. Not playing, it holds the first frame for [HeroPhase.START]
 * and the last for [HeroPhase.END].
 */
@Composable
private fun loopFrames(play: Boolean, rest: HeroPhase, frames: List<Long>): Int {
    val restFrame = if (rest == HeroPhase.START) 0 else frames.lastIndex
    var frame by remember { mutableIntStateOf(restFrame) }
    LaunchedEffect(play, rest) {
        frame = restFrame
        if (!play) return@LaunchedEffect
        while (true) {
            for (index in frames.indices) {
                frame = index
                delay(frames[index])
            }
        }
    }
    return frame
}

@Composable
private fun DateIconArt(timePattern: String, now: Long, play: Boolean, rest: HeroPhase) {
    val today = Instant.ofEpochMilli(now).atZone(ZoneId.systemDefault()).toLocalDate()
    val (yesterday, todayDay) = heroDays(today)
    val turned = loopFrames(play, rest, listOf(1300L, 3300L)) == 1
    val onContainer = MaterialTheme.colorScheme.onPrimaryContainer

    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(
            text = if (turned) {
                DateTimeUtils.formatTime(0, 0, timePattern)
            } else {
                DateTimeUtils.formatTime(23, 59, timePattern)
            },
            style = MaterialTheme.typography.labelLarge,
            color = onContainer.copy(alpha = 0.8f),
        )
        Spacer(modifier = Modifier.height(14.dp))
        Row(
            horizontalArrangement = Arrangement.spacedBy(22.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            NeighborIcon(onContainer)
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Box(
                    modifier = Modifier
                        .size(80.dp)
                        .clip(CircleShape)
                        .background(colorResource(R.color.ic_launcher_background)),
                ) {
                    AnimatedContent(
                        targetState = if (turned) todayDay else yesterday,
                        transitionSpec = {
                            (slideInVertically(tween(550)) { it / 5 } + fadeIn(tween(550)) + scaleIn(tween(550), 0.86f))
                                .togetherWith(
                                    slideOutVertically(tween(450)) { -it / 5 } + fadeOut(tween(450)) +
                                        scaleOut(tween(450), targetScale = 0.86f),
                                )
                        },
                        label = "day",
                    ) { day ->
                        // Composited like the icon picker's preview
                        // ([AppIconPreset.previewForegroundFor]).
                        Image(
                            painter = painterResource(DayIcons.foregroundFor(day)),
                            contentDescription = null,
                            modifier = Modifier
                                .fillMaxSize()
                                .scale(AppIconPreset.PREVIEW_FOREGROUND_SCALE),
                        )
                    }
                }
                Spacer(modifier = Modifier.height(6.dp))
                Text(
                    text = stringResource(R.string.app_name),
                    style = MaterialTheme.typography.labelMedium,
                    fontWeight = FontWeight.SemiBold,
                    color = onContainer,
                )
            }
            NeighborIcon(onContainer)
        }
    }
}

/** A blank launcher icon either side of KashCal's, so the tile reads as a home screen. */
@Composable
private fun NeighborIcon(color: Color) {
    Box(
        modifier = Modifier
            .size(52.dp)
            .clip(CircleShape)
            .background(color.copy(alpha = 0.12f)),
    )
}

@Composable
private fun RecentChangesArt(timePattern: String, now: Long, play: Boolean, rest: HeroPhase) {
    val resources = LocalResources.current
    val formatter = remember(resources, timePattern) { RecentChangeFormatter(resources, timePattern) }
    val rows = remember(resources, now) { whatsNewSampleRows(resources, now, ZoneId.systemDefault()) }
    // Frames: empty, one row, two rows, three rows, then the removed row swipes away.
    val frame = loopFrames(play, rest, listOf(400L, 380L, 380L, 2000L, 1800L))
    val visible = minOf(frame, rows.size)
    val removedGone = play && frame == 4
    // The sheet dismisses a row end to start, which is rightward in right-to-left layouts.
    val towardStart = if (LocalLayoutDirection.current == LayoutDirection.Rtl) 1 else -1

    Surface(
        shape = RoundedCornerShape(18.dp),
        color = MaterialTheme.colorScheme.surfaceContainerLow,
        shadowElevation = 3.dp,
        modifier = Modifier
            .fillMaxWidth(0.94f)
            .wrapContentHeight(unbounded = true)
            // The real row's sizes, drawn smaller so three rows fit the frame.
            .graphicsLayer {
                scaleX = 0.86f
                scaleY = 0.86f
            },
    ) {
        Column(modifier = Modifier.padding(horizontal = 14.dp, vertical = 8.dp)) {
            Text(
                text = formatter.dayHeader(now, now),
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.primary,
            )
            rows.forEachIndexed { index, item ->
                val removed = item.changeType == ChangeType.DELETED
                AnimatedVisibility(
                    visible = index < visible && !(removedGone && removed),
                    enter = fadeIn(tween(400)) + slideInVertically(tween(400)) { it / 3 },
                    // The removed row swipes away as in the sheet; the rest fade as the
                    // loop restarts.
                    exit = if (removed) {
                        slideOutHorizontally(tween(400)) { towardStart * it } + fadeOut(tween(400)) +
                            shrinkVertically(tween(400, delayMillis = 200))
                    } else {
                        fadeOut(tween(300))
                    },
                ) {
                    RecentChangeRowContent(
                        item = item,
                        whenText = formatter.whenText(item, now),
                        detail = formatter.detail(item),
                        verticalPadding = 5.dp,
                    )
                }
            }
        }
    }
}

@Composable
private fun NotificationArt(timePattern: String, now: Long, play: Boolean, rest: HeroPhase) {
    val resources = LocalResources.current
    val content = remember(resources, timePattern, now) {
        val moved = whatsNewSampleRows(resources, now, ZoneId.systemDefault())
            .first { it.changeType == ChangeType.MODIFIED }
        buildChangeNotificationContent(listOf(moved), resources, RecentChangeFormatter(resources, timePattern), now)
    }
    val clock = remember(timePattern, now) {
        DateTimeUtils.formatEventTime(now, isAllDay = false, pattern = timePattern)
    }
    val arrived = loopFrames(play, rest, listOf(500L, 4300L)) == 1
    val separator = stringResource(R.string.recent_changes_detail_separator)
    val header = listOfNotNull(
        stringResource(R.string.app_name),
        content.subText,
        stringResource(R.string.whats_new_hero_now),
    ).joinToString(separator)

    // A lock screen stays dark in either theme, like a real one.
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Brush.verticalGradient(listOf(Color(0xFF14213B), Color(0xFF0B3A3C)))),
    ) {
        Text(
            text = clock,
            color = Color.White.copy(alpha = 0.9f),
            fontSize = 40.sp,
            fontWeight = FontWeight.Light,
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .padding(bottom = 14.dp),
        )
        AnimatedVisibility(
            visible = arrived,
            enter = slideInVertically(spring(dampingRatio = 0.7f, stiffness = Spring.StiffnessLow)) { -it * 2 } +
                fadeIn(tween(200)),
            exit = fadeOut(tween(300)),
            modifier = Modifier
                .align(Alignment.TopCenter)
                .padding(14.dp),
        ) {
            Surface(shape = RoundedCornerShape(20.dp), color = Color(0xF22D3036), shadowElevation = 6.dp) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 14.dp, vertical = 11.dp),
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            painter = painterResource(R.drawable.ic_notification),
                            contentDescription = null,
                            tint = Color(0xFF82D5C8),
                            modifier = Modifier.size(14.dp),
                        )
                        Spacer(modifier = Modifier.width(6.dp))
                        Text(
                            text = header,
                            color = Color(0xFFA8ACB3),
                            fontSize = 11.sp,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        text = content.title,
                        color = Color.White,
                        fontSize = 14.sp,
                        fontWeight = FontWeight.Medium,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Text(
                        text = content.text,
                        color = Color(0xFFBFC3C9),
                        fontSize = 12.sp,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
        }
    }
}
