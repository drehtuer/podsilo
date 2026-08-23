// SPDX-License-Identifier: GPL-3.0-or-later

package net.drehtuer.podsilo.feature.settings

import androidx.compose.foundation.layout.Column
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable

/**
 * Shown **before** the file picker, not after: the warning is about what a restore does at all, and
 * nothing in it depends on which file gets chosen.
 *
 * This follows the same principle as [BulkPreviewDialog] (`decisions/0013`) — a destructive,
 * non-undoable operation says in words what it will do before it does it. What is at stake here is
 * the ledger: everything else on the device can be rebuilt from Nextcloud and the feeds, but "which
 * episodes have I already handled" is the app's own memory.
 *
 * The last line is the genuinely reassuring part and is true rather than soothing: the action log on
 * the server is untouched by a restore, and because the restored `SyncState` carries the archive's
 * older `since` cursor, the next sync pulls everything that happened after the backup and folds it
 * back in.
 */
@Composable
internal fun RestoreWarningDialog(onEvent: (SettingsEvent) -> Unit) {
    AlertDialog(
        onDismissRequest = { onEvent(SettingsEvent.RestoreCancelled) },
        title = { Text("Replace everything with a backup?") },
        text = {
            Column {
                Text(
                    "Your podcasts, episodes and download history on this device are replaced by " +
                        "the contents of the backup file. This cannot be undone.",
                    style = MaterialTheme.typography.bodyMedium,
                )
                Text(
                    "Your Nextcloud account is not touched. The next sync pulls back anything that " +
                        "happened after the backup was made.",
                    style = MaterialTheme.typography.bodySmall,
                )
            }
        },
        confirmButton = {
            TextButton(onClick = { onEvent(SettingsEvent.RestoreConfirmed) }) { Text("Choose backup file") }
        },
        dismissButton = {
            TextButton(onClick = { onEvent(SettingsEvent.RestoreCancelled) }) { Text("Cancel") }
        },
    )
}
