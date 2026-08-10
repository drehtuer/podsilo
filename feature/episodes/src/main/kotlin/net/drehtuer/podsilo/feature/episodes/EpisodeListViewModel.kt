// SPDX-License-Identifier: GPL-3.0-or-later

package net.drehtuer.podsilo.feature.episodes

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flatMapLatest
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
import net.drehtuer.podsilo.core.model.port.LogCategory
import net.drehtuer.podsilo.core.model.port.LogEntry
import net.drehtuer.podsilo.core.model.port.LogRepository
import net.drehtuer.podsilo.core.model.port.SettingsRepository
import net.drehtuer.podsilo.core.model.port.SwipeDirection
import net.drehtuer.podsilo.core.model.port.SwipeMapping
import java.time.ZoneId

/** `WhileSubscribed` grace period: survives a rotation without restarting the query. */
private const val SUBSCRIPTION_TIMEOUT_MS = 5_000L

/**
 * How long a swipe decision is held before it is written (`docs/decisions/0021`).
 *
 * Matched to a Material `SnackbarDuration.Short`, so the window closes at roughly the moment the
 * undo affordance leaves the screen. The view model is the authority on when it actually closes.
 */
private const val UNDO_WINDOW_MS = 5_000L

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
    private val workMonitor: DownloadWorkMonitor,
    private val logRepository: LogRepository,
    private val zone: ZoneId = ZoneId.systemDefault(),
    /**
     * Where a decision still inside its undo window is written when the screen goes away.
     *
     * A scope that **outlives this view model**, because `viewModelScope` is already cancelled by
     * the time `onCleared` runs — a write launched there would never happen. Injected so tests can
     * pass the test scope rather than waiting on a real dispatcher (`docs/decisions/0021`).
     */
    private val commitScope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Default),
) : ViewModel() {
    private val filter = MutableStateFlow(EpisodeFilter.TO_DECIDE)
    private val selection = MutableStateFlow<Selection?>(null)
    private val refreshing = MutableStateFlow(false)
    private val pendingBulk = MutableStateFlow<BulkPreview?>(null)
    private val pendingMarkAll = MutableStateFlow<List<String>?>(null)
    private val pendingSelectionAction = MutableStateFlow<EpisodeUiAction?>(null)
    private val pendingUndo = MutableStateFlow<PendingUndo?>(null)
    private var undoJob: Job? = null

    private val effects = Channel<EpisodeListEffect>(Channel.BUFFERED)
    val effect: Flow<EpisodeListEffect> = effects.receiveAsFlow()

    /**
     * The feed's own row — title, artwork, and `lastRefreshedAt`.
     *
     * **Observed, not read once.** It used to be a one-shot read, which was fine for a title but
     * cannot support the feed-error banner: that has to disappear the moment a later refresh
     * succeeds, and "succeeded" *is* a change to this row. Subscription lists are tiny, so filtering
     * the full list costs nothing.
     */
    private val feedFlow: Flow<Feed?> =
        feedRepository.observeAll().map { feeds ->
            feeds.firstOrNull { it.url == feedUrl }
        }

    /**
     * The banner `docs/UI.md` §5 specifies for a failed fetch — **which nothing has ever been able
     * to show.** `feedError` existed as a state field with a KDoc, set by nobody and read by nobody,
     * so a feed that failed to load was completely silent on the screen that lists it.
     *
     * The text comes from the error log rather than from a second error channel, because
     * `FeedRefresher` already writes a plain-language sentence there for exactly these failures
     * ("Feed server did not respond.") and §5 wants that sentence verbatim. One writer, two readers —
     * S8 and this banner — rather than two writers that can disagree.
     */
    private val feedErrorFlow: Flow<LogEntry?> =
        logRepository.observe(LogCategory.FEED).map { entries -> entries.firstOrNull { it.feedUrl == feedUrl } }

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
            // Both describe "which rows are special right now", and combine tops out at five sources.
            combine(selection, pendingUndo, ::Pair),
            // Nested because `combine` tops out at five sources; these three are the screen's
            // chrome — the indicator, the dialog and the paused banner — rather than its content.
            combine(refreshing, pendingBulk, folderStatus.observe(), pendingMarkAll, pendingSelectionAction, ::Chrome),
            settingsRepository.observeSwipeMapping(),
            connectivityMonitor.observe(),
        ) { current, selectionAndUndo, chrome, mapping, connectivity ->
            Snapshot(
                filter = current,
                selection = selectionAndUndo.first,
                pendingUndo = selectionAndUndo.second,
                refreshing = chrome.refreshing,
                pendingBulk = chrome.pendingBulk,
                folder = chrome.folder,
                pendingMarkAll = chrome.pendingMarkAll,
                pendingSelectionAction = chrome.pendingSelectionAction,
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
                // Live byte progress, which no screen had until issue #47: the ledger says an
                // episode is DOWNLOADING, only this process knows how far along.
                workMonitor.observe(),
                feedErrorFlow,
            ) { items, feed, work, lastFeedError ->
                resumeStranded(work, items)
                snapshot.toUiState(
                    items = items,
                    feedTitle = feed?.title ?: feedUrl,
                    feedArtwork = feed?.imageUrl,
                    work = work,
                    feedError = lastFeedError.takeIf { it.isNewerThanLastSuccess(feed) }?.message,
                )
            }
        }.stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(SUBSCRIPTION_TIMEOUT_MS),
            initialValue = EpisodeListUiState(feedUrl = feedUrl, feedTitle = feedUrl),
        )

    /**
     * `docs/UI_interface.md` §7's third case: a `DOWNLOADING` row with no work behind it was killed
     * mid-download, so the work is re-enqueued **on first observation**.
     *
     * This does not weaken the no-auto-download invariant (CLAUDE.md §1). It resumes a download the
     * user already asked for — the ledger row is the proof they asked — and it is deliberately
     * limited to `DOWNLOADING`: a `QUEUED` row is not resumed here, and no row without a ledger entry
     * is ever touched. `userRequested` stays `false`, so this can never get past `DownloadWorker`'s
     * terminal-row refusal.
     *
     * [alreadyResumed] makes it once-per-key-per-ViewModel: without it, every re-emission of the
     * query while the worker is starting up would enqueue again.
     */
    private fun resumeStranded(
        work: DownloadWork,
        items: List<EpisodeListItem>,
    ) {
        work.strandedIn(items).forEach { key ->
            if (alreadyResumed.add(key)) scheduler.enqueueDownload(key, userRequested = false)
        }
    }

    private val alreadyResumed = mutableSetOf<String>()

    @Suppress("LongParameterList")
    private fun Snapshot.toUiState(
        items: List<EpisodeListItem>,
        feedTitle: String,
        feedArtwork: String?,
        work: DownloadWork,
        feedError: String?,
    ): EpisodeListUiState {
        val rows =
            items
                .map { it.toUi(feedTitle = feedTitle, feedArtworkUrl = feedArtwork, work = work) }
                // The held decision is shown as though it had been taken. Nothing is written yet
                // (docs/decisions/0021) — but a swipe that appeared to do nothing for five seconds
                // would read as the app ignoring it, and the user would swipe again.
                .map { row ->
                    pendingUndo?.takeIf { it.episodeKey == row.episodeKey }?.let { row.asPending(it) } ?: row
                }
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
            pendingSelectionAction = pendingSelectionAction,
            pendingUndo = pendingUndo,
            feedError = feedError,
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
            EpisodeListEvent.BackClicked -> emit(EpisodeListEffect.NavigateUp)
            EpisodeListEvent.ActivityClicked -> emit(EpisodeListEffect.OpenActivity)
            is EpisodeListEvent.FilterChanged -> {
                filter.value = event.filter
                // A filter change invalidates a selection made under the old one: acting on rows the
                // user can no longer see is exactly the accidental bulk action §14.2 warns about.
                selection.value = null
                // And a confirmation whose set just changed under it must not survive to be tapped.
                pendingSelectionAction.value = null
            }
            is EpisodeListEvent.SelectionStarted ->
                selection.value = Selection(setOf(event.episodeKey), currentRowCount())
            is EpisodeListEvent.SelectionToggled -> toggleSelection(event.episodeKey)
            EpisodeListEvent.SelectionCleared -> {
                selection.value = null
                pendingSelectionAction.value = null
            }
            EpisodeListEvent.SelectAllInFilter -> selectAll()
            // Opens the confirmation and writes nothing; only BulkConfirmed writes.
            is EpisodeListEvent.SelectionActionRequested -> pendingSelectionAction.value = event.action
            EpisodeListEvent.SelectionActionDismissed -> pendingSelectionAction.value = null
            is EpisodeListEvent.Triage -> viewModelScope.launch { triage(listOf(event.episodeKey), event.action) }
            is EpisodeListEvent.SwipeCommitted -> onSwipe(event.episodeKey, event.direction)
            is EpisodeListEvent.BulkConfirmed ->
                viewModelScope.launch {
                    pendingSelectionAction.value = null
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
            EpisodeListEvent.UndoRequested -> {
                // Nothing was written, so there is nothing to revert: the held decision is simply
                // dropped and the row returns to undecided (docs/decisions/0021).
                undoJob?.cancel()
                pendingUndo.value = null
            }
            // Both mean "fetch this feed again"; the banner is simply the affordance a user who is
            // looking at the failure will reach for first.
            EpisodeListEvent.PullToRefresh, EpisodeListEvent.RetryFeedClicked -> refresh()
            // The fix lives outside this screen (the SAF picker, or the user freeing space), so the
            // host handles it; S2 only reports that the queue is held.
            EpisodeListEvent.PausedBannerActionClicked -> emit(EpisodeListEffect.ResolvePausedQueue)
        }
    }

    /**
     * A swipe decision, **held for [UNDO_WINDOW_MS] before anything is written**
     * (`docs/decisions/0021`).
     *
     * The deferral is the whole design. A skip becomes a `PLAY` action in an append-only log that
     * other clients act on, and the GPodder API has no retraction — so the only reliably reversible
     * state is one where the row was never written. Nothing here touches the ledger, the outbox or
     * WorkManager until the window elapses; [EpisodeListEvent.UndoRequested] simply discards it.
     *
     * The cost, stated plainly because the ADR states it: a decision made and then immediately
     * killed (process death inside the window) is **lost**. Nothing wrong is written — the episode
     * is merely still undecided — which is the trade this design accepts.
     */
    private fun onSwipe(
        episodeKey: String,
        direction: SwipeDirection,
    ) {
        viewModelScope.launch {
            // Read from the stored mapping rather than from `state.value`: the swipe background
            // renders from the same setting, so the gesture and its label cannot disagree
            // (docs/UI.md §12.1) — and this stays correct even before anything collects `state`.
            val action = settingsRepository.observeSwipeMapping().first().triageFor(direction) ?: return@launch

            // One pending decision at a time: a second swipe commits the first rather than
            // discarding it. Two live windows would need two snackbars and an answer to "which one
            // does Undo mean".
            commitPendingUndo()

            pendingUndo.value = PendingUndo(episodeKey, action)
            emit(EpisodeListEffect.ShowUndo(action))
            undoJob =
                viewModelScope.launch {
                    delay(UNDO_WINDOW_MS)
                    commitPendingUndo()
                }
        }
    }

    /**
     * Writes the held decision, if there still is one.
     *
     * Idempotent by design — the timer, a following swipe and leaving the screen all call it, and an
     * undo that arrives after the write finds nothing to discard rather than racing it.
     */
    private suspend fun commitPendingUndo() {
        val pending = pendingUndo.value ?: return
        pendingUndo.value = null
        undoJob?.cancel()
        // notify = false: the undo snackbar already reported this decision. A second one announcing
        // it again five seconds later, with no action on it, would be noise.
        triage(listOf(pending.episodeKey), pending.action, notify = false)
    }

    /**
     * Leaving the screen **commits** rather than discarding.
     *
     * Silently dropping a decision the user made and watched take effect is worse than committing
     * one they might have wanted back: they can still act again, and the row shows what happened.
     */
    override fun onCleared() {
        // `viewModelScope` is cancelled during onCleared, so the write cannot run there. The pending
        // decision is handed to a scope that outlives this view model — this is the one place the
        // class reaches outside its own lifecycle, and it is why `commitScope` exists.
        val pending = pendingUndo.value ?: return super.onCleared()
        pendingUndo.value = null
        commitScope.launch { triage(listOf(pending.episodeKey), pending.action, notify = false) }
        super.onCleared()
    }

    private suspend fun triage(
        episodeKeys: List<String>,
        action: EpisodeUiAction,
        notify: Boolean = true,
    ) {
        val episodes = episodeKeys.mapNotNull { episodeRepository.get(it) }
        if (episodes.isEmpty()) return

        when (action) {
            EpisodeUiAction.MARK_AS_PLAYED -> {
                triageWriter.markAsPlayed(episodes)
                if (notify) emit(EpisodeListEffect.ShowMessage(SnackbarText.BulkApplied(episodes.size)))
            }
            EpisodeUiAction.DOWNLOAD, EpisodeUiAction.DOWNLOAD_AGAIN, EpisodeUiAction.RETRY -> {
                triageWriter.queue(episodes)
                // userRequested only for a re-decision: it is the sole way past DownloadWorker's
                // terminal-row refusal, and setting it unconditionally would erase that guarantee
                // (docs/decisions/0012).
                val userRequested = action == EpisodeUiAction.DOWNLOAD_AGAIN
                episodes.forEach { scheduler.enqueueDownload(it.episodeKey, userRequested) }
                if (notify) emit(EpisodeListEffect.ShowMessage(SnackbarText.Queued(episodes.size)))
            }
            EpisodeUiAction.CANCEL -> episodes.forEach { scheduler.cancelDownload(it.episodeKey) }
            EpisodeUiAction.OPEN_IN_BROWSER -> episodes.firstOrNull()?.link?.let { emit(EpisodeListEffect.OpenUrl(it)) }
            // Copying is not opening. Both used to emit OpenUrl, so *Copy episode link* launched a
            // browser and the "Link copied" snackbar was unreachable.
            EpisodeUiAction.COPY_LINK ->
                episodes.firstOrNull()?.link?.let {
                    emit(EpisodeListEffect.CopyLink(it))
                    emit(EpisodeListEffect.ShowMessage(SnackbarText.LinkCopied))
                }
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
        val pendingSelectionAction: EpisodeUiAction?,
        val pendingUndo: PendingUndo?,
    )

    /** `combine` tops out at five sources; these four are all "transient chrome". */
    private data class Chrome(
        val refreshing: Boolean,
        val pendingBulk: BulkPreview?,
        val folder: FolderState,
        val pendingMarkAll: List<String>?,
        val pendingSelectionAction: EpisodeUiAction?,
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

/**
 * Whether a logged feed failure is still the *current* state of this feed.
 *
 * The rule is "newer than the last successful refresh", not "exists": an error from three days ago
 * that a later refresh cleared must not sit on the screen for ever. A feed that has never been
 * refreshed has no success to compare against, so any error stands.
 *
 * This is why a 304 has to move `lastRefreshedAt` (see `FeedRefresher`): a feed that is reached
 * successfully and is merely unchanged would otherwise never clear its banner.
 */
internal fun LogEntry?.isNewerThanLastSuccess(feed: Feed?): Boolean {
    val error = this ?: return false
    return error.at > (feed?.lastRefreshedAt ?: 0L)
}
