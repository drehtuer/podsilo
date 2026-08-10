// SPDX-License-Identifier: GPL-3.0-or-later

package net.drehtuer.podsilo.feature.episodes

import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.sizeIn
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import net.drehtuer.podsilo.core.ui.ChipRowPadding
import net.drehtuer.podsilo.core.ui.MaxContentWidth
import net.drehtuer.podsilo.core.ui.MinTouchTarget
import net.drehtuer.podsilo.core.ui.PodsiloIcon
import net.drehtuer.podsilo.core.ui.PodsiloIcons
import net.drehtuer.podsilo.core.ui.RowPadding
import java.time.ZoneId

internal const val SNIPPET_LINES = 2
internal const val TITLE_LINES = 2

/**
 * S2 — one feed's episodes (`docs/UI.md` §5).
 *
 * Stateless with respect to everything that matters: it renders [state] and emits [onEvent], and
 * holds no decision of its own. Every affordance a row shows comes from `EpisodeUi.actions`, which
 * the view model computed once, so the row body, the overflow and the accessibility actions cannot
 * disagree about what an episode currently offers (`docs/UI.md` §12.6).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun EpisodeListScreen(
    state: EpisodeListUiState,
    onEvent: (EpisodeListEvent) -> Unit,
    modifier: Modifier = Modifier,
    zone: ZoneId = ZoneId.systemDefault(),
) {
    Scaffold(
        modifier = modifier,
        topBar = { EpisodeListAppBar(state, onEvent) },
    ) { padding ->
        // Scoped to this feed (conditional GET — docs/UI.md §5). Like S1's, this gesture was
        // specified and its event handled, but nothing ever emitted it: S2 had no refresh
        // affordance of any kind, so a feed that failed to parse could never be retried from here.
        PullToRefreshBox(
            isRefreshing = state.isRefreshing,
            onRefresh = { onEvent(EpisodeListEvent.PullToRefresh) },
            modifier =
                Modifier
                    .padding(padding)
                    .fillMaxSize(),
        ) {
            Column(
                modifier = Modifier.fillMaxSize(),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Column(modifier = Modifier.widthIn(max = MaxContentWidth)) {
                    state.queueStatus.let { status ->
                        if (status is QueueStatus.Paused) PausedBanner(status, onEvent)
                    }
                    if (state.isOffline) OfflineBanner()
                    // Above the list, never in place of it: a failed refresh must leave the
                    // previously parsed episodes on screen (docs/UI.md §5).
                    state.feedError?.let { FeedErrorBanner(it, onEvent) }
                    FilterChips(state.filter, onEvent)
                    MarkAllRow(state, onEvent)

                    when (val content = state.content) {
                        EpisodeListUiState.Content.Loading -> LoadingRows()
                        is EpisodeListUiState.Content.Empty -> EmptyState(content.filter, onEvent)
                        is EpisodeListUiState.Content.Episodes ->
                            EpisodeRows(content.items, state, onEvent, zone)
                    }
                }
            }
        }
    }

    state.pendingBulk?.let { preview ->
        DownloadAllDialog(preview, onEvent)
    }
    state.pendingMarkAll?.let { keys ->
        MarkAllDialog(keys, onEvent)
    }
    // Both non-null only together: the action is only ever requested from the selection bar, which
    // exists only while there is a selection.
    state.pendingSelectionAction?.let { action ->
        state.selection?.let { selection -> SelectionActionDialog(action, selection, onEvent) }
    }
}

@Composable
private fun PausedBanner(
    status: QueueStatus.Paused,
    onEvent: (EpisodeListEvent) -> Unit,
) {
    // One condition, three causes, always with the fix as a button (docs/UI.md §12.11). The wording
    // differs because "choose a folder" and "the one you chose is gone" are different problems.
    val (message, action) =
        when (status.cause) {
            QueueStatus.PauseCause.FOLDER_NOT_CHOSEN ->
                "Downloads paused — no download folder chosen" to "Choose folder"
            QueueStatus.PauseCause.FOLDER_REVOKED ->
                "Downloads paused — the download folder is no longer available" to "Choose folder"
            QueueStatus.PauseCause.DISK_FULL ->
                "Downloads paused — no space left" to "Free up space"
        }
    Row(
        modifier =
            Modifier
                .fillMaxWidth()
                .background(MaterialTheme.colorScheme.errorContainer)
                .padding(RowPadding)
                .semantics { contentDescription = message },
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        // weight, and the button unwrapped: without these the message takes the whole row and the
        // action wraps to one word per line ("Choos / e / folder"), seen on the first device run.
        PodsiloIcon(PodsiloIcons.Warning, contentDescription = null)
        Text(message, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
        TextButton(
            onClick = { onEvent(EpisodeListEvent.PausedBannerActionClicked) },
            modifier = Modifier.sizeIn(minHeight = MinTouchTarget),
        ) { Text(action, maxLines = 1, softWrap = false) }
    }
}

/**
 * The failed-fetch banner from `docs/UI.md` §5 — **the state field existed and nothing ever set or
 * read it**, so until now a feed that would not load was silent on the screen that lists it.
 *
 * `circle-alert`, not `triangle-alert`: §18 draws the distinction and it matters here. A feed that
 * did not respond is *input the user can act on* (try again, or fix the feed in Nextcloud), not a
 * condition the download queue is in. Swapping them makes a broken feed look like a system fault.
 *
 * The message is the same plain sentence `FeedRefresher` wrote to the error log, passed through
 * verbatim — one writer, so the banner and S8 cannot describe the same failure differently.
 */
