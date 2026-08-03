// SPDX-License-Identifier: GPL-3.0-or-later

package net.drehtuer.podsilo.feature.episodes

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import net.drehtuer.podsilo.core.model.Feed
import net.drehtuer.podsilo.core.model.port.BulkScope
import net.drehtuer.podsilo.core.model.port.BulkScopeKind
import net.drehtuer.podsilo.core.model.port.ConnectivityMonitor
import net.drehtuer.podsilo.core.model.port.EpisodeListItem
import net.drehtuer.podsilo.core.model.port.EpisodeListRepository
import net.drehtuer.podsilo.core.model.port.EpisodeRepository
import net.drehtuer.podsilo.core.model.port.FeedRepository
import net.drehtuer.podsilo.core.model.port.LedgerFilter
import net.drehtuer.podsilo.core.model.port.SettingsRepository
import net.drehtuer.podsilo.core.model.port.SwipeDirection
import net.drehtuer.podsilo.core.model.port.SwipeMapping
import java.time.ZoneId

/** `WhileSubscribed` grace period: survives a rotation without restarting the query. */
private const val SUBSCRIPTION_TIMEOUT_MS = 5_000L

/**
 * S2. Observes the ledger join, projects it into rows, and turns events into ledger writes plus
 * scheduling requests — never into network calls (`docs/UI_interface.md` §0.1/§0.3).
 *
 * Not a `@HiltViewModel`: the feed URL is a construction parameter, so `:app` builds it through a
 * factory. That also keeps `:feature:episodes` free of a Hilt dependency, so this whole class is
 * testable as a plain object with fakes.
 */
