// SPDX-License-Identifier: GPL-3.0-or-later

package net.drehtuer.podsilo.feature.episodes

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
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
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import net.drehtuer.podsilo.core.model.LedgerState
import java.time.Duration
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle

private const val MINUTES_PER_HOUR = 60

/**
 * One episode row and everything it renders — split from the screen because a Compose file is a
 * pile of small composables and the two halves have different jobs: the screen owns the chrome
 * (banners, chips, empty states, the dialog), this owns what a single episode looks like.
 */
@Composable
internal fun EpisodeRow(
    episode: EpisodeUi,
    selected: Boolean,
    inSelectionMode: Boolean,
    onEvent: (EpisodeListEvent) -> Unit,
    zone: ZoneId,
) {
    Column(
        modifier =
            Modifier
                .fillMaxWidth()
                .heightIn(min = MinRowHeight)
                // Tapping the body opens detail and never triages — a mis-tap must not queue a
                // download (docs/UI.md §5).
                .clickable {
                    if (inSelectionMode) {
                        onEvent(EpisodeListEvent.SelectionToggled(episode.episodeKey))
                    } else {
                        onEvent(EpisodeListEvent.RowClicked(episode.episodeKey))
                    }
                }.background(
                    if (selected) MaterialTheme.colorScheme.secondaryContainer else MaterialTheme.colorScheme.surface,
                ).padding(RowPadding),
    ) {
        Text(
            text = episode.title,
            style = MaterialTheme.typography.titleMedium,
            // The title truncates first; the decision affordances never do (docs/UI.md §12.12).
            maxLines = TITLE_LINES,
            overflow = TextOverflow.Ellipsis,
            // De-emphasised, not unreadable: onSurfaceVariant rather than an opacity that would drop
            // the title below 4.5:1 (§12.7).
            color =
                if (episode.isDeEmphasised) {
                    MaterialTheme.colorScheme.onSurfaceVariant
                } else {
                    MaterialTheme.colorScheme.onSurface
                },
        )

        val meta = episode.metaLine(zone)
        if (meta.isNotEmpty()) {
            Text(meta, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }

        episode.statusLine()?.let { status ->
            Text(status, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }

        if (episode.ledgerState == LedgerState.DOWNLOADING) DownloadProgressBar(episode.progress)

        if (episode.descriptionSnippet.isNotEmpty()) {
            Text(
                text = episode.descriptionSnippet,
                style = MaterialTheme.typography.bodySmall,
                maxLines = SNIPPET_LINES,
                overflow = TextOverflow.Ellipsis,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        EpisodeActions(episode, onEvent)
    }
}

/**
 * Rendered from [EpisodeUi.actions] and nothing else — no `when (state)` here, which is what stops
 * this list drifting from the overflow and the accessibility actions (`docs/UI.md` §12.6).
 */
@Composable
private fun EpisodeActions(
    episode: EpisodeUi,
    onEvent: (EpisodeListEvent) -> Unit,
) {
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        episode.actions.forEach { action ->
            val label = action.labelFor(episode) ?: return@forEach
            TextButton(
                onClick = { onEvent(EpisodeListEvent.Triage(episode.episodeKey, action)) },
                modifier = Modifier.sizeIn(minHeight = MinTouchTarget),
            ) { Text(label) }
        }
    }
}

/**
 * A `FOLDER_UNAVAILABLE` or `DISK_FULL` failure replaces *Retry* with the action that can actually
 * clear it (`docs/UI.md` §12.11, `docs/decisions/0011`) — a Retry there is a button that cannot work.
 */
private fun EpisodeUiAction.labelFor(episode: EpisodeUi): String? =
    when (this) {
        EpisodeUiAction.DOWNLOAD -> "Download"
        EpisodeUiAction.DOWNLOAD_AGAIN -> "Download again"
        EpisodeUiAction.MARK_AS_PLAYED -> "Mark as played"
        EpisodeUiAction.CANCEL -> "Cancel"
        EpisodeUiAction.RETRY ->
            when (episode.lastError?.remedy) {
                FailureRemedy.CHOOSE_FOLDER -> "Choose folder"
                FailureRemedy.FREE_UP_SPACE -> "Free up space"
                null -> "Retry"
            }
        // Reachable from the row overflow and the detail sheet, not as a primary button.
        EpisodeUiAction.OPEN_IN_BROWSER, EpisodeUiAction.COPY_LINK -> null
    }

private fun EpisodeUi.metaLine(zone: ZoneId): String =
    listOfNotNull(publishedAt?.formatDate(zone), duration?.formatDuration())
        .joinToString(" · ")

private fun EpisodeUi.statusLine(): String? =
    when (ledgerState) {
        null -> null
        LedgerState.QUEUED -> "queued"
        LedgerState.DOWNLOADING -> null
        LedgerState.DOWNLOADED -> "✓ downloaded"
        LedgerState.SKIPPED -> "▸ played"
        LedgerState.HANDLED_REMOTELY -> "handled elsewhere"
        // The message is passed through verbatim: it is the one string the UI does not re-word.
        LedgerState.ERROR -> lastError?.let { "failed — ${it.message} (attempt ${it.attempts})" } ?: "failed"
    }

private fun Instant.formatDate(zone: ZoneId): String =
    DateTimeFormatter.ofLocalizedDate(FormatStyle.MEDIUM).withZone(zone).format(this)

/** Never fabricated: an absent duration simply has no part in the meta line (`docs/UI.md` §5). */
private fun Duration.formatDuration(): String {
    val minutes = toMinutes()
    return if (minutes >= MINUTES_PER_HOUR) {
        "${minutes / MINUTES_PER_HOUR} h ${minutes % MINUTES_PER_HOUR} min"
    } else {
        "$minutes min"
    }
}

/**
 * A percentage is only ever drawn from an update seen **in this process** (`docs/UI_interface.md`
 * §7). After process death WorkManager's progress is gone, so a `DOWNLOADING` row with none reads
 * *resuming* rather than implying it knows how far along it is.
 */
@Composable
private fun DownloadProgressBar(progress: DownloadProgress?) {
    val percent = progress?.percent
    if (percent != null) {
        LinearProgressIndicator(
            progress = { percent / PERCENT_SCALE },
            modifier =
                Modifier
                    .fillMaxWidth()
                    .semantics { contentDescription = "downloading, $percent percent" },
        )
    } else {
        LinearProgressIndicator(
            modifier = Modifier.fillMaxWidth().semantics { contentDescription = "resuming" },
        )
    }
}

private const val PERCENT_SCALE = 100f
