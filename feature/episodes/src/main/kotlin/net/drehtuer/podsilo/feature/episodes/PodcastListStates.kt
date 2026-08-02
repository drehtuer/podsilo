// SPDX-License-Identifier: GPL-3.0-or-later

package net.drehtuer.podsilo.feature.episodes

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.sizeIn
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics

/**
 * S1's chrome and its four empty states — every condition the home screen can be in that is not a
 * list of podcasts (`docs/UI.md` §4). Split from the screen itself because they are a different
 * job: the screen composes, these render one state each.
 */
@Composable
internal fun PodcastPausedBanner(
    status: QueueStatus.Paused,
    onEvent: (PodcastListEvent) -> Unit,
) {
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
        Text(message, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
        TextButton(
            onClick = { onEvent(PodcastListEvent.PausedBannerActionClicked) },
            modifier = Modifier.sizeIn(minHeight = MinTouchTarget),
        ) { Text(action, maxLines = 1, softWrap = false) }
    }
}

@Composable
internal fun PodcastOfflineBanner() {
    Text(
        text = "No network connection",
        style = MaterialTheme.typography.bodyMedium,
        modifier =
            Modifier
                .fillMaxWidth()
                .background(MaterialTheme.colorScheme.surfaceVariant)
                .padding(RowPadding),
    )
}

@Composable
internal fun PodcastLoadingState() {
    // Shimmer rows rather than a spinner overlay (docs/UI.md §4): the list is about to appear, and
    // a spinner over an empty screen reads as "something is wrong".
    Column(modifier = Modifier.fillMaxWidth()) {
        repeat(SHIMMER_ROWS) {
            Row(
                modifier =
                    Modifier
                        .fillMaxWidth()
                        .heightIn(min = ArtworkSize + RowPadding)
                        .padding(RowPadding)
                        .semantics { contentDescription = "Loading podcasts" },
            ) {
                Text("…", style = MaterialTheme.typography.bodyLarge)
            }
            HorizontalDivider()
        }
    }
}

private const val SHIMMER_ROWS = 3

@Composable
internal fun NotConfiguredState(onEvent: (PodcastListEvent) -> Unit) {
    CentredEmptyState(
        message = "Podsilo follows the podcast subscriptions in your Nextcloud.",
        actionLabel = "Connect Nextcloud",
        onAction = { onEvent(PodcastListEvent.ConnectNextcloudClicked) },
    )
}

@Composable
internal fun NoSubscriptionsState(onEvent: (PodcastListEvent) -> Unit) {
    // The wording matters more than it looks: this is where the read-only design becomes visible,
    // so it points at Nextcloud and offers Refresh — never an add-feed button (CLAUDE.md §10).
    CentredEmptyState(
        message = "No subscriptions found — add feeds in Nextcloud.",
        actionLabel = "Refresh",
        onAction = { onEvent(PodcastListEvent.PullToRefresh) },
    )
}

@Composable
internal fun CentredEmptyState(
    message: String,
    actionLabel: String,
    onAction: () -> Unit,
) {
    Column(
        modifier = Modifier.fillMaxSize().padding(RowPadding),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Text(message, style = MaterialTheme.typography.bodyLarge)
        TextButton(onClick = onAction, modifier = Modifier.sizeIn(minHeight = MinTouchTarget)) {
            Text(actionLabel)
        }
    }
}
