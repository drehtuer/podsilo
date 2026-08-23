// SPDX-License-Identifier: GPL-3.0-or-later

package net.drehtuer.podsilo.feature.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.launch
import net.drehtuer.podsilo.core.model.port.LogCategory
import net.drehtuer.podsilo.core.model.port.LogRepository
import net.drehtuer.podsilo.core.model.port.LoginFlow
import net.drehtuer.podsilo.core.model.port.LoginFlowException
import net.drehtuer.podsilo.core.model.port.LoginFlowFailure
import net.drehtuer.podsilo.core.model.port.NewLogEntry
import net.drehtuer.podsilo.core.model.port.NextcloudCredentials
import net.drehtuer.podsilo.core.model.port.NextcloudLoginFlowClient
import net.drehtuer.podsilo.core.model.port.SettingsRepository
import net.drehtuer.podsilo.core.model.port.SyncTrigger

/**
 * S5 — the Nextcloud connection dialog (`UI.adoc` §8).
 *
 * **Login Flow v2 exclusively.** The app never sees, asks for, or stores a user password; what it
 * persists is the app password the flow hands back (CLAUDE.md §5, `architecture.adoc` §2). There is
 * no username/password form in this module and there must never be one.
 *
 * The order below is load-bearing: **success is claimed only after the authenticated
 * `GET /subscriptions` returns 200.** A completed login flow proves the server is a Nextcloud and
 * the password works; it says nothing about gpoddersync being installed. On any failure the app
 * password is discarded rather than stored.
 *
 * And even then it is not stored: **the user confirms the account first**
 * ([ConnectUiState.Phase.ConfirmingAccount], `UI.adoc` §8). The flow returns whichever
 * account the *browser* was signed into, which is not a choice the app gets to make or even
 * influence — so the one thing it can do is show the name before acting on it.
 */
