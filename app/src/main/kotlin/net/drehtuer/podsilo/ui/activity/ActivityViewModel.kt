// SPDX-License-Identifier: GPL-3.0-or-later

package net.drehtuer.podsilo.ui.activity

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import net.drehtuer.podsilo.core.model.EpisodeLedgerRow
import net.drehtuer.podsilo.core.model.Feed
import net.drehtuer.podsilo.core.model.LedgerState
import net.drehtuer.podsilo.core.model.port.ConnectivityMonitor
import net.drehtuer.podsilo.core.model.port.EpisodeListItem
import net.drehtuer.podsilo.core.model.port.EpisodeListRepository
import net.drehtuer.podsilo.core.model.port.EpisodeRepository
import net.drehtuer.podsilo.core.model.port.FeedRepository
import net.drehtuer.podsilo.core.model.port.SettingsRepository
import net.drehtuer.podsilo.feature.episodes.DownloadFolderStatus
import net.drehtuer.podsilo.feature.episodes.DownloadWork
import net.drehtuer.podsilo.feature.episodes.DownloadWorkMonitor
import net.drehtuer.podsilo.feature.episodes.EpisodeScheduler
import net.drehtuer.podsilo.feature.episodes.FolderState
import net.drehtuer.podsilo.feature.episodes.TriageWriter
import net.drehtuer.podsilo.feature.episodes.queueStatusFor
import net.drehtuer.podsilo.feature.episodes.toUi
import net.drehtuer.podsilo.feature.settings.SyncStatus

private const val SUBSCRIPTION_TIMEOUT_MS = 5_000L

/** The last ~20 delivered files answer "did it land?"; more than that is a file browser. */
private const val RECENT_LIMIT = 20

/**
 * S7 (`docs/UI_interface.md` §6).
 *
 * Reads the ledger rather than WorkManager for what is queued and what failed: the ledger is the
 * durable record and survives process death, whereas `WorkInfo` does not (architecture §9). Live
 * byte progress is the one thing that comes from the worker, and its absence renders as *resuming*
 * rather than 0 % (§7).
 */
