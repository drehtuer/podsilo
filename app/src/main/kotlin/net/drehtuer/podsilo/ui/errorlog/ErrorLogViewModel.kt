// SPDX-License-Identifier: GPL-3.0-or-later

package net.drehtuer.podsilo.ui.errorlog

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import net.drehtuer.podsilo.core.model.port.LogCategory
import net.drehtuer.podsilo.core.model.port.LogRepository

private const val SUBSCRIPTION_TIMEOUT_MS = 5_000L

/**
 * S8 (`docs/UI_interface.md` §6b).
 *
 * Nothing here leaves the device unless the user taps Copy or Share — no telemetry, per README.
 */
class ErrorLogViewModel(
    private val logRepository: LogRepository,
) : ViewModel() {
    private val filter = MutableStateFlow<LogCategory?>(null)
    private val expanded = MutableStateFlow<Set<Long>>(emptySet())
    private val pendingClear = MutableStateFlow(false)

    private val effects = Channel<ErrorLogEffect>(Channel.BUFFERED)
    val effect: Flow<ErrorLogEffect> = effects.receiveAsFlow()

    @Suppress("OPT_IN_USAGE")
    val state: StateFlow<ErrorLogUiState> =
        combine(filter, expanded, pendingClear, ::Triple)
            .flatMapLatest { (category, open, clearing) ->
                // flatMapLatest, not combine: a filter change must *replace* the query, so entries
                // from the previous category can never render under the new chip.
                logRepository.observe(category).map { entries ->
                    ErrorLogUiState(
                        filter = category,
                        entries = entries,
                        expanded = open,
                        pendingClear = clearing,
                    )
                }
            }.stateIn(
                scope = viewModelScope,
                started = SharingStarted.WhileSubscribed(SUBSCRIPTION_TIMEOUT_MS),
                initialValue = ErrorLogUiState(),
            )

    fun onEvent(event: ErrorLogEvent) {
        when (event) {
            is ErrorLogEvent.FilterChanged -> filter.value = event.category
            is ErrorLogEvent.DetailToggled -> toggle(event.id)
            is ErrorLogEvent.EntryClicked -> openEpisode(event.id)
            ErrorLogEvent.CopyAllClicked -> viewModelScope.launch { export(::copy) }
            ErrorLogEvent.ShareClicked -> viewModelScope.launch { export(::share) }
            ErrorLogEvent.ClearRequested -> pendingClear.value = true
            ErrorLogEvent.ClearCancelled -> pendingClear.value = false
            ErrorLogEvent.ClearConfirmed -> viewModelScope.launch { clearLog() }
        }
    }

    private fun toggle(id: Long) {
        expanded.value = if (id in expanded.value) expanded.value - id else expanded.value + id
    }

    /** Only entries that name an episode are navigable; the rest are not a dead tap, just inert. */
    private fun openEpisode(id: Long) {
        val entry = state.value.entries.firstOrNull { it.id == id } ?: return
        val episodeKey = entry.episodeKey ?: return
        val feedUrl = entry.feedUrl ?: return
        effects.trySend(ErrorLogEffect.OpenEpisode(feedUrl, episodeKey))
    }

    /**
     * Exports the **whole** log, not the current filter: the text is for pasting into an issue, and
     * a filtered export would silently omit the entry that explains the one being reported.
     */
    private suspend fun export(deliver: (String) -> Unit) {
        val text = logRepository.exportPlainText()
        if (text.isBlank()) return
        deliver(text)
    }

    private fun copy(text: String) {
        effects.trySend(ErrorLogEffect.CopyToClipboard(text))
    }

    private fun share(text: String) {
        effects.trySend(ErrorLogEffect.Share(text))
    }

    /**
     * Clears **the whole ring buffer, not the current filter** — a filtered clear would leave a count
     * the user cannot account for. Touches nothing else: no ledger row, no worker, no sync state.
     * Recording resumes immediately.
     *
     * Named `clearLog` and not `clear`: `ViewModel.clear()` exists and is not suspending, so the
     * obvious name would silently be an override attempt.
     */
    private suspend fun clearLog() {
        val count = state.value.entries.size
        pendingClear.value = false
        logRepository.clear()
        effects.trySend(ErrorLogEffect.ShowMessage(clearedMessage(count)))
    }
}

internal fun clearedMessage(count: Int): String =
    if (count ==
        1
    ) {
        "Cleared 1 log entry."
    } else {
        "Cleared $count log entries."
    }
