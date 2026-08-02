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
import net.drehtuer.podsilo.core.model.port.LoginFlowException
import net.drehtuer.podsilo.core.model.port.LoginFlowFailure
import net.drehtuer.podsilo.core.model.port.NextcloudLoginFlowClient
import net.drehtuer.podsilo.core.model.port.SettingsRepository

/**
 * S5 — the Nextcloud connection dialog (`docs/UI.md` §8).
 *
 * **Login Flow v2 exclusively.** The app never sees, asks for, or stores a user password; what it
 * persists is the app password the flow hands back (CLAUDE.md §5, `docs/decisions/0010`). There is
 * no username/password form in this module and there must never be one.
 *
 * The order below is load-bearing: **success is claimed only after the authenticated
 * `GET /subscriptions` returns 200.** A completed login flow proves the server is a Nextcloud and
 * the password works; it says nothing about gpoddersync being installed. On any failure the app
 * password is discarded rather than stored.
 */
class ConnectViewModel(
    private val loginFlowClient: NextcloudLoginFlowClient,
    private val settingsRepository: SettingsRepository,
    private val syncTrigger: ConnectSyncTrigger,
) : ViewModel() {
    private val _state = MutableStateFlow(ConnectUiState())
    val state: StateFlow<ConnectUiState> = _state.asStateFlow()

    private val effects = Channel<ConnectEffect>(Channel.BUFFERED)
    val effect: Flow<ConnectEffect> = effects.receiveAsFlow()

    private var flowJob: Job? = null

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
                    _state.value = _state.value.copy(host = event.value, inlineError = null)
                }
            ConnectEvent.Submit -> submit()
            ConnectEvent.Cancel -> cancel()
        }
    }

    private fun submit() {
        val host = _state.value.host.trim()
        val invalid = hostProblem(host)
        if (invalid != null) {
            _state.value = _state.value.copy(inlineError = invalid)
            return
        }
        flowJob?.cancel()
        flowJob = viewModelScope.launch { connect(normaliseHost(host)) }
    }

    /**
     * Aborts the poll. Cancelling the coroutine is what stops it — the client's contract is that a
     * cancelled poll simply stops asking, so there is nothing else to unwind.
     */
    private fun cancel() {
        flowJob?.cancel()
        flowJob = null
        if (_state.value.phase == ConnectUiState.Phase.Editing) {
            emit(ConnectEffect.Dismiss)
        } else {
            _state.value = _state.value.copy(phase = ConnectUiState.Phase.Editing)
        }
    }

    // `@Suppress("ReturnCount")`: the three returns are the three ways this sequence must stop
    // *without storing anything*. Flattening them into nested `if`s would bury the one rule that
    // matters — that nothing is persisted until the last step succeeds.
    @Suppress("ReturnCount")
    private suspend fun connect(baseUrl: String) {
        _state.value = _state.value.copy(phase = ConnectUiState.Phase.RequestingFlow, inlineError = null)

        val flow =
            loginFlowClient
                .start(
                    baseUrl,
                ).getOrElse { return fail(it.asConnectError(ConnectError.NOT_NEXTCLOUD)) }
        emit(ConnectEffect.OpenBrowser(flow.loginUrl))
        _state.value = _state.value.copy(phase = ConnectUiState.Phase.AwaitingAuthorization)

        // Cancellation propagates on its own: the client's contract is that a cancelled poll simply
        // stops asking, so Cancel needs no unwinding here.
        val result = loginFlowClient.poll(flow).getOrElse { return fail(it.asConnectError(ConnectError.ABANDONED)) }

        _state.value = _state.value.copy(phase = ConnectUiState.Phase.VerifyingGpodderSync)
        loginFlowClient.verifyGpodderSync(result.credentials).getOrElse {
            // The password is *not* stored: connecting to a Nextcloud without gpoddersync would
            // leave the user with an app that silently syncs nothing (docs/UI.md §8).
            return fail(it.asConnectError(ConnectError.NO_GPODDERSYNC))
        }

        // Only now, and the server's own canonical URL rather than the typed one — a Nextcloud
        // behind a reverse proxy legitimately returns a different host.
        settingsRepository.setNextcloudCredentials(result.credentials)
        syncTrigger.requestSyncNow()
        emit(ConnectEffect.Connected)
    }

    private fun fail(error: ConnectError) {
        _state.value = _state.value.copy(phase = ConnectUiState.Phase.Editing, inlineError = error)
    }

    private fun emit(effect: ConnectEffect) {
        effects.trySend(effect)
    }
}

/**
 * Validation happens on submit only, so typing never fights the user (`docs/UI.md` §8).
 *
 * `null` means "good enough to try" — the server is the real authority on whether it exists, and
 * guessing harder here would only reject valid setups.
 */
internal fun hostProblem(host: String): ConnectError? =
    when {
        host.isBlank() -> ConnectError.UNREACHABLE
        host.any { it.isWhitespace() } -> ConnectError.UNREACHABLE
        else -> null
    }

/**
 * A typed scheme is **stripped, not rejected** (`docs/UI.md` §8), and https is then assumed: the
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
 * Enqueues the first sync once credentials land, so S1 fills in without the user doing anything
 * else. A port because `:feature:settings` must not see WorkManager (`docs/UI_interface.md` §0.2).
 */
fun interface ConnectSyncTrigger {
    fun requestSyncNow()
}

/**
 * Maps the client's typed failure onto the message S5 shows.
 *
 * [fallback] is what an *untyped* failure degrades to — the step's most likely cause. This mapping
 * exists because collapsing every failure into one message is exactly the bug `docs/UI.md` §8's
 * table was written to prevent: a mistyped host reported as "this doesn't look like a Nextcloud
 * server" sends the user to check their server instead of their spelling. Found by running the
 * manual probe against an address that does not resolve.
 */
internal fun Throwable.asConnectError(fallback: ConnectError): ConnectError =
    when ((this as? LoginFlowException)?.failure) {
        LoginFlowFailure.UNREACHABLE -> ConnectError.UNREACHABLE
        LoginFlowFailure.TLS -> ConnectError.TLS
        LoginFlowFailure.NOT_NEXTCLOUD -> ConnectError.NOT_NEXTCLOUD
        LoginFlowFailure.NO_GPODDERSYNC -> ConnectError.NO_GPODDERSYNC
        LoginFlowFailure.UNAUTHORIZED -> ConnectError.UNAUTHORIZED
        LoginFlowFailure.ABANDONED -> ConnectError.ABANDONED
        null -> fallback
    }