@Suppress("TooManyFunctions") // One private function per user-visible step of the flow; see the KDoc.
class ConnectViewModel(
    private val loginFlowClient: NextcloudLoginFlowClient,
    private val settingsRepository: SettingsRepository,
    private val syncTrigger: SyncTrigger,
    private val logRepository: LogRepository,
) : ViewModel() {
    private val _state = MutableStateFlow(ConnectUiState())
    val state: StateFlow<ConnectUiState> = _state.asStateFlow()

    private val effects = Channel<ConnectEffect>(Channel.BUFFERED)
    val effect: Flow<ConnectEffect> = effects.receiveAsFlow()

    private var flowJob: Job? = null

    /** The poll, kept separate from [flowJob] so foreground changes can stop it without losing the flow. */
    private var pollJob: Job? = null

    /**
     * The started-but-not-yet-granted flow, held across a trip to the browser.
     *
     * Non-null exactly while we are waiting for the user to grant access. It survives backgrounding
     * — that is the whole point — and is cleared once the grant lands, or when the attempt is
     * cancelled or restarted.
     */
    private var pendingFlow: LoginFlow? = null

    /**
     * Whether the connection UI is on screen. **Starts `false`**: nothing may poll until the host
     * has said we are visible, which it does on `ON_START`.
     */
    private var isForeground: Boolean = false

    /**
     * The granted credentials, held **only** between the grant and the user confirming the account.
     *
     * Deliberately not in [ConnectUiState]: that is a data class whose `toString` a crash reporter,
     * a log line or a Compose state inspector will happily print, and it carries the app password
     * (CLAUDE.md §5). The UI is given the login name and nothing else.
     */
    private var pendingCredentials: NextcloudCredentials? = null

    /** Pre-fills the field when changing an existing instance, and re-titles the dialog. */
    fun prefillFromCurrentAccount() {
        viewModelScope.launch {
            val account = settingsRepository.observeNextcloudAccount().first()
            if (account != null) {
                _state.value = _state.value.copy(host = account.serverUrl.withoutScheme(), isChangingExisting = true)
            }
        }
    }

    fun onEvent(event: ConnectEvent) {
        when (event) {
            is ConnectEvent.HostChanged ->
                // Edits are ignored while a request is in flight: the field is read-only then, and
                // accepting a change would leave the poll running against a different host than the
                // one on screen.
                if (_state.value.phase == ConnectUiState.Phase.Editing) {
                    _state.value =
                        _state.value.copy(
                            host = event.value,
                            // Checked on every keystroke, which is the whole point: a space in an
                            // address is invisible, and reporting it only after a request that never
                            // leaves the device sends the reader to look at their network. An empty
                            // field is not a mistake yet, so it stays silent.
                            inlineError =
                                event.value
                                    .trim()
                                    .takeIf { it.isNotEmpty() }
                                    ?.let(::hostProblem),
                        )
                }
            ConnectEvent.Submit -> submit()
            ConnectEvent.Cancel -> cancel()
            ConnectEvent.ConfirmAccount -> confirmAccount()
            ConnectEvent.RejectAccount -> rejectAccount()
            // Suspends the poll while the user is away and resumes it when they return. The resume
            // is what completes a normal login: they grant access in the browser, come back, and the
            // first attempt after that finds the flow granted. Nothing is lost by not having asked
            // in the meantime.
            is ConnectEvent.ForegroundChanged -> {
                isForeground = event.inForeground
                if (event.inForeground) {
                    startPollingIfForeground()
                } else {
                    pollJob?.cancel()
                    pollJob = null
                }
            }
        }
    }

    /** Idempotent: a second call while a poll is already running is a no-op, not a second poll. */
    private fun startPollingIfForeground() {
        val flow = pendingFlow ?: return
        if (!isForeground || pollJob?.isActive == true) return
        pollJob = viewModelScope.launch { awaitGrant(flow) }
    }

    /** The only path that stores credentials. */
    private fun confirmAccount() {
        val credentials = pendingCredentials ?: return
        pendingCredentials = null
        viewModelScope.launch {
            settingsRepository.setNextcloudCredentials(credentials)
            syncTrigger.requestSyncNow()
            effects.trySend(ConnectEffect.Connected)
        }
    }

    /**
     * Discards the app password **without storing it** and opens the server so the user can log out
     * there.
     *
     * That detour is the actual fix, unintuitive as it looks: the flow has no account chooser, so a
     * second attempt against a live browser session returns the same account however many times it is
     * retried. The session is the thing to change, and only the browser can change it.
     *
     * The password Nextcloud already issued is **revoked** rather than abandoned — see
     * [discardPendingCredentials]. The UI does not wait for that.
     */
    private fun rejectAccount() {
        val server = pendingCredentials?.serverUrl
        discardPendingCredentials()
        _state.value =
            _state.value.copy(phase = ConnectUiState.Phase.Editing, showSwitchAccountHint = true)
        server?.let { effects.trySend(ConnectEffect.OpenBrowser(it)) }
    }

    /**
     * Forgets the granted-but-unconfirmed credentials, and asks the server to delete the app
     * password it already issued.
     *
     * Nextcloud hands the password over *before* the user is asked "is this the right account?", so
     * every decline used to leave a live password listed under *Security* belonging to an account the
     * user had just refused. Deleting it needs that same password to authenticate, which is why this
     * is the last possible moment to do it — after this returns, the app cannot.
     *
     * **Nothing waits for it.** The caller's contract here is to store nothing and move on, so the
     * request is launched and the state transition happens regardless. A server that is unreachable,
     * or old enough not to have the endpoint, leaves exactly the harmless, hand-revocable leftover
     * that existed before this method — which is why the failure is a log line and not a dialog.
     */
    private fun discardPendingCredentials() {
        val credentials = pendingCredentials ?: return
        pendingCredentials = null
        viewModelScope.launch {
            loginFlowClient.revokeAppPassword(credentials).onFailure {
                logRepository.record(
                    NewLogEntry(
                        category = LogCategory.AUTH,
                        message =
                            "The app password for the account you declined could not be deleted " +
                                "from Nextcloud. It is unused here; remove it under Settings → " +
                                "Security if you want it gone.",
                        detail = it.message,
                    ),
                )
            }
        }
    }

    private fun submit() {
        val host = _state.value.host.trim()
        val invalid = hostProblem(host)
        if (invalid != null) {
            return fail(invalid, "host '$host' rejected before contacting anything")
        }
        flowJob?.cancel()
        pollJob?.cancel()
        pendingFlow = null
        // Re-submitting from the confirmation screen abandons that grant just as surely as declining
        // it does, so it is revoked on the same terms.
        discardPendingCredentials()
        flowJob = viewModelScope.launch { connect(normaliseHost(host)) }
    }

    /**
     * Aborts the poll. Cancelling the coroutine is what stops it — the client's contract is that a
     * cancelled poll simply stops asking, so there is nothing else to unwind.
     */
    private fun cancel() {
        flowJob?.cancel()
        flowJob = null
        pollJob?.cancel()
        pollJob = null
        pendingFlow = null
        // Backing out of the confirmation must not leave a granted password sitting in memory for a
        // later Submit to pick up and store against a host the user has since retyped — and the copy
        // on the server is the same leftover a decline would have left, so it goes the same way.
        discardPendingCredentials()
        if (_state.value.phase == ConnectUiState.Phase.Editing) {
            effects.trySend(ConnectEffect.Dismiss)
        } else {
            _state.value = _state.value.copy(phase = ConnectUiState.Phase.Editing)
        }
    }

    /**
     * Starts the flow and hands the user to their browser. **It does not poll** — see [awaitGrant].
     */
    private suspend fun connect(baseUrl: String) {
        _state.value =
            _state.value.copy(
                phase = ConnectUiState.Phase.RequestingFlow,
                inlineError = null,
                showSwitchAccountHint = false,
            )

        val flow =
            loginFlowClient
                .start(
                    baseUrl,
                ).getOrElse { return fail(it.asConnectError(ConnectError.NOT_NEXTCLOUD), it.message) }

        pendingFlow = flow
        effects.trySend(ConnectEffect.OpenBrowser(flow.loginUrl))
        _state.value = _state.value.copy(phase = ConnectUiState.Phase.AwaitingAuthorization)

        // Polling starts only if we are still on screen. The usual case is that we are not for long:
        // `OpenBrowser` is about to put the browser in front of us, `ForegroundChanged(false)`
        // arrives, and the poll waits for the user to come back (decisions/0020).
        startPollingIfForeground()
    }

    /**
     * Polls until the user grants access, then verifies and asks them to confirm the account.
     *
     * **Runs only while the app is in the foreground** (`decisions/0020`). Login Flow v2 is a
     * loop that watches for something the *user* does in a browser, and its result is only useful
     * once they come back here — so polling behind their back buys nothing and costs the one thing
     * that broke it: a backgrounded process on Android 17 could not resolve the host, and a single
     * `UnknownHostException` killed the whole flow while the browser was still saying "access
     * granted".
     *
     * `@Suppress("ReturnCount")`: the returns are the ways this sequence must stop *without storing
     * anything*. Flattening them into nested `if`s would bury the one rule that matters — that
     * nothing is persisted until the last step succeeds.
     */
    @Suppress("ReturnCount")
    private suspend fun awaitGrant(flow: LoginFlow) {
        // Cancellation propagates on its own: the client's contract is that a cancelled poll simply
        // stops asking, so backgrounding and Cancel both need no unwinding here.
        val result =
            loginFlowClient
                .poll(
                    flow,
                ).getOrElse { return fail(it.asConnectError(ConnectError.ABANDONED), it.message) }

        pendingFlow = null
        _state.value = _state.value.copy(phase = ConnectUiState.Phase.VerifyingGpodderSync)
        loginFlowClient.verifyGpodderSync(result.credentials).getOrElse {
            // The password is *not* stored: connecting to a Nextcloud without gpoddersync would
            // leave the user with an app that silently syncs nothing (UI.adoc §8).
            return fail(it.asConnectError(ConnectError.NO_GPODDERSYNC), it.message)
        }

        // STILL NOT STORED. The flow proved the server is a Nextcloud with gpoddersync and handed
        // back a working app password — it did not prove this is the account the user meant. Login
        // Flow v2 has no account chooser, so a browser already signed in as someone else grants that
        // someone else silently, and the first the user would hear of it is their other account's
        // episodes going missing. `Phase.ConfirmingAccount` names the account and waits.
        //
        // The credentials carry the server's own canonical URL rather than the typed one — a
        // Nextcloud behind a reverse proxy legitimately returns a different host.
        pendingCredentials = result.credentials
        _state.value = _state.value.copy(phase = ConnectUiState.Phase.ConfirmingAccount(result.loginName))
    }

    /**
     * Shows the sentence, and **records the reason**.
     *
     * The dialog has room for one plain sentence, which is correct for it and useless for
     * diagnosis: "Can't reach that address" is the same six words whether DNS failed, the server
     * answered on a host the phone cannot route to, or Android refused a cleartext URL the server
     * asked for. `UI.adoc` §8 has said all along that these are "each also written to S8"; they
     * were not. Now the underlying message — which names the host and the actual failure — lands in
     * the error log, where it can be read, copied and shared.
     *
     * [detail] comes from an exception message, never from the credentials: the app password travels
     * in an `Authorization` header, not in any URL that could end up here (`LogRepository.record`).
     */
    private fun fail(
        error: ConnectError,
        detail: String?,
    ) {
        _state.value = _state.value.copy(phase = ConnectUiState.Phase.Editing, inlineError = error)
        viewModelScope.launch {
            logRepository.record(
                NewLogEntry(
                    category = LogCategory.AUTH,
                    message = "Connecting to Nextcloud failed: ${error.name}",
                    detail = detail,
                ),
            )
        }
    }
}

