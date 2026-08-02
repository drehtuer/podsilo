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
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import net.drehtuer.podsilo.core.model.Feed
import net.drehtuer.podsilo.core.model.LedgerState
import net.drehtuer.podsilo.core.model.port.ConnectivityMonitor
import net.drehtuer.podsilo.core.model.port.EpisodeLedgerRepository
import net.drehtuer.podsilo.core.model.port.EpisodeListItem
import net.drehtuer.podsilo.core.model.port.EpisodeRepository
import net.drehtuer.podsilo.core.model.port.FeedRepository
import net.drehtuer.podsilo.core.model.port.LedgerFilter
import net.drehtuer.podsilo.core.model.port.LedgerFilterState
import net.drehtuer.podsilo.core.model.port.SettingsRepository
import net.drehtuer.podsilo.feature.episodes.DownloadFolderStatus
import net.drehtuer.podsilo.feature.episodes.EpisodeScheduler
import net.drehtuer.podsilo.feature.episodes.EpisodeUi
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
    private val ledgerRepository: EpisodeLedgerRepository,
    private val episodeRepository: EpisodeRepository,
    private val feedRepository: FeedRepository,
    private val settingsRepository: SettingsRepository,
    private val connectivityMonitor: ConnectivityMonitor,
    private val folderStatus: DownloadFolderStatus,
    private val syncStatus: SyncStatus,
    private val scheduler: EpisodeScheduler,
    private val triageWriter: TriageWriter,
    private val syncNow: ActivitySyncTrigger,
) : ViewModel() {
    private val effects = Channel<ActivityEffect>(Channel.BUFFERED)
    val effect: Flow<ActivityEffect> = effects.receiveAsFlow()

    val state: StateFlow<ActivityUiState> =
        combine(
            ledgerRepository.observe(LedgerFilter(state = LedgerFilterState.ALL)),
            folderStatus.observe(),
            connectivityMonitor.observe(),
            syncStatus.observeLastSyncAt(),
            settingsRepository.observeNextcloudAccount(),
        ) { rows, folder, connectivity, lastSync, account ->
            Snapshot(rows.map { it.episodeKey to it }.toMap(), folder, connectivity.online, lastSync, account != null)
        }.map { snapshot -> snapshot.toUiState() }
            .stateIn(
                scope = viewModelScope,
                started = SharingStarted.WhileSubscribed(SUBSCRIPTION_TIMEOUT_MS),
                initialValue = ActivityUiState(),
            )

    private suspend fun Snapshot.toUiState(): ActivityUiState {
        val feeds = feedRepository.getAll().associateBy(Feed::url)
        val rows = ledger.values.mapNotNull { row -> row.toEpisodeUi(feeds) }

        return ActivityUiState(
            queueStatus = queueStatusFor(folder, rows),
            sync =
                SyncUi(
                    lastSyncAt = lastSyncAt,
                    outboxDepth = ledger.values.count { !it.syncedToServer },
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
            recent =
                ledger.values
                    .asSequence()
                    .filter { it.state == LedgerState.DOWNLOADED && it.writtenFileName != null }
                    .sortedByDescending { it.actionedAt }
                    .take(RECENT_LIMIT)
                    .map {
                        DeliveredUi(
                            fileName = it.writtenFileName.orEmpty(),
                            folderLabel = feeds[it.feedUrl]?.title,
                            episodeKey = it.episodeKey,
                            feedUrl = it.feedUrl,
                        )
                    }.toList(),
        )
    }

    /**
     * The episode cache can be gone while the ledger row remains — that asymmetry is deliberate
     * (unsubscribing prunes episodes and keeps the ledger, CLAUDE.md §5). A row with no episode is
     * skipped rather than rendered with invented text.
     */
    private suspend fun net.drehtuer.podsilo.core.model.EpisodeLedgerRow.toEpisodeUi(
        feeds: Map<String, Feed>,
    ): EpisodeUi? {
        val episode = episodeRepository.get(episodeKey) ?: return null
        val feed = feeds[feedUrl]
        return EpisodeListItem(episode, this).toUi(
            feedTitle = feed?.title ?: feedUrl,
            feedArtworkUrl = feed?.imageUrl,
        )
    }

    fun onEvent(event: ActivityEvent) {
        when (event) {
            ActivityEvent.SyncNowClicked -> requestSync()
            is ActivityEvent.CancelClicked -> scheduler.cancelDownload(event.episodeKey)
            is ActivityEvent.RetryClicked -> viewModelScope.launch { retry(event.episodeKey) }
            is ActivityEvent.MarkAsPlayedClicked -> viewModelScope.launch { markAsPlayed(event.episodeKey) }
            is ActivityEvent.DetailsClicked -> emit(ActivityEffect.OpenErrorLog)
            is ActivityEvent.RowClicked -> emit(ActivityEffect.OpenEpisodes(event.feedUrl))
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
        val ledger: Map<String, net.drehtuer.podsilo.core.model.EpisodeLedgerRow>,
        val folder: FolderState,
        val online: Boolean,
        val lastSyncAt: java.time.Instant?,
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
