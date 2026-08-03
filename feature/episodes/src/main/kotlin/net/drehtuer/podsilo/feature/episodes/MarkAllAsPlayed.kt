// SPDX-License-Identifier: GPL-3.0-or-later

package net.drehtuer.podsilo.feature.episodes

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.sizeIn
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import net.drehtuer.podsilo.core.ui.MinTouchTarget
import net.drehtuer.podsilo.core.ui.RowPadding

/**
 * *Mark all as played*, on the **Downloaded** filter only.
 *
 * Scoped there deliberately. On *To decide* the equivalent already exists in S4 with a per-feed
 * preview (`docs/decisions/0013`), and on *Played / handled* it would be a no-op. What it answers is
 * the case the author actually hit: a pile of episodes already fetched onto the phone, all of which
 * should stop being offered anywhere else.
 *
 * It confirms first, and that is not decoration — this writes `PLAY` actions to a shared log that
 * other clients act on, and no undo reaches them.
 */
@Composable
internal fun MarkAllRow(
    state: EpisodeListUiState,
    onEvent: (EpisodeListEvent) -> Unit,
) {
    val items = (state.content as? EpisodeListUiState.Content.Episodes)?.items.orEmpty()
    if (state.filter != EpisodeFilter.DOWNLOADED || items.isEmpty()) return

    TextButton(
        onClick = { onEvent(EpisodeListEvent.MarkAllRequested) },
        modifier = Modifier.padding(horizontal = RowPadding).sizeIn(minHeight = MinTouchTarget),
    ) { Text("Mark all ${items.size} as played") }
}

/** Names the count and says where the state goes, exactly as S4's bulk preview does. */
@Composable
internal fun MarkAllDialog(
    keys: List<String>,
    onEvent: (EpisodeListEvent) -> Unit,
) {
    AlertDialog(
        onDismissRequest = { onEvent(EpisodeListEvent.MarkAllDismissed) },
        title = { Text("Mark ${keys.size} downloaded episodes as played?") },
        text = {
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
        },
        confirmButton = {
            TextButton(onClick = { onEvent(EpisodeListEvent.MarkAllConfirmed) }) { Text("Mark as played") }
        },
        dismissButton = {
            TextButton(onClick = { onEvent(EpisodeListEvent.MarkAllDismissed) }) { Text("Cancel") }
        },
    )
}
