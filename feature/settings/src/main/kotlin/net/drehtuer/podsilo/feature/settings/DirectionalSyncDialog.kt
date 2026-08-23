// SPDX-License-Identifier: GPL-3.0-or-later

package net.drehtuer.podsilo.feature.settings

import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable

/**
 * The confirmation both directional passes go through (`decisions/0025`).
 *
 * **The push names its count**, because it writes to a shared, append-only log that other clients act
 * on and that the API cannot retract — the same safeguard every bulk write in this app carries
 * (`decisions/0013`).
 *
 * **The pull names none**, and that is a deliberate limitation rather than an oversight. The number
 * worth showing would be *how many of these change anything here*, which is only knowable after
 * fetching the log — and a view model does not touch the network (`UI.adoc` §B0.3). Saying what
 * the operation can and cannot do is the honest substitute, and it is a shorter promise than the
 * push's: the pull only ever marks episodes handled.
 */
@Composable
internal fun DirectionalSyncDialog(
    confirmation: DirectionalSyncConfirmation,
    onEvent: (SettingsEvent) -> Unit,
) {
    val pull = confirmation.direction == SyncDirection.PULL
    AlertDialog(
        onDismissRequest = { onEvent(SettingsEvent.DirectionalSyncCancelled) },
        title = {
            Text(
                if (pull) {
                    "Apply Nextcloud's state here?"
                } else {
                    "Send ${confirmation.pushableCount} decisions to Nextcloud?"
                },
            )
        },
        text = {
            Text(
                if (pull) {
                    "Episodes that are played in Nextcloud will be marked played here. " +
                        "Nothing is unmarked, nothing is downloaded, and decisions you have " +
                        "already made on this device are left alone."
                } else {
                    "Every decision made on this device is sent again, including ones Nextcloud " +
                        "has already seen. Your other clients will see them. This cannot be undone."
                },
            )
        },
        confirmButton = {
            TextButton(onClick = { onEvent(SettingsEvent.DirectionalSyncConfirmed) }) {
                Text(if (pull) "Apply" else "Send")
            }
        },
        dismissButton = {
            TextButton(onClick = { onEvent(SettingsEvent.DirectionalSyncCancelled) }) { Text("Cancel") }
        },
    )
}
