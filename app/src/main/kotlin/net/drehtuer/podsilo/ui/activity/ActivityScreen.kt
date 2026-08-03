// SPDX-License-Identifier: GPL-3.0-or-later

package net.drehtuer.podsilo.ui.activity

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.sizeIn
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.unit.dp
import net.drehtuer.podsilo.core.ui.MaxContentWidth
import net.drehtuer.podsilo.core.ui.MinTouchTarget
import net.drehtuer.podsilo.core.ui.PodsiloIcon
import net.drehtuer.podsilo.core.ui.PodsiloIcons
import net.drehtuer.podsilo.core.ui.RowPadding
import net.drehtuer.podsilo.feature.episodes.QueueStatus
import java.time.Duration
import java.time.Instant

/**
 * S7 — activity (`docs/UI.md` §10). *What is the app doing, and what is stuck?*
 *
 * Explicitly **not** a file manager: the *recently downloaded* group shows what was written and
 * offers no delete, no open-file and no existence check (README, CLAUDE.md §11).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ActivityScreen(
    state: ActivityUiState,
    onEvent: (ActivityEvent) -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    now: Instant = Instant.now(),
) {
    Scaffold(
        modifier = modifier,
        topBar = {
            TopAppBar(
                title = { Text("Activity") },
                navigationIcon = {
                    IconButton(onClick = onBack, modifier = Modifier.sizeIn(minHeight = MinTouchTarget)) {
                        PodsiloIcon(PodsiloIcons.Back, contentDescription = "Back")
                    }
                },
                actions = {
                    IconButton(onClick = { onEvent(ActivityEvent.ErrorLogClicked) }) {
                        PodsiloIcon(PodsiloIcons.ErrorLog, contentDescription = "Error log")
                    }
                },
            )
        },
    ) { padding ->
        Column(
            modifier = Modifier.padding(padding).fillMaxSize(),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Column(modifier = Modifier.widthIn(max = MaxContentWidth)) {
                (state.queueStatus as? QueueStatus.Paused)?.let { PausedBanner(it, onEvent) }
                SyncRow(state.sync, now, onEvent)
                if (state.isIdle) NothingHappening() else Groups(state, onEvent)
            }
        }
    }
}

@Composable
private fun PausedBanner(
    status: QueueStatus.Paused,
    onEvent: (ActivityEvent) -> Unit,
) {
    val (message, action) =
        when (status.cause) {
            QueueStatus.PauseCause.FOLDER_NOT_CHOSEN ->
                "Downloads paused — no download folder chosen" to "Choose folder"
            QueueStatus.PauseCause.FOLDER_REVOKED ->
                "Downloads paused — the download folder is no longer available" to "Choose folder"
            QueueStatus.PauseCause.DISK_FULL -> "Downloads paused — no space left" to "Free up space"
        }
    Row(
        modifier =
            Modifier
                .fillMaxWidth()
                .background(MaterialTheme.colorScheme.errorContainer)
                .padding(RowPadding),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Row(
            // The weight goes on the *Row*, not the Text inside it: without it the icon-plus-message
            // group takes the whole width and the action clips to "Cl". Seen on the device.
            modifier = Modifier.weight(1f),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            // A condition the queue is in, not user input to fix (docs/UI.md §18).
            PodsiloIcon(PodsiloIcons.Warning, contentDescription = null)
            Text(message, style = MaterialTheme.typography.bodyMedium)
        }
        TextButton(
            onClick = { onEvent(ActivityEvent.PausedBannerActionClicked) },
            modifier = Modifier.sizeIn(minHeight = MinTouchTarget),
        ) { Text(action, maxLines = 1, softWrap = false) }
    }
}

@Composable
private fun SyncRow(
    sync: SyncUi,
    now: Instant,
    onEvent: (ActivityEvent) -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(RowPadding),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text("Sync", style = MaterialTheme.typography.bodyLarge)
            Text(
                text = sync.line(now),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        // Disabled with the reason shown, rather than a button that times out (docs/UI.md §12.10).
        TextButton(
            onClick = { onEvent(ActivityEvent.SyncNowClicked) },
            enabled = sync.canSyncNow,
            modifier = Modifier.sizeIn(minHeight = MinTouchTarget),
        ) {
            PodsiloIcon(if (sync.canSyncNow) PodsiloIcons.Syncing else PodsiloIcons.Offline, contentDescription = null)
            Text("Sync now", maxLines = 1, softWrap = false)
        }
    }
    HorizontalDivider()
}

internal fun SyncUi.line(now: Instant): String {
    val blocked =
        when (blockedReason) {
            BlockedReason.OFFLINE -> "No network connection"
            BlockedReason.NOT_CONFIGURED -> "No Nextcloud connected"
            null -> null
        }
    val last = lastSyncAt?.let { "last ${relative(it, now)}" } ?: "never synced"
    val pending =
        when (outboxDepth) {
            0 -> null
            1 -> "1 action pending"
            else -> "$outboxDepth actions pending"
        }
    return listOfNotNull(blocked, last, pending).joinToString(" · ")
}

@Composable
private fun NothingHappening() {
    Column(
        modifier = Modifier.fillMaxSize().padding(RowPadding),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        PodsiloIcon(PodsiloIcons.AllDone, contentDescription = null)
        Text("Nothing downloading, nothing failed.", style = MaterialTheme.typography.bodyLarge)
    }
}

@Composable
private fun Groups(
    state: ActivityUiState,
    onEvent: (ActivityEvent) -> Unit,
) {
    LazyColumn(modifier = Modifier.fillMaxSize()) {
        group("DOWNLOADING", state.downloading, { it.episodeKey }) { DownloadingRow(it, onEvent) }
        group("QUEUED", state.queued, { it.episode.episodeKey }) { QueuedRow(it, onEvent) }
        group("FAILED", state.failed, { it.episodeKey }) { FailedRow(it, onEvent) }
        group("RECENTLY DOWNLOADED", state.recent, { it.episodeKey }) { DeliveredRow(it, onEvent) }
        if (state.recent.isNotEmpty()) {
            item {
                // "Clear" empties the LIST, not the folder and not the ledger. Podsilo never deletes a
                // downloaded file (CLAUDE.md §1), and the ledger row is what stops the episode being
                // fetched again (§11) — so the label says "list" and the subtitle says the rest.
                TextButton(
                    onClick = { onEvent(ActivityEvent.ClearDeliveredClicked) },
                    modifier = Modifier.padding(horizontal = RowPadding).sizeIn(minHeight = MinTouchTarget),
                ) { Text("Clear list") }
            }
        }
    }
}

/** A group with no rows renders nothing at all — an empty heading is worse than no heading. */
private fun <T> androidx.compose.foundation.lazy.LazyListScope.group(
    title: String,
    rows: List<T>,
    key: (T) -> Any,
    row: @Composable (T) -> Unit,
) {
    if (rows.isEmpty()) return
    item(key = "header-$title") {
        Text(
            text = title,
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.primary,
            modifier = Modifier.padding(horizontal = RowPadding, vertical = 8.dp),
        )
    }
    items(rows, key = { "$title-${key(it)}" }) {
        row(it)
        HorizontalDivider()
    }
}

/** Coarse on purpose, and the same wording S1 and S4 use. */
internal fun relative(
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

private const val MINUTES_PER_HOUR = 60
private const val MINUTES_PER_DAY = 60 * 24
