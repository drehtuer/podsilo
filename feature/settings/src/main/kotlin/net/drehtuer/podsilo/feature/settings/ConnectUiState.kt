// SPDX-License-Identifier: GPL-3.0-or-later

package net.drehtuer.podsilo.feature.settings

/**
 * S5 — the Nextcloud connection dialog (`docs/UI_interface.md` §5).
 *
 * @property host what the user typed, **without a scheme**: the field renders a fixed `https://`
 *   prefix, and a pasted scheme is stripped rather than rejected.
 * @property isChangingExisting re-titles the dialog and shows the caution line that the download
 *   history is kept — true, because the ledger has no foreign key to feeds (architecture §4).
 */
data class ConnectUiState(
    val host: String = "",
    val phase: Phase = Phase.Editing,
    val inlineError: ConnectError? = null,
    val isChangingExisting: Boolean = false,
) {
    sealed interface Phase {
        data object Editing : Phase

        data object RequestingFlow : Phase

        /** The field is read-only and Cancel aborts the poll (`docs/UI.md` §8). */
        data object AwaitingAuthorization : Phase

        /** The authenticated `GET /subscriptions`. Success is not claimed before this returns 200. */
        data object VerifyingGpodderSync : Phase
    }

    /** While anything is in flight the dialog cannot be dismissed by tapping outside (§8). */
    val isBusy: Boolean get() = phase != Phase.Editing
}

/** Each maps to one plain-language sentence — never a stack trace (`docs/UI.md` §8). */
enum class ConnectError { UNREACHABLE, TLS, NOT_NEXTCLOUD, NO_GPODDERSYNC, UNAUTHORIZED, ABANDONED }

sealed interface ConnectEvent {
    data class HostChanged(
        val value: String,
    ) : ConnectEvent

    data object Submit : ConnectEvent

    data object Cancel : ConnectEvent
}

sealed interface ConnectEffect {
    /** A Custom Tab, opened by the host — `docs/decisions/0007` keeps `:core:gpodder` Android-free. */
    data class OpenBrowser(
        val url: String,
    ) : ConnectEffect

    data object Connected : ConnectEffect

    data object Dismiss : ConnectEffect
}
