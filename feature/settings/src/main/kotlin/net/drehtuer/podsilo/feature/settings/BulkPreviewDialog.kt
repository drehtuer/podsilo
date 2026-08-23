// SPDX-License-Identifier: GPL-3.0-or-later

package net.drehtuer.podsilo.feature.settings

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier

/** More than this and the dialog becomes a list rather than a summary. */
private const val MAX_LISTED_FEEDS = 5

/**
 * The safeguard that replaced the old rule against writing backlog rows at all
 * (`decisions/0013`) — **mandatory, not decoration**.
 *
 * A bulk *mark as played* is not undoable: the `PLAY` actions reach the shared log and the author's
 * other clients act on them. So this names the exact count and the per-feed breakdown, and says in
 * words that the state goes to Nextcloud, **before** anything is written. Do not weaken it.
 */
@Composable
internal fun BulkPreviewDialog(
    confirmation: BulkConfirmation,
    onEvent: (SettingsEvent) -> Unit,
) {
    AlertDialog(
        onDismissRequest = { onEvent(SettingsEvent.BulkCancelled) },
        title = { Text("Mark ${confirmation.count} episodes as played?") },
        text = {
            Column(modifier = Modifier.verticalScroll(rememberScrollState())) {
                confirmation.perFeed.take(MAX_LISTED_FEEDS).forEach {
                    Text("${it.feedTitle}   ${it.count}", style = MaterialTheme.typography.bodyMedium)
                }
                remainderLine(confirmation)?.let {
                    Text(it, style = MaterialTheme.typography.bodyMedium)
                }
                Text(
                    "They move to \"Played / handled\" and can each still be downloaded individually.",
                    style = MaterialTheme.typography.bodySmall,
                )
                Text(
                    // Stated plainly rather than as a warning: sharing triage state across clients
                    // is the point of the app, not a side effect (UI.adoc §7).
                    "Played state is sent to Nextcloud, so your other clients see it too.",
                    style = MaterialTheme.typography.bodySmall,
                )
            }
        },
        confirmButton = {
            TextButton(onClick = { onEvent(SettingsEvent.BulkConfirmed) }) { Text("Mark as played") }
        },
        dismissButton = {
            TextButton(onClick = { onEvent(SettingsEvent.BulkCancelled) }) { Text("Cancel") }
        },
    )
}

/**
 * `null` when everything fits. The remainder is summarised rather than truncated silently, so the
 * listed numbers always add up to the count in the title.
 */
internal fun remainderLine(confirmation: BulkConfirmation): String? {
    val hidden = confirmation.perFeed.drop(MAX_LISTED_FEEDS)
    if (hidden.isEmpty()) return null
    val podcasts = if (hidden.size == 1) "1 more podcast" else "${hidden.size} more podcasts"
    return "… $podcasts   ${hidden.sumOf { it.count }}"
}