/**
 * Validation happens on submit only, so typing never fights the user (`UI.adoc` §8).
 *
 * `null` means "good enough to try" — the server is the real authority on whether it exists, and
 * guessing harder here would only reject valid setups.
 */
internal fun hostProblem(host: String): ConnectError? =
    when {
        host.isBlank() -> ConnectError.ADDRESS_INVALID
        host.any { it.isWhitespace() } -> ConnectError.ADDRESS_HAS_SPACE
        // Before parsing, because a scheme with nothing after it survives [normaliseHost] as a
        // *host*: "https://" loses its slashes to `trimEnd('/')`, no longer matches the prefix
        // test, and is re-prefixed into "https://https:" — which parses, with host "https".
        host.withoutScheme().isBlank() -> ConnectError.ADDRESS_INVALID
        !parsesAsAHost(host) -> ConnectError.ADDRESS_INVALID
        else -> null
    }

/**
 * Whether a host can be parsed out of what was typed.
 *
 * `java.net.URI` rather than a regex of our own: hostnames, IPv4 literals, bracketed IPv6, ports and
 * a subdirectory install are all legal here, and the set of things that are *not* is far harder to
 * write down than to delegate. A `null` authority is the answer to "there is no host in this".
 *
 * Deliberately permissive about what it accepts — a single-label LAN name like `nextcloud` is a real
 * setup, so this rejects only what cannot be an address at all. The check exists to make a typo
 * visible immediately, not to have opinions about the author's network.
 */