@Suppress("LongParameterList", "TooManyFunctions")
class EpisodeListViewModel(
    private val feedUrl: String,
    private val feedRepository: FeedRepository,
    private val episodeRepository: EpisodeRepository,
    private val listRepository: EpisodeListRepository,
    private val settingsRepository: SettingsRepository,
    private val connectivityMonitor: ConnectivityMonitor,
    private val triageWriter: TriageWriter,
    private val scheduler: EpisodeScheduler,
    private val spaceProbe: DownloadSpaceProbe,
    private val folderStatus: DownloadFolderStatus,
    private val zone: ZoneId = ZoneId.systemDefault(),
) : ViewModel() {
    private val filter = MutableStateFlow(EpisodeFilter.TO_DECIDE)
    private val selection = MutableStateFlow<Selection?>(null)
    private val refreshing = MutableStateFlow(false)
    private val pendingBulk = MutableStateFlow<BulkPreview?>(null)
    private val pendingMarkAll = MutableStateFlow<List<String>?>(null)

    private val effects = Channel<EpisodeListEffect>(Channel.BUFFERED)
    val effect: Flow<EpisodeListEffect> = effects.receiveAsFlow()

    /** The feed's own row, for the title and artwork. Read once per collection of [state]. */
    private val feedFlow: Flow<Feed?> = flow { emit(feedRepository.get(feedUrl)) }

    /**
     * Derived rather than pushed into a `MutableStateFlow` from an `init` block.
     *
     * `WhileSubscribed` means the query runs when a screen is looking and stops when it isn't —
     * a list of 500 episodes should not be re-projected on every ledger write while the user is in
     * settings. It also makes the whole class testable without an `init`-launched job to pump.
     */
    @Suppress("OPT_IN_USAGE")
    val state: StateFlow<EpisodeListUiState> =
        combine(
            filter,
            selection,
            // Nested because `combine` tops out at five sources; these three are the screen's
            // chrome — the indicator, the dialog and the paused banner — rather than its content.
            combine(refreshing, pendingBulk, folderStatus.observe(), pendingMarkAll, ::Chrome),
            settingsRepository.observeSwipeMapping(),
            connectivityMonitor.observe(),
        ) { current, currentSelection, chrome, mapping, connectivity ->
            Snapshot(
                filter = current,
                selection = currentSelection,
                refreshing = chrome.refreshing,
                pendingBulk = chrome.pendingBulk,
                folder = chrome.folder,
                pendingMarkAll = chrome.pendingMarkAll,
                mapping = mapping,
                online = connectivity.online,
            )
        }.flatMapLatest { snapshot ->
            // flatMapLatest, not combine: a filter change must *replace* the query, so rows from the
            // previous filter can never be rendered under the new chip.
            combine(
                listRepository.observeEpisodes(
                    LedgerFilter(state = snapshot.filter.ledgerState, feedUrl = feedUrl),
                ),
                feedFlow,
            ) { items, feed ->
                snapshot.toUiState(items, feed?.title ?: feedUrl, feed?.imageUrl)
            }
        }.stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(SUBSCRIPTION_TIMEOUT_MS),
            initialValue = EpisodeListUiState(feedUrl = feedUrl, feedTitle = feedUrl),
        )

    private fun Snapshot.toUiState(
        items: List<EpisodeListItem>,
        feedTitle: String,
        feedArtwork: String?,
    ): EpisodeListUiState {
        val rows = items.map { it.toUi(feedTitle = feedTitle, feedArtworkUrl = feedArtwork) }
        return EpisodeListUiState(
            feedUrl = feedUrl,
            feedTitle = feedTitle,
            filter = filter,
            content =
                if (rows.isEmpty()) {
                    EpisodeListUiState.Content.Empty(filter)
                } else {
                    EpisodeListUiState.Content.Episodes(rows)
                },
            sections = monthSectionsFor(rows, zone),
            queueStatus = queueStatusFor(folder, rows),
            selection = selection,
            isRefreshing = refreshing,
            pendingBulk = pendingBulk,
            pendingMarkAll = pendingMarkAll,
            isOffline = !online,
            swipeMapping = mapping,
            // The overflow reads "Download all (n)"; n is the *undecided* count, so the item is
            // meaningless — and hidden — on any filter but "to decide".
            downloadAllCount = if (filter == EpisodeFilter.TO_DECIDE) rows.size else 0,
        )
    }

    // `@Suppress("CyclomaticComplexMethod")`: an exhaustive `when` over a sealed event hierarchy.
    // Its complexity is the number of events, and splitting it would hide the one place that lists
    // every thing this screen can do.
    @Suppress("CyclomaticComplexMethod")
    fun onEvent(event: EpisodeListEvent) {
        when (event) {
            is EpisodeListEvent.RowClicked -> emit(EpisodeListEffect.OpenDetail(event.episodeKey))
            is EpisodeListEvent.FilterChanged -> {
                filter.value = event.filter
                // A filter change invalidates a selection made under the old one: acting on rows the
                // user can no longer see is exactly the accidental bulk action §14.2 warns about.
                selection.value = null
            }
            is EpisodeListEvent.SelectionStarted ->
                selection.value = Selection(setOf(event.episodeKey), currentRowCount())
            is EpisodeListEvent.SelectionToggled -> toggleSelection(event.episodeKey)
            EpisodeListEvent.SelectionCleared -> selection.value = null
            EpisodeListEvent.SelectAllInFilter -> selectAll()
            is EpisodeListEvent.Triage -> viewModelScope.launch { triage(listOf(event.episodeKey), event.action) }
            is EpisodeListEvent.SwipeCommitted -> onSwipe(event.episodeKey, event.direction)
            is EpisodeListEvent.BulkConfirmed ->
                viewModelScope.launch {
                    triage(event.keys.toList(), event.action)
                    selection.value = null
                }
            EpisodeListEvent.DownloadAllRequested -> viewModelScope.launch { emitDownloadAllPreview() }
            is EpisodeListEvent.DownloadAllConfirmed ->
                viewModelScope.launch {
                    pendingBulk.value = null
                    triage(event.keys, EpisodeUiAction.DOWNLOAD)
                }
            EpisodeListEvent.DownloadAllDismissed -> pendingBulk.value = null
            // Confirmed before writing, like every other bulk mark-as-played: these become `PLAY`
            // actions on a shared log and no undo reaches them (docs/decisions/0013).
            EpisodeListEvent.MarkAllRequested ->
                pendingMarkAll.value =
                    (state.value.content as? EpisodeListUiState.Content.Episodes)
                        ?.items
                        ?.map { it.episodeKey }
                        ?.takeIf { it.isNotEmpty() }
            EpisodeListEvent.MarkAllConfirmed ->
                viewModelScope.launch {
                    val keys = pendingMarkAll.value.orEmpty()
                    pendingMarkAll.value = null
                    if (keys.isNotEmpty()) triage(keys, EpisodeUiAction.MARK_AS_PLAYED)
                }
            EpisodeListEvent.MarkAllDismissed -> pendingMarkAll.value = null
            EpisodeListEvent.PullToRefresh -> refresh()
            // The fix lives outside this screen (the SAF picker, or the user freeing space), so the
            // host handles it; S2 only reports that the queue is held.
            EpisodeListEvent.PausedBannerActionClicked -> emit(EpisodeListEffect.ResolvePausedQueue)
        }
    }

    private fun onSwipe(
        episodeKey: String,
        direction: SwipeDirection,
    ) {
        viewModelScope.launch {
            // Read from the stored mapping rather than from `state.value`: the swipe background
            // renders from the same setting, so the gesture and its label cannot disagree
            // (docs/UI.md §12.1) — and this stays correct even before anything collects `state`.
            val action = settingsRepository.observeSwipeMapping().first().triageFor(direction)
            if (action != null) triage(listOf(episodeKey), action)
        }
    }

    private suspend fun triage(
        episodeKeys: List<String>,
        action: EpisodeUiAction,
    ) {
        val episodes = episodeKeys.mapNotNull { episodeRepository.get(it) }
        if (episodes.isEmpty()) return

        when (action) {
            EpisodeUiAction.MARK_AS_PLAYED -> {
                triageWriter.markAsPlayed(episodes)
                emit(EpisodeListEffect.ShowMessage(SnackbarText.BulkApplied(episodes.size)))
            }
            EpisodeUiAction.DOWNLOAD, EpisodeUiAction.DOWNLOAD_AGAIN, EpisodeUiAction.RETRY -> {
                triageWriter.queue(episodes)
                // userRequested only for a re-decision: it is the sole way past DownloadWorker's
                // terminal-row refusal, and setting it unconditionally would erase that guarantee
                // (docs/decisions/0012).
                val userRequested = action == EpisodeUiAction.DOWNLOAD_AGAIN
                episodes.forEach { scheduler.enqueueDownload(it.episodeKey, userRequested) }
                emit(EpisodeListEffect.ShowMessage(SnackbarText.Queued(episodes.size)))
            }
            EpisodeUiAction.CANCEL -> episodes.forEach { scheduler.cancelDownload(it.episodeKey) }
            EpisodeUiAction.OPEN_IN_BROWSER, EpisodeUiAction.COPY_LINK ->
                episodes.firstOrNull()?.link?.let { emit(EpisodeListEffect.OpenUrl(it)) }
        }
    }

    /**
     * Produces the confirmation dialog's preview and **writes nothing** — `docs/decisions/0014`'s
     * whole safeguard is that the count is named before anything happens. Only
     * [EpisodeListEvent.DownloadAllConfirmed] writes.
     */
    private suspend fun emitDownloadAllPreview() {
        val undecided =
            listRepository.undecided(BulkScope(kind = BulkScopeKind.ALL_UNDECIDED, feedUrl = feedUrl))
        pendingBulk.value = if (undecided.isEmpty()) null else buildBulkPreview(undecided, spaceProbe.freeBytes())
    }

    private fun refresh() {
        viewModelScope.launch {
            // Checked before anything is started: an offline pull returns immediately rather than
            // hanging on a timeout the user could have been told about instantly (docs/UI.md §12.10).
            if (!connectivityMonitor.observe().first().online) {
                emit(EpisodeListEffect.ShowMessage(SnackbarText.Offline))
                return@launch
            }
            // Held until the refreshed rows arrive rather than cleared on the next line: enqueueing is
            // synchronous, so clearing it here would make `isRefreshing` never observably true and the
            // pull-to-refresh indicator would never appear.
            refreshing.value = true
            try {
                // Suspends until the work reaches a terminal state, so the indicator is visible for the
                // whole chain rather than for the microsecond enqueueing takes (docs/UI.md §4).
                scheduler.requestFeedRefresh(feedUrl)
            } finally {
                refreshing.value = false
            }
        }
    }

    private fun toggleSelection(episodeKey: String) {
        val current = selection.value ?: Selection(emptySet(), currentRowCount())
        val keys = if (episodeKey in current.keys) current.keys - episodeKey else current.keys + episodeKey
        // An empty selection leaves selection mode: an app bar reading "0 selected" is a dead end.
        selection.value = if (keys.isEmpty()) null else current.copy(keys = keys)
    }

    private fun selectAll() {
        val rows = (state.value.content as? EpisodeListUiState.Content.Episodes)?.items.orEmpty()
        if (rows.isEmpty()) return
        selection.value = Selection(rows.map { it.episodeKey }.toSet(), rows.size)
    }

    private fun currentRowCount(): Int = (state.value.content as? EpisodeListUiState.Content.Episodes)?.items?.size ?: 0

    private fun emit(effect: EpisodeListEffect) {
        effects.trySend(effect)
    }

    private data class Snapshot(
        val filter: EpisodeFilter,
        val selection: Selection?,
        val refreshing: Boolean,
        val pendingBulk: BulkPreview?,
        val folder: FolderState,
        val mapping: SwipeMapping,
        val online: Boolean,
        val pendingMarkAll: List<String>?,
    )

    /** `combine` tops out at five sources; these four are all "transient chrome". */
    private data class Chrome(
        val refreshing: Boolean,
        val pendingBulk: BulkPreview?,
        val folder: FolderState,
        val pendingMarkAll: List<String>?,
    )
}

/**
 * The scheduling surface a screen is allowed to touch.
 *
 * A view model never sees `WorkManager` (`docs/UI_interface.md` §0.2); `:app`'s `WorkScheduler`
 * implements this, which also keeps `:feature:episodes` free of a WorkManager dependency and
 * therefore testable without Robolectric.
 */
interface EpisodeScheduler {
    fun enqueueDownload(
        episodeKey: String,
        userRequested: Boolean,
    )

    fun cancelDownload(episodeKey: String)

    /** Suspends until the refresh reaches a terminal state, so the UI can show it for its duration. */
    suspend fun requestFeedRefresh(feedUrl: String?)
}
