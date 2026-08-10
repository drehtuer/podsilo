// SPDX-License-Identifier: GPL-3.0-or-later

package net.drehtuer.podsilo.core.ui

import androidx.compose.ui.unit.dp

// The spacing invariants from `docs/UI.md` §17 and §19, in one place. They were duplicated in
// :feature:episodes and :feature:settings before this module existed, which is exactly the drift
// §17 exists to prevent: two screens that disagree about a row's height read as two apps.

/** Horizontal padding for every row and banner. */
val RowPadding = 16.dp

/** The accessibility floor. A control below it is a bug, not a style choice (`docs/UI.md` §12.12). */
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