private fun parsesAsAHost(host: String): Boolean =
    runCatching { java.net.URI(normaliseHost(host)).host != null }.getOrDefault(false)

/**
 * A typed scheme is **stripped, not rejected** (`UI.adoc` §8), and https is then assumed: the
 * field renders a fixed `https://` prefix, so a pasted `https://cloud.example.org` must not become
 * `https://https://…`. A deliberate `http://` is honoured — a self-hosted instance on a LAN is a
 * real setup, and silently upgrading it would fail confusingly.
 */
internal fun normaliseHost(host: String): String {
    val trimmed = host.trim().trimEnd('/')
    return when {
        trimmed.startsWith("http://") || trimmed.startsWith("https://") -> trimmed
        else -> "https://$trimmed"
    }
}

private fun String.withoutScheme(): String = removePrefix("https://").removePrefix("http://")

/**
 * Maps the client's typed failure onto the message S5 shows.
 *
 * [fallback] is what an *untyped* failure degrades to — the step's most likely cause. This mapping
 * exists because collapsing every failure into one message is exactly the bug `UI.adoc` §8's
 * table was written to prevent: a mistyped host reported as "this doesn't look like a Nextcloud
 * server" sends the user to check their server instead of their spelling. Found by running the
 * manual probe against an address that does not resolve.
 */
internal fun Throwable.asConnectError(fallback: ConnectError): ConnectError =
    when ((this as? LoginFlowException)?.failure) {
        LoginFlowFailure.UNREACHABLE -> ConnectError.UNREACHABLE
        LoginFlowFailure.TIMED_OUT -> ConnectError.TIMED_OUT
        LoginFlowFailure.CLEARTEXT_BLOCKED -> ConnectError.CLEARTEXT_BLOCKED
        LoginFlowFailure.TLS -> ConnectError.TLS
        LoginFlowFailure.NOT_NEXTCLOUD -> ConnectError.NOT_NEXTCLOUD
        LoginFlowFailure.NO_GPODDERSYNC -> ConnectError.NO_GPODDERSYNC
        LoginFlowFailure.UNAUTHORIZED -> ConnectError.UNAUTHORIZED
        LoginFlowFailure.ABANDONED -> ConnectError.ABANDONED
        null -> fallback
    }
