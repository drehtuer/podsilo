// SPDX-License-Identifier: GPL-3.0-or-later

package net.drehtuer.podsilo.feature.episodes

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SwipeToDismissBox
import androidx.compose.material3.SwipeToDismissBoxState
import androidx.compose.material3.SwipeToDismissBoxValue
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import net.drehtuer.podsilo.core.model.port.SwipeAction
import net.drehtuer.podsilo.core.model.port.SwipeDirection
import net.drehtuer.podsilo.core.model.port.SwipeMapping
import net.drehtuer.podsilo.core.ui.PodsiloIcon
import net.drehtuer.podsilo.core.ui.PodsiloIcons
import net.drehtuer.podsilo.core.ui.RowPadding

/** `docs/UI.md` §12.1: a swipe must pass ~40 % of the row before it commits, so a flick cannot. */
private const val COMMIT_THRESHOLD = 0.4f

/**
 * The swipe gesture on an episode row (`docs/UI.md` §5 and §12.1).
 *
 * `SwipeCommitted` and its view-model handler shipped without anything to fire them — the third
 * affordance in this project to be specified, wired at both ends, and left with no gesture in the
 * middle (after pull-to-refresh and the artwork slot). The events, the `SwipeMapping` setting, S4's
 * two dropdowns and the ledger writes were all already in place; only this was missing.
 *
 * Two rules that are easy to get wrong and are the reason this is its own file:
 *
 * 1. **The background is rendered from [mapping], never hard-coded.** S4 lets the two directions be
 *    re-mapped and swapped, so a hard-coded "Download" label would announce and show the wrong verb
 *    the moment the user changes it. Both the word and the icon come from the same value the
 *    view model will act on.
 * 2. **The row springs back rather than dismissing.** Nothing is removed from the list by a triage
 *    decision — the episode gains a ledger state and is re-rendered greyed out, or leaves the
 *    current filter. The commit therefore reacts to the settled state and snaps the row back; doing
 *    it in `confirmValueChange` fires it fourteen times per gesture (see the comment on the effect).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun SwipeableEpisodeRow(
    episode: EpisodeUi,
    mapping: SwipeMapping,
    enabled: Boolean,
    onEvent: (EpisodeListEvent) -> Unit,
    content: @Composable () -> Unit,
) {
    // An episode with no audio has nothing to download, and selection mode owns the gesture surface
    // while it is active — in both cases the row must not swipe at all rather than swipe into a
    // no-op the user will read as the app ignoring them.
    val swipeable = enabled && episode.hasEnclosure
    val rightAction = mapping.actionFor(SwipeDirection.RIGHT)
    val leftAction = mapping.actionFor(SwipeDirection.LEFT)

    // `remember`, NOT `rememberSwipeToDismissBoxState` — and the difference is a bug, not a style
    // preference. That helper is `rememberSaveable`, so the drag offset is written to saved state and
    // restored when the row comes back. Switching the filter chips detaches these rows and reattaches
    // them, and a row whose swipe had not fully settled came back **still pushed aside, with the
    // coloured panel showing**, exactly as reported.
    //
    // The worse half is invisible: `LaunchedEffect(state.currentValue)` below keys on that restored
    // value, so a row restored in a non-settled position fires `SwipeCommitted` **again** — a second
    // `PLAY` or `DOWNLOAD` for an episode the user swiped once, in an app whose triage has no undo.
    //
    // Keyed on `episodeKey` as well, so a LazyColumn slot reused for a different episode cannot
    // inherit the previous one's offset either.
    val state =
        remember(episode.episodeKey) {
            SwipeToDismissBoxState(
                initialValue = SwipeToDismissBoxValue.Settled,
                positionalThreshold = { distance -> distance * COMMIT_THRESHOLD },
            )
        }

    // THE COMMIT LIVES HERE, NOT IN `confirmValueChange`.
    //
    // The obvious implementation — do the work in `confirmValueChange` and return `false` so the row
    // springs back — fires the side effect **fourteen times for one swipe**, measured. That callback
    // is a predicate consulted repeatedly while the drag settles, not a commit hook, and vetoing the
    // change keeps it being re-asked. Fourteen ledger writes and fourteen posted actions per gesture,
    // in an app whose triage decisions have no undo.
    //
    // Reacting to `currentValue` instead fires once, when the state actually settles. `reset()` then
    // returns the row to place, which is what the design wants: a decision changes the episode's
    // state and re-renders it greyed out, it does not remove a row and leave a hole.
    LaunchedEffect(state.currentValue) {
        val direction =
            when (state.currentValue) {
                SwipeToDismissBoxValue.StartToEnd -> SwipeDirection.RIGHT
                SwipeToDismissBoxValue.EndToStart -> SwipeDirection.LEFT
                SwipeToDismissBoxValue.Settled -> null
            } ?: return@LaunchedEffect

        onEvent(EpisodeListEvent.SwipeCommitted(episode.episodeKey, direction))
        // `snapTo`, not `reset()`. `reset()` *animates* back over a few hundred milliseconds, and the
        // coloured panel with its "Download" / "Mark as played" label is visible for all of it —
        // while the list is simultaneously re-rendering the row greyed out or dropping it from the
        // current filter. That overlap is the reported flashing. Snapping makes the return
        // instantaneous, so the panel is only ever on screen while a finger is actually dragging.
        state.snapTo(SwipeToDismissBoxValue.Settled)
    }

    SwipeToDismissBox(
        state = state,
        enableDismissFromStartToEnd = swipeable && rightAction != SwipeAction.NONE,
        enableDismissFromEndToStart = swipeable && leftAction != SwipeAction.NONE,
        backgroundContent = {
            SwipeBackground(
                action =
                    when (state.dismissDirection) {
                        SwipeToDismissBoxValue.StartToEnd -> rightAction
                        SwipeToDismissBoxValue.EndToStart -> leftAction
                        SwipeToDismissBoxValue.Settled -> null
                    },
                alignment =
                    if (state.dismissDirection == SwipeToDismissBoxValue.EndToStart) {
                        Alignment.CenterEnd
                    } else {
                        Alignment.CenterStart
                    },
            )
        },
        content = { content() },
    )
}

/**
 * The panel revealed behind a swiping row.
 *
 * Carries **both an icon and the word**, because `docs/UI.md` §12.7 requires status to be legible
 * without relying on colour, and because the two actions are otherwise distinguishable only by which
 * way the finger went. Colours come from the theme's container roles rather than raw values so the
 * ≥ 3:1 contrast holds in both schemes — darkening a light-mode colour for dark mode is exactly what
 * §12.7 says is not sufficient.
 */
