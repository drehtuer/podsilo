// SPDX-License-Identifier: GPL-3.0-or-later

package net.drehtuer.podsilo.feature.settings

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.sizeIn
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType

/**
 * S5 (`docs/UI.md` §8). One field, and **no password anywhere** — the whole point of Login Flow v2
 * is that the user signs in on their own Nextcloud, in a browser, and the app only ever holds the
 * app password that comes back.
 */
@Composable
fun ConnectDialog(
    state: ConnectUiState,
    onEvent: (ConnectEvent) -> Unit,
    modifier: Modifier = Modifier,
) {
    AlertDialog(
        modifier = modifier,
        // Not dismissable from outside while a request is in flight (§8).
        onDismissRequest = { if (!state.isBusy) onEvent(ConnectEvent.Cancel) },
        title = { Text(if (state.isChangingExisting) "Change Nextcloud instance" else "Connect Nextcloud") },
        text = {
            Column {
                OutlinedTextField(
                    value = state.host,
                    onValueChange = { onEvent(ConnectEvent.HostChanged(it)) },
                    label = { Text("Nextcloud address") },
                    // The prefix is rendered, not typed: a pasted scheme is stripped on submit.
                    prefix = { Text("https://") },
                    singleLine = true,
                    readOnly = state.isBusy,
                    isError = state.inlineError != null,
                    keyboardOptions =
                        KeyboardOptions(keyboardType = KeyboardType.Uri, imeAction = ImeAction.Go),
                    keyboardActions = KeyboardActions(onGo = { onEvent(ConnectEvent.Submit) }),
                    modifier = Modifier.fillMaxWidth(),
                )
                Text(
                    "Only the server address — you sign in on your Nextcloud in the next step.",
                    style = MaterialTheme.typography.bodySmall,
                )
                state.inlineError?.let {
                    Text(
                        text = it.message,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                    )
                }
                if (state.isChangingExisting) {
                    Text(
                        "Your download history is kept, so episodes you already handled stay handled.",
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
                state.busyLabel?.let {
                    CircularProgressIndicator(modifier = Modifier.semantics { contentDescription = it })
                    Text(it, style = MaterialTheme.typography.bodySmall)
                }
            }
        },
        confirmButton = {
            if (!state.isBusy) {
                TextButton(
                    onClick = { onEvent(ConnectEvent.Submit) },
                    modifier = Modifier.sizeIn(minHeight = MinTouchTarget),
                ) { Text("Request authorization") }
            }
        },
        dismissButton = {
            // Stays enabled while busy — it is what aborts the poll.
            TextButton(
                onClick = { onEvent(ConnectEvent.Cancel) },
                modifier = Modifier.sizeIn(minHeight = MinTouchTarget),
            ) { Text("Cancel") }
        },
    )
}

/** `null` while editing. Each phase says what it is waiting for rather than showing a bare spinner. */
internal val ConnectUiState.busyLabel: String?
    get() =
        when (phase) {
            ConnectUiState.Phase.Editing -> null
            ConnectUiState.Phase.RequestingFlow -> "Contacting the server…"
            ConnectUiState.Phase.AwaitingAuthorization -> "Waiting for authorization in your browser…"
            ConnectUiState.Phase.VerifyingGpodderSync -> "Checking for GPodder Sync…"
        }

/** Plain language, one sentence, never a stack trace — the table in `docs/UI.md` §8. */
internal val ConnectError.message: String
    get() =
        when (this) {
            ConnectError.UNREACHABLE -> "Can't reach that address. Check the spelling and your network."
            ConnectError.TLS -> "The server's certificate isn't trusted."
            ConnectError.NOT_NEXTCLOUD -> "This doesn't look like a Nextcloud server."
            ConnectError.NO_GPODDERSYNC -> "This Nextcloud doesn't have the GPodder Sync app installed."
            ConnectError.UNAUTHORIZED -> "Authorization was refused. Try again."
            ConnectError.ABANDONED -> "Authorization wasn't completed."
        }
