// SPDX-License-Identifier: GPL-3.0-or-later

package net.drehtuer.podsilo.ui.activity

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.sizeIn
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import net.drehtuer.podsilo.core.ui.MinTouchTarget
import net.drehtuer.podsilo.core.ui.PodsiloIcon
import net.drehtuer.podsilo.core.ui.PodsiloIcons
import net.drehtuer.podsilo.core.ui.RowPadding
import net.drehtuer.podsilo.feature.episodes.EpisodeUi
import net.drehtuer.podsilo.feature.episodes.FailureRemedy

/**
 * S7's four kinds of row. Split from the screen because the screen's job is which groups exist and
 * in what order, and this file's is what a row in each one looks like.
 */
@Composable
internal fun DownloadingRow(
    episode: EpisodeUi,
    onEvent: (ActivityEvent) -> Unit,
) {
    ActivityRow(episode, onEvent) {
        val percent = episode.progress?.percent
        if (percent != null) {
            LinearProgressIndicator(
                progress = { percent / PERCENT_SCALE },
                modifier = Modifier.fillMaxWidth().semantics { contentDescription = "downloading, $percent percent" },
            )
        } else {
            // No live update means this process has not seen one — never a stale 0 % (§7).
            LinearProgressIndicator(
                modifier = Modifier.fillMaxWidth().semantics { contentDescription = "resuming" },
            )
        }
        TextButton(
            onClick = { onEvent(ActivityEvent.CancelClicked(episode.episodeKey)) },
            modifier = Modifier.sizeIn(minHeight = MinTouchTarget),
        ) { Text("Cancel") }
    }
}

private const val PERCENT_SCALE = 100f

@Composable
internal fun QueuedRow(
    queued: QueuedUi,
    onEvent: (ActivityEvent) -> Unit,
) {
    ActivityRow(queued.episode, onEvent) {
        Text(queued.reason.label, style = MaterialTheme.typography.bodySmall)
        TextButton(
            onClick = { onEvent(ActivityEvent.CancelClicked(queued.episode.episodeKey)) },
            modifier = Modifier.sizeIn(minHeight = MinTouchTarget),
        ) { Text("Cancel") }
    }
}

internal val WaitReason.label: String
    get() =
        when (this) {
            WaitReason.WIFI -> "waiting for Wi-Fi"
            WaitReason.NETWORK -> "waiting for a network"
            WaitReason.FOLDER -> "waiting for a download folder"
            WaitReason.RESUMING -> "resuming after restart"
        }

@Composable
internal fun FailedRow(
    episode: EpisodeUi,
    onEvent: (ActivityEvent) -> Unit,
) {
    ActivityRow(episode, onEvent) {
        episode.lastError?.let { failure ->
            // Verbatim: the message is the one string the UI does not re-word.
            Text(
                text = "${failure.message} · attempt ${failure.attempts}",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error,
            )
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            // ADR 0011: a failure the user must fix offers the fix, never a Retry that cannot work.
            val remedy = episode.lastError?.remedy
            TextButton(
                onClick = {
                    if (remedy == null) {
                        onEvent(ActivityEvent.RetryClicked(episode.episodeKey))
                    } else {
                        onEvent(ActivityEvent.PausedBannerActionClicked)
                    }
                },
                modifier = Modifier.sizeIn(minHeight = MinTouchTarget),
            ) {
                Text(
                    when (remedy) {
                        FailureRemedy.CHOOSE_FOLDER -> "Choose folder"
                        FailureRemedy.FREE_UP_SPACE -> "Free up space"
                        null -> "Retry"
                    },
                )
            }
            TextButton(
                onClick = { onEvent(ActivityEvent.MarkAsPlayedClicked(episode.episodeKey)) },
                modifier = Modifier.sizeIn(minHeight = MinTouchTarget),
            ) { Text("Mark as played") }
            TextButton(
                onClick = { onEvent(ActivityEvent.DetailsClicked(episode.episodeKey)) },
                modifier = Modifier.sizeIn(minHeight = MinTouchTarget),
            ) { Text("Details") }
        }
    }
}

@Composable
internal fun DeliveredRow(
    delivered: DeliveredUi,
    onEvent: (ActivityEvent) -> Unit,
) {
    Row(
        modifier =
            Modifier
                .fillMaxWidth()
                .clickable { onEvent(ActivityEvent.RowClicked(delivered.feedUrl, delivered.episodeKey)) }
                .padding(RowPadding),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        PodsiloIcon(PodsiloIcons.Check, contentDescription = null)
        Column {
            Text(delivered.fileName, style = MaterialTheme.typography.bodyMedium)
            delivered.folderLabel?.let {
                Text(
                    it,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
private fun ActivityRow(
    episode: EpisodeUi,
    onEvent: (ActivityEvent) -> Unit,
    content: @Composable androidx.compose.foundation.layout.ColumnScope.() -> Unit,
) {
    Column(
        modifier =
            Modifier
                .fillMaxWidth()
                .clickable { onEvent(ActivityEvent.RowClicked(episode.feedUrl, episode.episodeKey)) }
                .padding(RowPadding),
    ) {
        Text(episode.title, style = MaterialTheme.typography.bodyMedium)
        Text(
            episode.feedTitle,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        content()
    }
}
