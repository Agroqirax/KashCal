package org.onekash.kashcal.ui.components

import android.content.Intent
import android.net.Uri
import androidx.activity.compose.BackHandler
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.ModalBottomSheetProperties
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringArrayResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.launch
import org.onekash.kashcal.R
import org.onekash.kashcal.domain.whatsnew.ReleaseNote
import org.onekash.kashcal.ui.components.hub.AccountAvatar

/** Test tag on the box that holds the pager; its height is the tallest page's. */
internal const val WHATS_NEW_PAGER_TAG = "whats_new_pager"

/** Test tag on each invisible sizing copy of a page, so tests can tell it from the shown page. */
internal const val WHATS_NEW_SIZER_TAG = "whats_new_sizer"

/**
 * Shows the release notes the user hasn't acknowledged as a post-update bottom sheet, one page
 * per entry of [releases] in list order (oldest first, as `WhatsNewGate` returns them).
 *
 * The user pages with swipes, Next and Back, or system back ([whatsNewBackAction]); the controls
 * each page shows come from [WhatsNewPagerControls]. Skip, Done, swiping the sheet down and
 * tapping the scrim all call [onDismiss], and system back does too on the first page. A page
 * shows the parts its [ReleaseNote] sets: illustration, body, bullets, caption, a button that
 * opens a URL, and the "Find it in" path, which starts at the avatar showing [userInitials].
 * Illustrations ([WhatsNewHeroArt]) word their sample times with [timePattern] and play only on
 * the page the pager has settled on.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun WhatsNewBanner(
    releases: List<ReleaseNote>,
    userInitials: String,
    timePattern: String,
    onDismiss: () -> Unit,
) {
    val context = LocalContext.current
    val motionEnabled = remember { heroMotionEnabled(context.contentResolver) }
    val now = remember { System.currentTimeMillis() }
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val pagerState = rememberPagerState(pageCount = { releases.size })
    val scope = rememberCoroutineScope()
    val page = pagerState.currentPage
    val controls = WhatsNewPagerControls.at(page, releases.size)
    // Steps from the page a running scroll is heading to: currentPage only moves past halfway,
    // so a step from it mid-animation would land on the wrong page.
    fun step(delta: Int) {
        scope.launch { pagerState.animateScrollToPage(pagerState.targetPage + delta) }
    }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        // Back goes through the BackHandler below, so it can page back before it closes.
        properties = ModalBottomSheetProperties(shouldDismissOnBackPress = false),
    ) {
        BackHandler {
            when (whatsNewBackAction(pagerState.targetPage)) {
                WhatsNewBackAction.PREVIOUS_PAGE -> step(-1)
                WhatsNewBackAction.DISMISS -> onDismiss()
            }
        }

        Column(
            modifier = Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(bottom = 24.dp),
        ) {
            if (controls.showDots) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 8.dp),
                    contentAlignment = Alignment.CenterEnd,
                ) {
                    HideableTextButton(
                        text = stringResource(R.string.whats_new_skip),
                        visible = controls.showSkip,
                        onClick = onDismiss,
                    )
                }
            }

            // Every page is laid out once, invisible and inert, under the pager so the box takes
            // the tallest page's height and the sheet keeps one height while paging.
            Box(modifier = Modifier.testTag(WHATS_NEW_PAGER_TAG)) {
                releases.forEach { release ->
                    WhatsNewPage(
                        release = release,
                        userInitials = userInitials,
                        timePattern = timePattern,
                        now = now,
                        motionEnabled = motionEnabled,
                        playing = false,
                        measureOnly = true,
                    )
                }
                HorizontalPager(
                    state = pagerState,
                    modifier = Modifier.matchParentSize(),
                    verticalAlignment = Alignment.Top,
                ) { index ->
                    WhatsNewPage(
                        release = releases[index],
                        userInitials = userInitials,
                        timePattern = timePattern,
                        now = now,
                        motionEnabled = motionEnabled,
                        playing = index == pagerState.settledPage,
                        measureOnly = false,
                    )
                }
            }

            if (controls.showDots) {
                PageDots(page = page, count = releases.size)
            } else {
                Spacer(modifier = Modifier.height(24.dp))
            }

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                HideableTextButton(
                    text = stringResource(R.string.action_back),
                    visible = controls.showBack,
                    onClick = { step(-1) },
                )
                Button(
                    onClick = {
                        if (controls.isLast) onDismiss() else step(1)
                    },
                ) {
                    Text(
                        text = stringResource(if (controls.isLast) R.string.action_done else R.string.whats_new_next),
                    )
                }
            }
        }
    }
}

/** A text button that, when not [visible], keeps its space but can't be seen, read or tapped. */
@Composable
private fun HideableTextButton(text: String, visible: Boolean, onClick: () -> Unit) {
    TextButton(
        onClick = onClick,
        enabled = visible,
        modifier = if (visible) Modifier else Modifier.alpha(0f).clearAndSetSemantics {},
    ) {
        Text(text = text, style = MaterialTheme.typography.labelLarge)
    }
}

/**
 * One page: illustration, then [ReleaseSection], then the "Find it in" path; the illustration
 * animates while [playing]. A [measureOnly] copy only sizes the pager: it is invisible, hidden
 * from TalkBack, draws an empty illustration frame (its height is fixed) and has no buttons.
 */