@Suppress("LongParameterList") // A view model's parameter list is its port list.
class ActivityViewModel(
    private val episodeRepository: EpisodeRepository,
    private val listRepository: EpisodeListRepository,
    private val feedRepository: FeedRepository,
    private val settingsRepository: SettingsRepository,
    private val connectivityMonitor: ConnectivityMonitor,
    private val folderStatus: DownloadFolderStatus,
    private val workMonitor: DownloadWorkMonitor,
    private val syncStatus: SyncStatus,
    private val scheduler: EpisodeScheduler,
    private val triageWriter: TriageWriter,
    private val syncNow: ActivitySyncTrigger,
    // Injected rather than System.currentTimeMillis(), so the clear cursor is testable (CLAUDE.md §7).
    private val clock: java.time.Clock = java.time.Clock.systemUTC(),
) : ViewModel() {
    private val effects = Channel<ActivityEffect>(Channel.BUFFERED)
    val effect: Flow<ActivityEffect> = effects.receiveAsFlow()

    /**
     * **Bounded by the queue, not by the ledger** — the fix for issue #47.
     *
     * This used to observe *every* ledger row on the device and then look each row's episode up one
     * at a time, in Kotlin, before discarding all but the handful in flight. On a device with
     * thousands of decided episodes that is thousands of sequential queries per emission, re-run on
     * every ledger write anywhere in the app; the screen was seconds behind, and got worse the more
     * the app was used. Both narrowing and joining are now the database's job.
     *
     * `flatMapLatest` on the delivered cursor because `LIMIT` and the cursor are query parameters:
     * clearing the list has to *replace* the query rather than filter its results.
     */
    @Suppress("OPT_IN_USAGE")
    val state: StateFlow<ActivityUiState> =
        combine(
            listRepository.observeInFlight(),
            workMonitor.observe(),
            folderStatus.observe(),
            connectivityMonitor.observe(),
            combine(
                syncStatus.observeLastSyncAt(),
                listRepository.observeUnsyncedCount(),
                settingsRepository.observeNextcloudAccount(),
                ::Triple,
            ),
        ) { inFlight, work, folder, connectivity, sync ->
            Snapshot(inFlight, work, folder, connectivity.online, sync.first, sync.second, sync.third != null)
        }.flatMapLatest { snapshot ->
            settingsRepository.observeDeliveredClearedAt().flatMapLatest { clearedAt ->
                listRepository.observeRecentlyDelivered(since = clearedAt, limit = RECENT_LIMIT).map { delivered ->
                    snapshot.toUiState(delivered)
                }
            }
        }.stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(SUBSCRIPTION_TIMEOUT_MS),
            initialValue = ActivityUiState(),
        )

    private suspend fun Snapshot.toUiState(delivered: List<EpisodeLedgerRow>): ActivityUiState {
        val feeds = feedRepository.getAll().associateBy(Feed::url)
        val rows =
            inFlight.map { item ->
                val feed = feeds[item.episode.feedUrl]
                item.toUi(feedTitle = feed?.title ?: item.episode.feedUrl, feedArtworkUrl = feed?.imageUrl, work = work)
            }

        return ActivityUiState(
            queueStatus = queueStatusFor(folder, rows),
            sync =
                SyncUi(
                    lastSyncAt = lastSyncAt,
                    outboxDepth = outboxDepth,
                    canSyncNow = online && configured,
                    blockedReason =
                        when {
                            !configured -> BlockedReason.NOT_CONFIGURED
                            !online -> BlockedReason.OFFLINE
                            else -> null
                        },
                ),
            downloading = rows.filter { it.ledgerState == LedgerState.DOWNLOADING },
            queued =
                rows
                    .filter { it.ledgerState == LedgerState.QUEUED }
                    .map { QueuedUi(it, waitReason(folder, online)) },
            failed = rows.filter { it.ledgerState == LedgerState.ERROR },
            // Already limited, ordered and cursor-filtered by SQL. The cursor hides rows and deletes
            // nothing: those rows are what stop an episode being downloaded twice (CLAUDE.md §11).
            recent =
                delivered.map {
                    DeliveredUi(
                        fileName = it.writtenFileName.orEmpty(),
                        folderLabel = feeds[it.feedUrl]?.title,
                        episodeKey = it.episodeKey,
                        feedUrl = it.feedUrl,
                    )
                },
        )
    }

    fun onEvent(event: ActivityEvent) {
        when (event) {
            ActivityEvent.SyncNowClicked -> requestSync()
            is ActivityEvent.CancelClicked -> scheduler.cancelDownload(event.episodeKey)
            is ActivityEvent.RetryClicked -> viewModelScope.launch { retry(event.episodeKey) }
            is ActivityEvent.MarkAsPlayedClicked -> viewModelScope.launch { markAsPlayed(event.episodeKey) }
            is ActivityEvent.DetailsClicked -> emit(ActivityEffect.OpenErrorLog)
            is ActivityEvent.RowClicked -> emit(ActivityEffect.OpenEpisodeDetail(event.episodeKey))
            // Hides the list; never deletes a ledger row. Those rows are what stop an episode being
            // downloaded a second time (CLAUDE.md §11), so "clear" here means "stop showing me these".
            ActivityEvent.ClearDeliveredClicked ->
                viewModelScope.launch { settingsRepository.setDeliveredClearedAt(clock.millis()) }
            ActivityEvent.PausedBannerActionClicked -> emit(ActivityEffect.ChooseFolder)
            ActivityEvent.ErrorLogClicked -> emit(ActivityEffect.OpenErrorLog)
        }
    }

    private fun requestSync() {
        viewModelScope.launch {
            if (!connectivityMonitor.observe().first().online) {
                emit(ActivityEffect.ShowMessage("No network connection"))
                return@launch
            }
            syncNow.requestSyncNow()
            emit(ActivityEffect.ShowMessage("Syncing…"))
        }
    }

    /**
     * A retry is a re-decision, so it goes through [TriageWriter.queue] exactly as S2's does —
     * `attempts` resets and `lastError` clears, and a fresh download does not render as "attempt 3
     * of 3" before it starts (`docs/decisions/0012` §3).
     */
    private suspend fun retry(episodeKey: String) {
        val episode = episodeRepository.get(episodeKey) ?: return
        triageWriter.queue(listOf(episode))
        scheduler.enqueueDownload(episodeKey, userRequested = false)
    }

    private suspend fun markAsPlayed(episodeKey: String) {
        val episode = episodeRepository.get(episodeKey) ?: return
        triageWriter.markAsPlayed(listOf(episode))
    }

    private fun emit(effect: ActivityEffect) {
        effects.trySend(effect)
    }

    private data class Snapshot(
        val inFlight: List<EpisodeListItem>,
        val work: DownloadWork,
        val folder: FolderState,
        val online: Boolean,
        val lastSyncAt: java.time.Instant?,
        val outboxDepth: Int,
        val configured: Boolean,
    )
}

/**
 * Why a queued download has not started.
 *
 * Folder first: it is the only one the user can act on from here, and reporting "waiting for Wi-Fi"
 * while the real blocker is a missing folder sends them to the wrong place.
 */
internal fun waitReason(
    folder: FolderState,
    online: Boolean,
): WaitReason =
    when {
        folder != FolderState.GRANTED -> WaitReason.FOLDER
        !online -> WaitReason.NETWORK
        else -> WaitReason.WIFI
    }

/** `:app` owns `SyncWorker`; this keeps the view model from importing WorkManager. */
fun interface ActivitySyncTrigger {
    fun requestSyncNow()
}
