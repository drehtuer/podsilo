// SPDX-License-Identifier: GPL-3.0-or-later

package net.drehtuer.podsilo.feature.settings

/**
 * S5 — the Nextcloud connection dialog (`UI.adoc` §B5).
 *
 * @property host what the user typed, **without a scheme**: the field renders a fixed `https://`
 *   prefix, and a pasted scheme is stripped rather than rejected.
 * @property isChangingExisting re-titles the dialog and shows the caution line that the download
 *   history is kept — true, because the ledger has no foreign key to feeds (architecture §4).
 * @property showSwitchAccountHint set after *Use a different account*: explains that the account is
 *   decided by the browser's Nextcloud session, which is the one thing the app cannot choose for you.
 */
data class ConnectUiState(
    val host: String = "",
    val phase: Phase = Phase.Editing,
    val inlineError: ConnectError? = null,
    val isChangingExisting: Boolean = false,
    val showSwitchAccountHint: Boolean = false,
) {
    sealed interface Phase {
        data object Editing : Phase

        data object RequestingFlow : Phase

        /** The field is read-only and Cancel aborts the poll (`UI.adoc` §8). */
        data object AwaitingAuthorization : Phase

        /** The authenticated `GET /subscriptions`. Success is not claimed before this returns 200. */
        data object VerifyingGpodderSync : Phase

        /**
         * Authorization succeeded and the server named an account — **nothing is stored yet**.
         *
         * Login Flow v2 has no account chooser: if the browser already holds a Nextcloud session,
         * the grant page reads *"Currently logged in as X"* and offers a single *Grant access*
         * button. So the account is whichever one the browser happened to be signed into, and the
         * app used to persist it without ever showing the name. Connecting the wrong account is not
         * a cosmetic mistake — every triage decision from then on writes `DOWNLOAD` and `PLAY`
         * actions into *that* account's log, and those are not retractable.
         *
         * @property loginName the server's own `loginName`, never anything the app guessed.
         */
        data class ConfirmingAccount(
            val loginName: String,
        ) : Phase
    }

    /** While anything is in flight the dialog cannot be dismissed by tapping outside (§8). */
    val isBusy: Boolean get() = phase != Phase.Editing

    /** Non-null exactly when the user still has to accept or reject the account that came back. */
    val confirming: Phase.ConfirmingAccount? get() = phase as? Phase.ConfirmingAccount
}

/** Each maps to one plain-language sentence — never a stack trace (`UI.adoc` §8). */
enum class ConnectError {
    /**
     * The address contains a space — its own case because it is the one typo a phone keyboard makes
     * for you, and because *"can't reach that address"* sends the reader to look at their network
     * for a fault that is three characters into the field (issue found on the device, 2026-08-23).
     */
    ADDRESS_HAS_SPACE,

    /** The address is not one at all — empty, or nothing a host can be parsed out of. */
    ADDRESS_INVALID,
    UNREACHABLE,
    TIMED_OUT,
    CLEARTEXT_BLOCKED,
    TLS,
    NOT_NEXTCLOUD,
    NO_GPODDERSYNC,
    UNAUTHORIZED,
    ABANDONED,
}

sealed interface ConnectEvent {
    data class HostChanged(
        val value: String,
    ) : ConnectEvent

    data object Submit : ConnectEvent

    data object Cancel : ConnectEvent

    /** Accepts the named account. **The only path that stores credentials.** */
    data object ConfirmAccount : ConnectEvent

    /**
     * Rejects it. Discards the app password unstored and opens the server so the user can log out
     * of the browser session — the only way to be offered a different account next time.
     */
    data object RejectAccount : ConnectEvent

    /**
     * The connection UI became visible, or stopped being visible.
     *
     * **The poll runs only while this is `true`** (`decisions/0020`). Emitted by the host from
     * the lifecycle, not guessed by the view model: a view model has no business observing a
     * lifecycle, and the dialog is the thing that knows whether it is on screen.
     */
    data class ForegroundChanged(
        val inForeground: Boolean,
    ) : ConnectEvent
}

sealed interface ConnectEffect {
    /** A Custom Tab, opened by the host — `architecture.adoc` §2 keeps `:core:gpodder` Android-free. */
    data class OpenBrowser(
        val url: String,
    ) : ConnectEffect

    data object Connected : ConnectEffect

    data object Dismiss : ConnectEffect
}