@Composable
private fun WhatsNewPage(
    release: ReleaseNote,
    userInitials: String,
    timePattern: String,
    now: Long,
    motionEnabled: Boolean,
    playing: Boolean,
    measureOnly: Boolean,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier
            .then(
                if (measureOnly) {
                    Modifier.alpha(0f).clearAndSetSemantics { testTag = WHATS_NEW_SIZER_TAG }
                } else {
                    Modifier
                },
            )
            .fillMaxWidth()
            .padding(horizontal = 24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        HeroFrame {
            val hero = release.hero
            if (measureOnly) {
                // Nothing to draw: the frame's height is fixed.
            } else if (hero == null) {
                Icon(
                    imageVector = Icons.Default.Favorite,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(56.dp),
                )
            } else {
                WhatsNewHeroArt(
                    hero = hero,
                    timePattern = timePattern,
                    now = now,
                    playing = playing,
                    motionEnabled = motionEnabled,
                )
            }
        }
        Spacer(modifier = Modifier.height(20.dp))
        ReleaseSection(release, measureOnly = measureOnly)
        if (release.findInRes.isNotEmpty()) {
            FindItIn(labels = release.findInRes.map { stringResource(it) }, userInitials = userInitials)
        }
    }
}

/** The fixed-height tinted frame every page's illustration sits in, so titles line up. */
@Composable
private fun HeroFrame(content: @Composable () -> Unit) {
    val container = MaterialTheme.colorScheme.primaryContainer
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(188.dp)
            .clip(RoundedCornerShape(28.dp))
            .background(Brush.verticalGradient(listOf(container, container.copy(alpha = 0.45f)))),
        contentAlignment = Alignment.Center,
    ) {
        content()
    }
}

/**
 * The menu path to the announced option: the avatar that opens the hub, then each label joined by
 * a chevron, wrapping onto more lines when it doesn't fit. Read by TalkBack as one sentence.
 */
@Composable
private fun FindItIn(labels: List<String>, userInitials: String) {
    val avatarLabel = stringResource(R.string.menu_more)
    Column(
        modifier = Modifier
            .padding(top = 18.dp)
            .semantics(mergeDescendants = true) {},
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(
            text = stringResource(R.string.whats_new_find_it_in),
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(modifier = Modifier.height(8.dp))
        FlowRow(
            horizontalArrangement = Arrangement.Center,
            verticalArrangement = Arrangement.spacedBy(6.dp),
            itemVerticalAlignment = Alignment.CenterVertically,
        ) {
            AccountAvatar(
                initials = userInitials,
                size = 26.dp,
                fontSize = 10.sp,
                modifier = Modifier.semantics { contentDescription = avatarLabel },
            )
            labels.forEachIndexed { index, label ->
                // The chevron travels with the chip after it, so no line ends on a chevron.
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        imageVector = Icons.AutoMirrored.Filled.KeyboardArrowRight,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(18.dp),
                    )
                    PathChip(label = label, current = index == labels.lastIndex)
                }
            }
        }
    }
}

@Composable
private fun PathChip(label: String, current: Boolean) {
    Surface(
        shape = RoundedCornerShape(8.dp),
        color = if (current) {
            MaterialTheme.colorScheme.primaryContainer
        } else {
            MaterialTheme.colorScheme.surfaceContainerHighest
        },
        contentColor = if (current) {
            MaterialTheme.colorScheme.onPrimaryContainer
        } else {
            MaterialTheme.colorScheme.onSurface
        },
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelLarge,
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 5.dp),
        )
    }
}

/** The page position as dots, the current one stretched; TalkBack reads "Page N of M". */
@Composable
private fun PageDots(page: Int, count: Int) {
    val description = stringResource(R.string.whats_new_page_of, page + 1, count)
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 16.dp)
            .semantics { contentDescription = description },
        horizontalArrangement = Arrangement.spacedBy(6.dp, Alignment.CenterHorizontally),
    ) {
        repeat(count) { index ->
            val current = index == page
            val width by animateDpAsState(if (current) 24.dp else 8.dp, label = "dot")
            Box(
                modifier = Modifier
                    .height(8.dp)
                    .width(width)
                    .clip(CircleShape)
                    .background(
                        if (current) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outlineVariant,
                    ),
            )
        }
    }
}

@Composable
private fun ReleaseSection(release: ReleaseNote, measureOnly: Boolean) {
    val context = LocalContext.current
    Column(
        modifier = Modifier.fillMaxWidth(),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(
            text = stringResource(release.titleRes),
            style = MaterialTheme.typography.headlineSmall,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.onSurface,
            textAlign = TextAlign.Center,
        )

        if (release.bodyRes != 0) {
            Spacer(modifier = Modifier.height(10.dp))
            Text(
                text = stringResource(release.bodyRes),
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
            )
        }

        if (release.bulletsRes != 0) {
            Spacer(modifier = Modifier.height(8.dp))
            stringArrayResource(release.bulletsRes).forEach { line ->
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 4.dp),
                    verticalAlignment = Alignment.Top,
                    horizontalArrangement = Arrangement.Start,
                ) {
                    Text(
                        text = "•",
                        style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.primary,
                    )
                    Spacer(modifier = Modifier.width(12.dp))
                    Text(
                        text = line,
                        style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                }
            }
        }

        if (release.captionRes != 0) {
            Spacer(modifier = Modifier.height(24.dp))
            Text(
                text = stringResource(release.captionRes),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
            )
        }

        if (release.actionLabelRes != 0 && release.actionUrlRes != 0) {
            Spacer(modifier = Modifier.height(12.dp))
            val url = stringResource(release.actionUrlRes)
            if (measureOnly) {
                // A disabled Button still reports a click action, so a sizing copy only holds
                // the button's space.
                Spacer(modifier = Modifier.fillMaxWidth().height(50.dp))
            } else {
                Button(
                    onClick = {
                        context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
                    },
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(50.dp),
                ) {
                    Text(text = stringResource(release.actionLabelRes))
                }
            }
        }
    }
}
