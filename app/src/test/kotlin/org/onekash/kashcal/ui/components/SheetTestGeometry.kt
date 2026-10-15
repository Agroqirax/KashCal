package org.onekash.kashcal.ui.components

import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.isRoot
import androidx.compose.ui.test.junit4.ComposeContentTestRule
import androidx.compose.ui.unit.IntSize

/** The height a bottom sheet test measures against: the tallest root, the sheet's own window. */
internal fun ComposeContentTestRule.sheetWindowHeight(): Float =
    onAllNodes(isRoot()).fetchSemanticsNodes().maxOf { it.size.height }.toFloat()

/** Where [node] starts, as a fraction of `sheetWindowHeight` (0 = top, 1 = bottom). */
internal fun ComposeContentTestRule.topFraction(node: SemanticsNodeInteraction): Float =
    node.fetchSemanticsNode().boundsInWindow.top / sheetWindowHeight()

/**
 * Returns the measured size of every node with a pane title; Material3 sets one on the sheet.
 * Sizes, not window bounds: bounds are clipped while the sheet slides up.
 */
internal fun ComposeContentTestRule.sheetSizes(): List<IntSize> =
    onAllNodes(SemanticsMatcher.keyIsDefined(SemanticsProperties.PaneTitle)).fetchSemanticsNodes().map { it.size }