@Composable
private fun SwipeBackground(
    action: SwipeAction?,
    alignment: Alignment,
) {
    if (action == null || action == SwipeAction.NONE) return

    val visual =
        when (action) {
            SwipeAction.DOWNLOAD ->
                SwipeVisual(
                    MaterialTheme.colorScheme.primaryContainer,
                    MaterialTheme.colorScheme.onPrimaryContainer,
                    "Download",
                    PodsiloIcons.Download,
                )
            else ->
                SwipeVisual(
                    MaterialTheme.colorScheme.secondaryContainer,
                    MaterialTheme.colorScheme.onSecondaryContainer,
                    "Mark as played",
                    PodsiloIcons.Played,
                )
        }

    Row(
        modifier = Modifier.fillMaxSize().background(visual.colour).padding(horizontal = RowPadding),
        horizontalArrangement =
            if (alignment == Alignment.CenterEnd) Arrangement.End else Arrangement.Start,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        PodsiloIcon(visual.icon, contentDescription = null, tint = visual.onColour)
        Text(
            text = visual.label,
            style = MaterialTheme.typography.labelLarge,
            color = visual.onColour,
            modifier = Modifier.padding(start = 8.dp),
        )
    }
}

/** Named fields rather than a `Quadruple` nobody could read at the call site. */
private data class SwipeVisual(
    val colour: Color,
    val onColour: Color,
    val label: String,
    val icon: Int,
)