@Composable
private fun FeedErrorBanner(
    message: String,
    onEvent: (EpisodeListEvent) -> Unit,
) {
    Row(
        modifier =
            Modifier
                .fillMaxWidth()
                .background(MaterialTheme.colorScheme.errorContainer)
                .padding(RowPadding)
                .semantics { contentDescription = message },
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        PodsiloIcon(PodsiloIcons.InputError, contentDescription = null)
        Text(message, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
        TextButton(
            onClick = { onEvent(EpisodeListEvent.RetryFeedClicked) },
            modifier = Modifier.sizeIn(minHeight = MinTouchTarget),
        ) { Text("Try again", maxLines = 1, softWrap = false) }
    }
}

@Composable
private fun OfflineBanner() {
    Row(
        modifier =
            Modifier
                .fillMaxWidth()
                .background(MaterialTheme.colorScheme.surfaceVariant)
                .padding(RowPadding),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        PodsiloIcon(PodsiloIcons.Offline, contentDescription = null)
        Text("No network connection", style = MaterialTheme.typography.bodyMedium)
    }
}

/**
 * The four filter chips (`docs/UI.md` §5).
 *
 * **One horizontally scrollable line, not a wrap** (issue #48, decision D3). The four labels — the
 * longest being *Played / handled* — do not fit across a 360 dp phone, and the row that shipped had
 * neither scroll nor wrap, so the fourth chip was simply clipped off the right edge and `All` was
 * unreachable. Scrolling keeps the header height fixed; wrapping would push the first episode row
 * down on exactly the narrow screens that have the least room for it.
 *
 * The vertical padding is load-bearing rather than cosmetic: `sizeIn(minHeight = MinTouchTarget)`
 * grows each chip to the 48 dp floor §12.12 requires, and without padding of its own the row hands
 * that grown height straight to its neighbours.
 */
@Composable
private fun FilterChips(
    selected: EpisodeFilter,
    onEvent: (EpisodeListEvent) -> Unit,
) {
    Row(
        modifier =
            Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState())
                .padding(horizontal = RowPadding, vertical = ChipRowPadding),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        EpisodeFilter.entries.forEach { filter ->
            FilterChip(
                selected = filter == selected,
                onClick = { onEvent(EpisodeListEvent.FilterChanged(filter)) },
                label = { Text(filter.label, maxLines = 1, softWrap = false) },
                // §12.12: chips are interactive controls, and reading as labels is exactly the drift
                // that puts them below the 48 dp floor.
                modifier = Modifier.sizeIn(minHeight = MinTouchTarget),
            )
        }
    }
}

