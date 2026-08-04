// SPDX-License-Identifier: GPL-3.0-or-later

package net.drehtuer.podsilo.feature.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import net.drehtuer.podsilo.core.ui.MinTouchTarget
import net.drehtuer.podsilo.core.ui.PodsiloIcon
import net.drehtuer.podsilo.core.ui.PodsiloIcons

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
    val confirming = state.confirming
    AlertDialog(
        modifier = modifier,
        // Not dismissable from outside while a request is in flight (§8).
        onDismissRequest = { if (!state.isBusy) onEvent(ConnectEvent.Cancel) },
        title = {
            Text(
                when {
                    confirming != null -> "Connect as ${confirming.loginName}?"
                    state.isChangingExisting -> "Change Nextcloud instance"
                    else -> "Connect Nextcloud"
                },
            )
        },
        text = { if (confirming != null) AccountConfirmation() else ConnectBody(state, onEvent) },
        confirmButton = {
            when {
                confirming != null ->
                    TextButton(
                        onClick = { onEvent(ConnectEvent.ConfirmAccount) },
                        modifier = Modifier.sizeIn(minHeight = MinTouchTarget),
                    ) { Text("Connect") }
                !state.isBusy ->
                    TextButton(
                        onClick = { onEvent(ConnectEvent.Submit) },
                        modifier = Modifier.sizeIn(minHeight = MinTouchTarget),
                    ) { Text("Request authorization") }
                // Mid-flight: Cancel is the only button, as before.
                else -> Unit
            }
        },
        dismissButton = {
            // Stays enabled while busy — it is what aborts the poll.
            TextButton(
                onClick = {
                    onEvent(if (confirming != null) ConnectEvent.RejectAccount else ConnectEvent.Cancel)
                },
                modifier = Modifier.sizeIn(minHeight = MinTouchTarget),
            ) { Text(if (confirming != null) "Use a different account" else "Cancel") }
        },
    )
}

/**
 * Why an account the user never picked can turn up, and what to do about it.
 *
 * The name itself is in the dialog title, where it is the question being asked rather than a detail
 * inside a paragraph. What is left here is the part that is genuinely surprising: the account came
 * from the *browser's* session, so retrying without logging out returns the same one.
 */
@Composable
private fun AccountConfirmation() {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(
            "This is the account your browser was signed in to. Nextcloud doesn't offer a choice " +
                "here, so check the name before connecting.",
            style = MaterialTheme.typography.bodyMedium,
        )
        Text(
            "Podsilo will mark episodes as downloaded and played in this account.",
            style = MaterialTheme.typography.bodySmall,
        )
    }
}

/** `null` while editing. Each phase says what it is waiting for rather than showing a bare spinner. */
internal val ConnectUiState.busyLabel: String?
    get() =
        when (phase) {
            ConnectUiState.Phase.Editing -> null
            ConnectUiState.Phase.RequestingFlow -> "Contacting the server…"
            ConnectUiState.Phase.AwaitingAuthorization -> "Waiting for authorization in your browser…"
            ConnectUiState.Phase.VerifyingGpodderSync -> "Checking for GPodder Sync…"
            // Not busy — it is waiting for the user, and the dialog shows the account instead.
            is ConnectUiState.Phase.ConfirmingAccount -> null
        }

/** Plain language, one sentence, never a stack trace — the table in `docs/UI.md` §8. */
internal val ConnectError.message: String
    get() =
        when (this) {
            ConnectError.UNREACHABLE -> "Can't reach that address. Check the spelling and your network."
            // Names the likeliest cause, because it is one the user can act on by waiting. Nextcloud
            // slows repeated authorization attempts down deliberately, and the old wording sent
            // people to re-check an address that was never wrong.
            ConnectError.TIMED_OUT ->
                "The server didn't answer in time. Nextcloud slows down repeated login attempts — " +
                    "wait a minute and try again."
            ConnectError.TLS -> "The server's certificate isn't trusted."
            ConnectError.NOT_NEXTCLOUD -> "This doesn't look like a Nextcloud server."
            ConnectError.NO_GPODDERSYNC -> "This Nextcloud doesn't have the GPodder Sync app installed."
            ConnectError.UNAUTHORIZED -> "Authorization was refused. Try again."
            ConnectError.ABANDONED -> "Authorization wasn't completed."
        }

/**
 * The field and everything under it. Split out because the dialog's own job is the frame — title,
 * the two buttons, and the rule that it cannot be dismissed while a request is in flight.
 */
@Composable
private fun ConnectBody(
    state: ConnectUiState,
    onEvent: (ConnectEvent) -> Unit,
) {
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
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                // Input the user can fix, not a condition the app is in — swapping these
                // two makes a typo look like a system fault (docs/UI.md §18).
                PodsiloIcon(
                    PodsiloIcons.InputError,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.error,
                )
                Text(
                    text = it.message,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                )
            }
        }
        if (state.showSwitchAccountHint) {
            // Shown after *Use a different account*, next to the address field the user is about to
            // resubmit — because the useful instruction is what to do in the browser tab that just
            // opened, and repeating the request from here without logging out returns the same
            // account (docs/decisions/0019).
            Text(
                "Log out of Nextcloud in the browser that just opened, then request authorization " +
                    "again to sign in as someone else.",
                style = MaterialTheme.typography.bodySmall,
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
}
