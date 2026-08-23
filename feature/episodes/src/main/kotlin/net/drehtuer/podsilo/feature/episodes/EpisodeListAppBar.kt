// SPDX-License-Identifier: GPL-3.0-or-later

package net.drehtuer.podsilo.feature.episodes

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.sizeIn
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import net.drehtuer.podsilo.core.ui.MinTouchTarget
import net.drehtuer.podsilo.core.ui.PodsiloIcon
import net.drehtuer.podsilo.core.ui.PodsiloIcons

/**
 * S2's app bar (`UI.adoc` §5) — **the screen shipped without one at all**, alone among the eight.
 *
 * The consequences were larger than "missing chrome" suggests: no up navigation, no feed title, the
 * screen's content beginning under the status bar, nowhere for the *Download all (n)* item to live —
 * its event, `BulkPreview` and confirmation dialog were all built and tested with nothing able to
 * emit them — and nowhere for selection mode's `n selected` bar to go, which is why #46 waits on this.
 *
 * §5's diagram labels the bar `‹ Der Podcast [filter] [activity]`. There is deliberately no `filter`
 * icon here: §18's allow-list contains none, and the filter *is* the chip row rendered directly
 * beneath. The four affordances this bar does carry — `arrow-left`, `activity`, `ellipsis-vertical`
 * and the title — are each allow-listed for S2 by name.
 *
 * Its own file rather than another private composable in `EpisodeListScreen.kt`: detekt's
 * `TooManyFunctions` flagged that file on the way in, and the split it was asking for is a real seam
 * — the screen owns the list and its chrome, this owns navigation out of the screen — not a
 * threshold to suppress. Same reasoning as `EpisodeRow.kt` and `EpisodeSwipe.kt`.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun EpisodeListAppBar(
    state: EpisodeListUiState,
    onEvent: (EpisodeListEvent) -> Unit,
) {
    // Selection mode *replaces* the bar rather than adding to it: the actions belong to the
    // selection, and leaving "back" in place beside "3 selected" invites leaving the screen when the
    // user meant to leave the mode (UI.adoc §5).
    state.selection?.let { selection ->
        SelectionAppBar(selection, onEvent)
        return
    }
    TopAppBar(
        title = {
            Text(
                // The feed URL until the first fetch supplies a title — the view model already falls
                // back to it, and "Unknown podcast" would be a worse answer than the address.
                text = state.feedTitle,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        },
        navigationIcon = {
            IconButton(
                onClick = { onEvent(EpisodeListEvent.BackClicked) },
                modifier = Modifier.sizeIn(minHeight = MinTouchTarget),
            ) {
                PodsiloIcon(PodsiloIcons.Back, contentDescription = "Back")
            }
        },
        actions = {
            // The app bar is the one place an icon-only control exists — the target is conventional
            // there and nowhere else (UI.adoc §18).
            IconButton(
                onClick = { onEvent(EpisodeListEvent.ActivityClicked) },
                modifier = Modifier.sizeIn(minHeight = MinTouchTarget),
            ) {
                PodsiloIcon(PodsiloIcons.Activity, contentDescription = "Activity")
            }
            DownloadAllOverflow(state, onEvent)
        },
    )
}

/**
 * Selection mode's bar: `n selected`, the two triage actions, *Select all*, and ✕ (`UI.adoc` §5).
 *
 * The answer to "12 new episodes, no undo, 12 swipes" — and the reason issue #46 was ordered after
 * the app bar existed at all, since there was previously nowhere to put this.
 *
 * Two details that are requirements rather than polish:
 *
 * - **The count is a live region** (`UI.adoc` §12.12): TalkBack announces `n selected` on every
 *   change, which is the only feedback a non-sighted user gets that a tap toggled anything.
 * - **Acting confirms first.** Both actions go through [EpisodeListEvent.SelectionActionRequested],
 *   which writes nothing; only the dialog's confirm button writes. That is the same safeguard
 *   *Download all* and *Mark all as played* carry, and it matters most here because a bulk
 *   *Mark as played* emits `PLAY` actions to a shared log that no undo reaches.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SelectionAppBar(
    selection: Selection,
    onEvent: (EpisodeListEvent) -> Unit,
) {
    val count = selection.keys.size
    TopAppBar(
        title = {
            Text(
                text = "$count selected",
                modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
            )
        },
        navigationIcon = {
            IconButton(
                onClick = { onEvent(EpisodeListEvent.SelectionCleared) },
                modifier = Modifier.sizeIn(minHeight = MinTouchTarget),
            ) {
                PodsiloIcon(PodsiloIcons.Close, contentDescription = "Leave selection mode")
            }
        },
        actions = {
            IconButton(
                onClick = { onEvent(EpisodeListEvent.SelectionActionRequested(EpisodeUiAction.DOWNLOAD)) },
                modifier = Modifier.sizeIn(minHeight = MinTouchTarget),
            ) {
                PodsiloIcon(PodsiloIcons.Download, contentDescription = "Download selected")
            }
            IconButton(
                onClick = { onEvent(EpisodeListEvent.SelectionActionRequested(EpisodeUiAction.MARK_AS_PLAYED)) },
                modifier = Modifier.sizeIn(minHeight = MinTouchTarget),
            ) {
                PodsiloIcon(PodsiloIcons.Played, contentDescription = "Mark selected as played")
            }
            // Scoped to the current filter — `allInFilter` is the count the view model computed from
            // the rows actually on screen, never "every episode in the feed".
            TextButton(
                onClick = { onEvent(EpisodeListEvent.SelectAllInFilter) },
                modifier = Modifier.sizeIn(minHeight = MinTouchTarget),
            ) { Text("Select all", maxLines = 1, softWrap = false) }
        },
    )
}

/**
 * The confirmation selection-mode actions commit through (`UI.adoc` §5, `decisions/0014`).
 *
 * Names the exact count, and for *Mark as played* says where the state goes — the wording is not
 * decoration: those actions reach the shared log and other clients act on them.
 */
