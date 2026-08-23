// SPDX-License-Identifier: GPL-3.0-or-later

package net.drehtuer.podsilo.core.ui

import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.calculateEndPadding
import androidx.compose.foundation.layout.calculateStartPadding
import androidx.compose.foundation.layout.systemGestures
import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

// The spacing invariants from `docs/UI.adoc` §17 and §19, in one place. They were duplicated in
// :feature:episodes and :feature:settings before this module existed, which is exactly the drift
// §17 exists to prevent: two screens that disagree about a row's height read as two apps.

/** Horizontal padding for every row and banner. */
val RowPadding = 16.dp

/** The accessibility floor. A control below it is a bug, not a style choice (`docs/UI.adoc` §12.12). */
val MinTouchTarget = 48.dp

/** Episode rows are two lines plus metadata; anything shorter crowds the triage buttons. */
val MinRowHeight = 72.dp

/**
 * Vertical breathing room around a filter-chip row.
 *
 * Load-bearing rather than cosmetic: [MinTouchTarget] grows each chip to the accessibility floor,
 * and without padding of its own the row hands that grown height straight to its neighbours — the
 * crowding half of issue #48. Here rather than in one screen because S1 and S2 both have such a row,
 * and two screens disagreeing about it is the drift this file exists to prevent.
 */
val ChipRowPadding = 4.dp

/** Feed artwork, and therefore the podcast row's minimum height. */
val ArtworkSize = 56.dp

/** §19: rows stop reading as one thing when they stretch, so cap and centre the column. */
val MaxContentWidth = 600.dp

/**
 * The gutter that keeps a swipeable list's gesture surface off the screen's edges (issue #92).
 *
 * A row that reaches the physical edge competes with the system's own horizontal gestures — back
 * from either side, and the app-switch drag along the bottom — and the loser is the user, whose
 * attempt to leave the app is read as *Mark as played* on whatever row their finger crossed. In an
 * app whose triage decisions are posted to a shared server, that is not a cosmetic collision.
 *
 * 16 dp is the floor, not the answer: [gutterFor] takes whatever the device reports as its own
 * gesture inset when that is larger. The rows give up their own horizontal padding in exchange, so
 * the content grid lands where it always did on a device whose inset is the usual 16 dp.
 */
val ListGutter = 16.dp

/**
 * The gutter to inset a gesture-bearing list by, given what the device reserves for itself.
 *
 * Separate from the composable that reads the insets so it can be tested as arithmetic — the whole
 * rule is "never narrower than the design grid, never narrower than the system's own strip".
 */
fun gutterFor(
    systemGestureInset: Dp,
    minimum: Dp = ListGutter,
): Dp = maxOf(systemGestureInset, minimum)

/**
 * [gutterFor] applied to the current window's gesture insets, as a [PaddingValues] for a
 * `LazyColumn`'s `contentPadding`.
 *
 * `systemGestures`, not `safeGestures` or `navigationBars`: it is the one inset that describes where
 * the system will take a horizontal drag out of the app's hands, which is exactly the collision
 * issue #92 reported. Each side is read separately because a device held in landscape reserves them
 * asymmetrically.
 */
@Composable
fun listGutterPadding(): PaddingValues {
    val insets = WindowInsets.systemGestures.asPaddingValues()
    val direction = LocalLayoutDirection.current
    return PaddingValues(
        start = gutterFor(insets.calculateStartPadding(direction)),
        end = gutterFor(insets.calculateEndPadding(direction)),
    )
}

/**
 * The part of the list gutter a chrome row's own [RowPadding] does not already provide.
 *
 * Banners, filter chips and the mark-all row sit *outside* the list, so they keep their 16 dp
 * padding while the list is inset by [listGutterPadding] — which on a device reserving more than
 * 16 dp leaves the chrome on one grid and the rows on another, a step you can see between the chip
 * row and the first row under it. Found on the emulator, which reports 30 dp; invisible in the JVM
 * tests, where the reported inset is 0 and the two grids coincide.
 *
 * Applied to the container rather than to each banner, and zero whenever the gutter is the 16 dp
 * floor — so on a device that reserves nothing, this changes no pixel.
 */
@Composable
fun chromeGutterPadding(): PaddingValues {
    val gutter = listGutterPadding()
    val direction = LocalLayoutDirection.current
    return PaddingValues(
        start = chromeGutterFor(gutter.calculateStartPadding(direction)),
        end = chromeGutterFor(gutter.calculateEndPadding(direction)),
    )
}

/**
 * What a chrome row needs *added* to its own [RowPadding] to reach [gutter] — never negative.
 *
 * Split out for the same reason as [gutterFor]: it is arithmetic, and arithmetic is worth pinning
 * with a test rather than a screenshot.
 */
fun chromeGutterFor(gutter: Dp): Dp = (gutter - RowPadding).coerceAtLeast(0.dp)
