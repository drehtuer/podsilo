// SPDX-License-Identifier: GPL-3.0-or-later

package net.drehtuer.podsilo.feature.episodes

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.sizeIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.platform.UriHandler
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import net.drehtuer.podsilo.core.model.LedgerState
import net.drehtuer.podsilo.core.ui.MinTouchTarget
import net.drehtuer.podsilo.core.ui.PodsiloIcon
import net.drehtuer.podsilo.core.ui.PodsiloIcons
import net.drehtuer.podsilo.core.ui.RowPadding
import java.time.ZoneId

/**
 * S3 — the episode detail sheet (`docs/UI.adoc` §6).
 *
 * A **full screen**, open for every episode regardless of state including the
 * de-emphasised ones. Its buttons come from [EpisodeUi.actions] and its labels from the same
 * [labelFor] the row uses, so the sheet and the row it opened from cannot offer different actions
 * (`docs/UI.adoc` §12.6) — which is also why *Choose folder* replaces *Retry* here too.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun EpisodeDetailSheet(
    state: EpisodeDetailUiState,
    onEvent: (EpisodeDetailEvent) -> Unit,
    modifier: Modifier = Modifier,
    zone: ZoneId = ZoneId.systemDefault(),
) {
    // A FULL SCREEN, NOT A BOTTOM SHEET.
    //
    // It was a `ModalBottomSheet` rendered inside a full-screen `composable` navigation destination,
    // which is a contradiction: the destination owns the whole window and had nothing in it, so the
    // sheet floated over an empty page. Dragging the sheet down dismissed it and revealed that empty
    // page — the "pull down leads to a white screen" the author reported. Nothing had navigated
    // away, so `Dismissed` never popped the backstack either; the screen was simply blank.
    //
    // Made a real screen rather than teaching the sheet to survive a drag: show notes run to
    // paragraphs and the sheet was already `skipPartiallyExpanded`, i.e. always full height. It was
    // a full screen wearing a sheet's clothes. `docs/UI.adoc` §6 amended to match.
    Scaffold(
        modifier = modifier,
        topBar = {
            TopAppBar(
                // No title: the header immediately below already names the podcast, the date and the
                // duration, and repeating the feed title in the bar just spends the one line a long
                // episode title needs. The bar is here for the back affordance.
                title = {},
                navigationIcon = {
                    IconButton(
                        onClick = { onEvent(EpisodeDetailEvent.Dismissed) },
                        modifier = Modifier.sizeIn(minHeight = MinTouchTarget),
                    ) {
                        PodsiloIcon(PodsiloIcons.Back, contentDescription = "Back")
                    }
                },
            )
        },
    ) { padding ->
        Column(
            modifier =
                Modifier
                    .padding(padding)
                    .padding(horizontal = RowPadding)
                    .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(SheetSpacing),
        ) {
            SheetHeader(state.episode, zone)
            StatusSection(state, onEvent)
            Description(state.descriptionHtml, onEvent)
            state.episodePageUrl?.let { EpisodePageRow(onEvent) }
            DetailActions(state.episode, onEvent)
        }
    }
}

private val SheetSpacing = 12.dp

@Composable
private fun SheetHeader(
    episode: EpisodeUi,
    zone: ZoneId,
) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(episode.title, style = MaterialTheme.typography.titleMedium)
        Text(
            // The feed's own title, then date and duration — each part omitted when absent rather
            // than filled in with a placeholder (docs/UI.adoc §5).
            text =
                listOfNotNull(episode.feedTitle, episode.metaLine(zone).takeIf { it.isNotEmpty() })
                    .joinToString(" · "),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun StatusSection(
    state: EpisodeDetailUiState,
    onEvent: (EpisodeDetailEvent) -> Unit,
) {
    val episode = state.episode
    if (episode.ledgerState == LedgerState.DOWNLOADING) DownloadProgressBar(episode.progress)

    // Where the file went. Reported from what we wrote, never from looking in the folder: the
    // player owns that file and may already have deleted it (CLAUDE.md §11).
    state.deliveredTo?.let {
        Text(it, style = MaterialTheme.typography.bodyMedium)
    } ?: episode.statusLine()?.let {
        Text(it, style = MaterialTheme.typography.bodyMedium)
    }

    if (episode.ledgerState == LedgerState.ERROR) {
        TextButton(
            onClick = { onEvent(EpisodeDetailEvent.ErrorDetailsClicked) },
            modifier = Modifier.sizeIn(minHeight = MinTouchTarget),
        ) { Text("Error details") }
    }
}

@Composable
private fun Description(
    raw: String,
    onEvent: (EpisodeDetailEvent) -> Unit,
) {
    // Sanitised at render, never at write (architecture §4). `remember` because parsing hostile
    // feed HTML on every recomposition of a scrolling sheet is the wasteful version of correct.
    val text = remember(raw) { sanitizeEpisodeHtml(raw) }
    if (text.isEmpty()) return

    // Overriding the handler rather than baking a listener into the AnnotatedString keeps
    // `sanitizeEpisodeHtml` a pure, table-testable function, and routes taps through the view model
    // so the host can open a Custom Tab. The URLs are http(s)-only — the sanitiser guarantees it.
    val handler =
        remember(onEvent) {
            object : UriHandler {
                override fun openUri(uri: String) = onEvent(EpisodeDetailEvent.LinkClicked(uri))
            }
        }
    CompositionLocalProvider(LocalUriHandler provides handler) {
        Text(
            text = text,
            style = MaterialTheme.typography.bodyMedium,
            modifier = Modifier.fillMaxWidth(),
        )
    }
}

@Composable
private fun EpisodePageRow(onEvent: (EpisodeDetailEvent) -> Unit) {
    HorizontalDivider()
    TextButton(
        onClick = { onEvent(EpisodeDetailEvent.OpenInBrowserClicked) },
        modifier = Modifier.fillMaxWidth().sizeIn(minHeight = MinTouchTarget),
    ) {
        Text(
            text = "Open episode page in browser",
            modifier = Modifier.fillMaxWidth(),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

@Composable
private fun DetailActions(
    episode: EpisodeUi,
    onEvent: (EpisodeDetailEvent) -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(bottom = RowPadding),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        episode.actions.forEach { action ->
            val label = action.labelFor(episode) ?: return@forEach
            TextButton(
                onClick = { onEvent(EpisodeDetailEvent.Triage(action)) },
                modifier = Modifier.sizeIn(minHeight = MinTouchTarget),
            ) { Text(label) }
        }
    }
}
