// SPDX-License-Identifier: GPL-3.0-or-later

package net.drehtuer.podsilo.ui.errorlog

import net.drehtuer.podsilo.core.model.port.LogCategory
import net.drehtuer.podsilo.core.model.port.LogEntry

/**
 * S8 — the error log (`docs/UI.adoc` §B6b).
 *
 * A chronological, read-only failure log, so a single-user self-hosted setup can be debugged without
 * a laptop, `adb`, or a bug report. Successes are never here — S7's *recently downloaded* covers
 * those.
 *
 * @property entries already collapsed by the DAO, newest first. Repeated identical failures are one
 *   entry with an occurrence count, or one feed timing out hourly would evict every genuinely
 *   one-off error within a day.
 * @property canClear `false` when empty — Copy/Share/Clear go **disabled, not hidden**, so the
 *   affordance stays where the user learned it (`docs/UI.adoc` §11).
 */
data class ErrorLogUiState(
    val filter: LogCategory? = null,
    val entries: List<LogEntry> = emptyList(),
    val expanded: Set<Long> = emptySet(),
    val pendingClear: Boolean = false,
) {
    val canClear: Boolean get() = entries.isNotEmpty()
}

sealed interface ErrorLogEvent {
    /** `null` is *All*. */
    data class FilterChanged(
        val category: LogCategory?,
    ) : ErrorLogEvent

    data class DetailToggled(
        val id: Long,
    ) : ErrorLogEvent

    /** Jumps to the episode in S2, when the entry names one. */
    data class EntryClicked(
        val id: Long,
    ) : ErrorLogEvent

    data object CopyAllClicked : ErrorLogEvent

    data object ShareClicked : ErrorLogEvent

    data object ClearRequested : ErrorLogEvent

    data object ClearConfirmed : ErrorLogEvent

    data object ClearCancelled : ErrorLogEvent
}

sealed interface ErrorLogEffect {
    /** Both produce plain text; the host owns the clipboard and the share sheet. */
    data class CopyToClipboard(
        val text: String,
    ) : ErrorLogEffect

    data class Share(
        val text: String,
    ) : ErrorLogEffect

    data class OpenEpisode(
        val feedUrl: String,
        val episodeKey: String,
    ) : ErrorLogEffect

    data class ShowMessage(
        val text: String,
    ) : ErrorLogEffect
}