@Composable
internal fun SelectionActionDialog(
    action: EpisodeUiAction,
    selection: Selection,
    onEvent: (EpisodeListEvent) -> Unit,
) {
    val count = selection.keys.size
    val download = action == EpisodeUiAction.DOWNLOAD
    AlertDialog(
        onDismissRequest = { onEvent(EpisodeListEvent.SelectionActionDismissed) },
        title = {
            Text(
                if (download) {
                    "Download $count ${episodeWord(count)}?"
                } else {
                    "Mark $count ${episodeWord(count)} as played?"
                },
            )
        },
        text = {
            if (!download) {
                Column {
                    Text(
                        "They stay in your download folder — Podsilo never deletes files.",
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    Text(
                        "Played state is sent to Nextcloud, so your other clients see it too.",
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
            }
        },
        confirmButton = {
            TextButton(onClick = { onEvent(EpisodeListEvent.BulkConfirmed(action, selection.keys)) }) {
                Text(if (download) "Download" else "Mark as played")
            }
        },
        dismissButton = {
            TextButton(onClick = { onEvent(EpisodeListEvent.SelectionActionDismissed) }) { Text("Cancel") }
        },
    )
}

private fun episodeWord(count: Int): String = if (count == 1) "episode" else "episodes"

/**
 * *Download all (n)* — in the overflow rather than as a button, deliberately (`UI.adoc` §5,
 * `decisions/0014`): it is a command the user issues to a set they can see, not an affordance
 * that invites itself. There is no global "download everything" anywhere, and this one is scoped to
 * one podcast.
 *
 * Rendered only when there is something to download. The view model zeroes
 * [EpisodeListUiState.downloadAllCount] on every filter but *To decide* — the only one where
 * "download all of them" means anything, since every other filter is by definition already decided —
 * and an overflow whose sole item is absent is a button that opens an empty menu.
 */
@Composable
private fun DownloadAllOverflow(
    state: EpisodeListUiState,
    onEvent: (EpisodeListEvent) -> Unit,
) {
    if (state.downloadAllCount == 0) return
    var expanded by remember { mutableStateOf(false) }

    IconButton(
        onClick = { expanded = true },
        modifier = Modifier.sizeIn(minHeight = MinTouchTarget),
    ) {
        PodsiloIcon(PodsiloIcons.Overflow, contentDescription = "More actions")
    }
    DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
        val paused = state.queueStatus as? QueueStatus.Paused
        DropdownMenuItem(
            text = { Text("Download all (${state.downloadAllCount})") },
            // Disabled *with the reason*, never silently: a greyed item that does not say why is a
            // dead end (UI.adoc §5, §12.11).
            enabled = paused == null,
            trailingIcon = paused?.let { { Text(it.shortReason, style = MaterialTheme.typography.bodySmall) } },
            onClick = {
                // Opening the menu decides nothing and this click decides nothing either: it asks the
                // view model for a preview, and only the dialog's confirm button writes
                // (decisions/0014).
                expanded = false
                onEvent(EpisodeListEvent.DownloadAllRequested)
            },
        )
    }
}

/** The banner says it in full; a menu item has room for a clause. */
private val QueueStatus.Paused.shortReason: String
    get() =
        when (cause) {
            QueueStatus.PauseCause.FOLDER_NOT_CHOSEN -> "no folder chosen"
            QueueStatus.PauseCause.FOLDER_REVOKED -> "folder unavailable"
            QueueStatus.PauseCause.DISK_FULL -> "no space left"
        }
