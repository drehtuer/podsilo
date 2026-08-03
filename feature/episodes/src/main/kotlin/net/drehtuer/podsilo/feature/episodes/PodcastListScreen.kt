// SPDX-License-Identifier: GPL-3.0-or-later

package net.drehtuer.podsilo.feature.episodes

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.sizeIn
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import net.drehtuer.podsilo.core.ui.ArtworkSize
import net.drehtuer.podsilo.core.ui.MaxContentWidth
import net.drehtuer.podsilo.core.ui.MinTouchTarget
import net.drehtuer.podsilo.core.ui.PodsiloArtwork
import net.drehtuer.podsilo.core.ui.PodsiloIcon
import net.drehtuer.podsilo.core.ui.PodsiloIcons
import net.drehtuer.podsilo.core.ui.RowPadding
import java.time.Duration
import java.time.Instant

/**
 * S1 — the podcast list, and the app's home (`docs/UI.md` §4).
 *
 * Stateless, like S2: it renders [state] and emits [onEvent]. The one thing it must never grow is an
 * add-feed affordance — the empty state says subscriptions are managed in Nextcloud, and that empty
 * state is the main place the read-only design becomes visible to the user (CLAUDE.md §10).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PodcastListScreen(
    state: PodcastListUiState,
    onEvent: (PodcastListEvent) -> Unit,
    modifier: Modifier = Modifier,
    now: Instant = Instant.now(),
) {
    Scaffold(
        modifier = modifier,
        topBar = {
            TopAppBar(
                title = { Text("Podsilo") },
                actions = {
                    // The app bar is the one place an icon-only control exists — the target is
                    // conventional there and nowhere else (docs/UI.md §18).
                    IconButton(
                        onClick = { onEvent(PodcastListEvent.ActivityClicked) },
                        modifier = Modifier.sizeIn(minHeight = MinTouchTarget),
                    ) {
                        PodsiloIcon(
                            icon = PodsiloIcons.Activity,
                            contentDescription = if (state.activityBadge) "Activity, running" else "Activity",
                        )
                    }
                    IconButton(
                        onClick = { onEvent(PodcastListEvent.SettingsClicked) },
                        modifier = Modifier.sizeIn(minHeight = MinTouchTarget),
                    ) {
                        PodsiloIcon(PodsiloIcons.Settings, contentDescription = "Settings")
                    }
                },
            )
        },
    ) { padding ->
        // The ONLY way to refresh feeds once subscriptions exist. `PodcastListEvent.PullToRefresh`
        // and its view-model handler shipped without a gesture to fire them, and the sole emitter was
        // the Refresh button in the *no subscriptions* empty state — which by definition never
        // renders once there are feeds. On a real account with four subscriptions the result was
        // that every feed read "never refreshed" for ever and no episode ever arrived, because the
        // periodic worker is the only other caller (see docs/journal.md, 2026-08-02).
        PullToRefreshBox(
            isRefreshing = state.isRefreshing,
            onRefresh = { onEvent(PodcastListEvent.PullToRefresh) },
            modifier = Modifier.padding(padding).fillMaxSize(),
        ) {
            Column(
                modifier = Modifier.fillMaxSize(),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Column(modifier = Modifier.widthIn(max = MaxContentWidth)) {
                    // Not while the checklist is up: on first run both would say "choose a folder",
                    // and the checklist says it better — with the step it belongs to. Seen on the first
                    // device run, where the two stacked.
                    if (state.setup == null) {
                        (state.queueStatus as? QueueStatus.Paused)?.let {
                            PodcastPausedBanner(it, onEvent)
                        }
                    }
                    if (state.isOffline) PodcastOfflineBanner()
                    state.setup?.let { SetupCard(it, onEvent) }

                    when (val content = state.content) {
                        PodcastListUiState.Content.NotConfigured -> NotConfiguredState(onEvent)
                        PodcastListUiState.Content.Loading -> PodcastLoadingState()
                        PodcastListUiState.Content.NoSubscriptions -> NoSubscriptionsState(onEvent)
                        is PodcastListUiState.Content.Feeds -> {
                            PodcastFilterChips(state.filter, onEvent)
                            FeedRows(content.feeds, state.totalUndecided, state.filter, now, onEvent)
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun PodcastFilterChips(
    selected: PodcastFilter,
    onEvent: (PodcastListEvent) -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(horizontal = RowPadding),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        PodcastFilter.entries.forEach { filter ->
            FilterChip(
                selected = filter == selected,
                onClick = { onEvent(PodcastListEvent.FilterChanged(filter)) },
                label = { Text(filter.label) },
                modifier = Modifier.sizeIn(minHeight = MinTouchTarget),
            )
        }
    }
}

private val PodcastFilter.label: String
    get() =
        when (this) {
            PodcastFilter.WITH_NEW -> "With new episodes"
            PodcastFilter.ALL -> "All podcasts"
        }

@Composable
private fun FeedRows(
    feeds: List<FeedUi>,
    totalUndecided: Int,
    filter: PodcastFilter,
    now: Instant,
    onEvent: (PodcastListEvent) -> Unit,
) {
    if (feeds.isEmpty()) {
        CentredEmptyState(
            message = "Nothing new. All caught up.",
            actionLabel = "Show all podcasts",
            onAction = { onEvent(PodcastListEvent.FilterChanged(PodcastFilter.ALL)) },
        )
        return
    }
    LazyColumn(modifier = Modifier.fillMaxSize()) {
        items(feeds, key = { it.url }) { feed ->
            PodcastRow(feed, now, onEvent)
            HorizontalDivider()
        }
        item {
            Text(
                text = summaryLine(totalUndecided, feeds.size, filter),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.fillMaxWidth().padding(RowPadding),
            )
        }
    }
}

internal fun summaryLine(
    totalUndecided: Int,
    feedCount: Int,
    filter: PodcastFilter,
): String {
    val scope = if (filter == PodcastFilter.ALL) "podcasts" else "podcasts with new episodes"
    return "$totalUndecided episodes to decide across $feedCount $scope"
}

@Composable
private fun PodcastRow(
    feed: FeedUi,
    now: Instant,
    onEvent: (PodcastListEvent) -> Unit,
) {
    Row(
        modifier =
            Modifier
                .fillMaxWidth()
                .heightIn(min = ArtworkSize + RowPadding)
                .clickable { onEvent(PodcastListEvent.FeedClicked(feed.url)) }
                .padding(RowPadding),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(RowPadding),
    ) {
        // The row reserved `ArtworkSize` height from the day it was written and never drew anything
        // into it — Coil was in the catalog, approved (ADR 0015), and used by no module at all.
        PodsiloArtwork(url = feed.artworkUrl, title = feed.displayTitle)
        Column(modifier = Modifier.weight(1f)) {
            Text(
                // The URL until the first fetch supplies a title — never "Unknown podcast".
                text = feed.displayTitle,
                style = MaterialTheme.typography.bodyLarge,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = feed.secondaryLine(now),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Text(
            // "–", not "0": a feed nobody has fetched has an unknown count, not none (§12.5).
            text = feed.undecidedCount?.toString() ?: "–",
            style = MaterialTheme.typography.labelLarge,
            modifier =
                Modifier.semantics {
                    contentDescription =
                        feed.undecidedCount?.let { "$it episodes to decide" } ?: "never refreshed"
                },
        )
        // The whole row is the tap target; this is the affordance, not a control (§18).
        PodsiloIcon(PodsiloIcons.ChevronRight, contentDescription = null)
    }
}

/**
 * `"never refreshed"` is a fact worth stating (it explains a `–` badge); a relative time is easier
 * to read at a glance than a timestamp, and needs no locale-specific date format.
 */
internal fun FeedUi.secondaryLine(now: Instant): String =
    when {
        activeDownloads > 0 -> "$activeDownloads downloading"
        lastRefreshedAt == null -> "never refreshed"
        else -> "last refreshed ${relativeTime(lastRefreshedAt, now)}"
    }

/** Coarse on purpose: nobody needs to know a feed was refreshed 7 minutes and 12 seconds ago. */
internal fun relativeTime(
    then: Instant,
    now: Instant,
): String {
    val minutes = Duration.between(then, now).toMinutes()
    return when {
        minutes < 1 -> "just now"
        minutes < MINUTES_PER_HOUR -> "$minutes min ago"
        minutes < MINUTES_PER_DAY -> "${minutes / MINUTES_PER_HOUR} h ago"
        else -> "${minutes / MINUTES_PER_DAY} d ago"
    }
}

private const val MINUTES_PER_DAY = 60 * 24
