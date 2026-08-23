// SPDX-License-Identifier: GPL-3.0-or-later

package net.drehtuer.podsilo.feature.episodes

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.sizeIn
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import net.drehtuer.podsilo.core.model.LedgerState
import net.drehtuer.podsilo.core.ui.MinRowHeight
import net.drehtuer.podsilo.core.ui.MinTouchTarget
import net.drehtuer.podsilo.core.ui.PodsiloArtwork
import net.drehtuer.podsilo.core.ui.PodsiloIcon
import net.drehtuer.podsilo.core.ui.PodsiloIcons
import net.drehtuer.podsilo.core.ui.RowPadding
import java.time.ZoneId

/**
 * One episode row and everything it renders — split from the screen because a Compose file is a
 * pile of small composables and the two halves have different jobs: the screen owns the chrome
 * (banners, chips, empty states, the dialog), this owns what a single episode looks like.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
internal fun EpisodeRow(
    episode: EpisodeUi,
    selected: Boolean,
    inSelectionMode: Boolean,
    onEvent: (EpisodeListEvent) -> Unit,
    zone: ZoneId,
) {
    Row(
        modifier =
            Modifier
                .fillMaxWidth()
                .heightIn(min = MinRowHeight)
                // Tapping the body opens detail and never triages — a mis-tap must not queue a
                // download (UI.adoc §5). Long-press enters selection mode, which is the entry
                // point issue #46 was missing: the whole selection model existed and was tested,
                // and `clickable` gave it no way in.
                .combinedClickable(
                    onLongClick = { onEvent(EpisodeListEvent.SelectionStarted(episode.episodeKey)) },
                    onClick = {
                        if (inSelectionMode) {
                            onEvent(EpisodeListEvent.SelectionToggled(episode.episodeKey))
                        } else {
                            onEvent(EpisodeListEvent.RowClicked(episode.episodeKey))
                        }
                    },
                ).semantics {
                    // §12.12: selection must be reachable **without** a long-press. A custom action
                    // is how a TalkBack user reaches it — a gesture-only affordance is unreachable
                    // for them, and this is the same event the long-press emits, not a parallel path.
                    customActions =
                        listOf(
                            CustomAccessibilityAction(if (inSelectionMode) "Toggle selection" else "Select") {
                                onEvent(
                                    if (inSelectionMode) {
                                        EpisodeListEvent.SelectionToggled(episode.episodeKey)
                                    } else {
                                        EpisodeListEvent.SelectionStarted(episode.episodeKey)
                                    },
                                )
                                true
                            },
                        )
                    if (inSelectionMode) this.selected = selected
                }.background(
                    if (selected) MaterialTheme.colorScheme.secondaryContainer else MaterialTheme.colorScheme.surface,
                    // Vertical only: the horizontal inset is the list's gutter now, so the swipe
                    // surface stops short of the screen edge instead of reaching it (issue #92).
                ).padding(vertical = RowPadding),
        horizontalArrangement = Arrangement.spacedBy(RowPadding),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // The checkbox §12.12 asks for, in the leading position. Rendered only in selection mode so
        // the ordinary list keeps its artwork-first anatomy; `square`/`square-check` have been on
        // §18's allow-list for exactly this since it was written, with no call site until now.
        if (inSelectionMode) {
            PodsiloIcon(
                icon = if (selected) PodsiloIcons.Checked else PodsiloIcons.Unchecked,
                contentDescription = if (selected) "Selected" else "Not selected",
            )
        }
        // Leading artwork, per UI.adoc §5's row anatomy — the episode's own image when the feed
        // supplied one, otherwise the podcast's. `EpisodeUi.artworkUrl` already resolves that.
        PodsiloArtwork(url = episode.artworkUrl, title = episode.title)
        EpisodeRowBody(episode, zone, Modifier.weight(1f))
        // Trailing `⋮`, closing §5's row anatomy. Hidden in selection mode, where the row's job is
        // to be selected and a per-row menu would compete with the selection bar's actions.
        if (!inSelectionMode) EpisodeOverflow(episode, onEvent)
    }
}

/**
 * The row overflow `UI.adoc` §5 specifies, and §12.1 calls a **mandatory** non-gesture equivalent
 * of the swipes — which did not exist. The row rendered its applicable actions as inline
 * `TextButton`s instead, and two actions had no row-level call site at all because `labelFor`
 * returned `null` for them: *Copy episode link* and *Open in browser* were reachable only from S3.
 *
 * Built from [EpisodeUi.actions] and nothing else — no `when (state)` here. That set is computed
 * once in the view model, so this menu, the swipe label and the accessibility actions cannot
 * disagree about what an episode currently offers (§12.6).
 *
 * **This replaces the inline buttons**, which §5's row anatomy never had: it ends at
 * "status badge/progress, overflow `⋮`". A row with two or three buttons in it also crowds out the
 * description snippet at large font scales, which §12.12 asks to keep readable.
 */
@Composable
private fun EpisodeOverflow(
    episode: EpisodeUi,
    onEvent: (EpisodeListEvent) -> Unit,
) {
    if (episode.actions.isEmpty()) return
    var expanded by remember { mutableStateOf(false) }

    IconButton(
        onClick = { expanded = true },
        modifier = Modifier.sizeIn(minHeight = MinTouchTarget),
    ) {
        PodsiloIcon(PodsiloIcons.Overflow, contentDescription = "Actions for ${episode.title}")
    }
    DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
        episode.actions.forEach { action ->
            DropdownMenuItem(
                text = { Text(action.menuLabelFor(episode)) },
                onClick = {
                    expanded = false
                    onEvent(EpisodeListEvent.Triage(episode.episodeKey, action))
                },
            )
        }
    }
}

/**
 * Every action's menu label — unlike [labelFor], this returns a string for **all** of them, because
 * a menu that silently drops an item the view model offered is how *Copy episode link* stayed
 * unreachable from the list.
 */
internal fun EpisodeUiAction.menuLabelFor(episode: EpisodeUi): String =
    when (this) {
        EpisodeUiAction.OPEN_IN_BROWSER -> "Open in browser"
        EpisodeUiAction.COPY_LINK -> "Copy episode link"
        else -> labelFor(episode) ?: name
    }

/** The text column beside the artwork — split out only so [EpisodeRow] stays readable. */
@Composable
private fun EpisodeRowBody(
    episode: EpisodeUi,
    zone: ZoneId,
    modifier: Modifier = Modifier,
) {
    Column(modifier = modifier) {
        Text(
            text = episode.title,
            style = MaterialTheme.typography.titleMedium,
            // The title truncates first; the decision affordances never do (UI.adoc §12.12).
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
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                episode.statusIcon()?.let { PodsiloIcon(it, contentDescription = null) }
                Text(
                    status,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
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
    }
}

/**
 * A percentage is only ever drawn from an update seen **in this process** (`UI.adoc` Part B
 * §7). After process death WorkManager's progress is gone, so a `DOWNLOADING` row with none reads
 * *resuming* rather than implying it knows how far along it is.
 */
@Composable
internal fun DownloadProgressBar(progress: DownloadProgress?) {
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