private val EpisodeFilter.label: String
    get() =
        when (this) {
            EpisodeFilter.TO_DECIDE -> "To decide"
            EpisodeFilter.DOWNLOADED -> "Downloaded"
            EpisodeFilter.PLAYED_OR_HANDLED -> "Played / handled"
            EpisodeFilter.ALL -> "All"
        }

@Composable
private fun LoadingRows() {
    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        CircularProgressIndicator(modifier = Modifier.semantics { contentDescription = "Loading episodes" })
    }
}

@Composable
private fun EmptyState(
    filter: EpisodeFilter,
    onEvent: (EpisodeListEvent) -> Unit,
) {
    Column(
        modifier = Modifier.fillMaxSize().padding(RowPadding),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        PodsiloIcon(
            icon = if (filter == EpisodeFilter.TO_DECIDE) PodsiloIcons.AllDone else PodsiloIcons.Empty,
            contentDescription = null,
        )
        Text(
            text =
                if (filter == EpisodeFilter.TO_DECIDE) {
                    "Nothing to decide in this podcast."
                } else {
                    "Nothing here."
                },
            style = MaterialTheme.typography.bodyLarge,
        )
        if (filter != EpisodeFilter.ALL) {
            TextButton(
                onClick = { onEvent(EpisodeListEvent.FilterChanged(EpisodeFilter.ALL)) },
                modifier = Modifier.sizeIn(minHeight = MinTouchTarget),
            ) { Text("Show all episodes") }
        }
    }
}

@Composable
private fun EpisodeRows(
    items: List<EpisodeUi>,
    state: EpisodeListUiState,
    onEvent: (EpisodeListEvent) -> Unit,
    zone: ZoneId,
) {
    // Keyed by episodeKey: the sticky headers and any item animation both need stable keys, and a
    // 500-episode feed under "All" is the case that punishes their absence (UI_interface §14.3).
    LazyColumn(modifier = Modifier.fillMaxSize()) {
        items(items, key = { it.episodeKey }) { episode ->
            val header = state.sections.firstOrNull { it.firstIndex == items.indexOf(episode) }
            if (header != null) MonthHeader(header)
            SwipeableEpisodeRow(
                episode = episode,
                mapping = state.swipeMapping,
                // Selection mode owns the gesture surface while it is active.
                enabled = !state.inSelectionMode,
                onEvent = onEvent,
            ) {
                EpisodeRow(
                    episode = episode,
                    selected = state.selection?.keys?.contains(episode.episodeKey) == true,
                    inSelectionMode = state.inSelectionMode,
                    onEvent = onEvent,
                    zone = zone,
                )
            }
            HorizontalDivider()
        }
    }
}

@Composable
private fun MonthHeader(section: MonthSection) {
    val label =
        section.label?.let { "%04d-%02d".format(it.year, it.month) } ?: "Date unknown"
    Text(
        text = label,
        style = MaterialTheme.typography.labelLarge,
        color = MaterialTheme.colorScheme.primary,
        modifier =
            Modifier
                .fillMaxWidth()
                .background(MaterialTheme.colorScheme.surface)
                .padding(horizontal = RowPadding, vertical = 8.dp)
                .semantics { contentDescription = "Published $label" },
    )
}

@Composable
private fun DownloadAllDialog(
    preview: BulkPreview,
    onEvent: (EpisodeListEvent) -> Unit,
) {
    AlertDialog(
        onDismissRequest = { onEvent(EpisodeListEvent.DownloadAllDismissed) },
        title = { Text("Download ${preview.count} episodes?") },
        text = {
            Column {
                preview.perFeed.forEach { Text("${it.feedUrl}   ${it.count}") }
                if (preview.exceedsFreeSpace) {
                    // A warning line only — the estimate is a guess and must never veto a decision
                    // the user has made (docs/decisions/0014).
                    Text(
                        "⚠ This may not fit in the download folder.",
                        color = MaterialTheme.colorScheme.error,
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
            }
        },
        confirmButton = {
            TextButton(onClick = { onEvent(EpisodeListEvent.DownloadAllConfirmed(preview.episodeKeys)) }) {
                Text("Download")
            }
        },
        dismissButton = {
            TextButton(onClick = { onEvent(EpisodeListEvent.DownloadAllDismissed) }) { Text("Cancel") }
        },
    )
}
