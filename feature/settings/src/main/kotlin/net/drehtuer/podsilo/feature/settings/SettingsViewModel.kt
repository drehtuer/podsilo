// SPDX-License-Identifier: GPL-3.0-or-later

package net.drehtuer.podsilo.feature.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import net.drehtuer.podsilo.core.model.Episode
import net.drehtuer.podsilo.core.model.EpisodeLedgerRow
import net.drehtuer.podsilo.core.model.EpochTime
import net.drehtuer.podsilo.core.model.LedgerState
import net.drehtuer.podsilo.core.model.port.BulkScope
import net.drehtuer.podsilo.core.model.port.BulkScopeKind
import net.drehtuer.podsilo.core.model.port.EpisodeLedgerRepository
import net.drehtuer.podsilo.core.model.port.EpisodeListRepository
import net.drehtuer.podsilo.core.model.port.FeedRepository
import net.drehtuer.podsilo.core.model.port.NamingSettings
import net.drehtuer.podsilo.core.model.port.OlderThan
import net.drehtuer.podsilo.core.model.port.SettingsRepository
import java.time.Clock
import java.time.ZoneId

/** Matches the episode screens' grace period, so navigating away and back does not restart queries. */
private const val SUBSCRIPTION_TIMEOUT_MS = 5_000L

private const val MILLIS_PER_SECOND = 1_000

/**
 * S4 (`docs/UI_interface.md` §5).
 *
 * Every control commits immediately — there is no Save button and no form buffer (`docs/UI.md` §7).
 * The single exception is the bulk *mark as played*, which is the one operation here that reaches
 * the shared action log and cannot be undone in bulk, so it goes through a preview that names the
 * count first (`docs/decisions/0013`).
 */
@Suppress("LongParameterList", "TooManyFunctions") // A view model's parameter list is its port list.
class SettingsViewModel(
    private val settingsRepository: SettingsRepository,
    private val ledgerRepository: EpisodeLedgerRepository,
    private val listRepository: EpisodeListRepository,
    private val feedRepository: FeedRepository,
    private val folderStatus: SettingsFolderStatus,
    private val counts: SettingsCounts,
    private val namingSummary: NamingSummary,
    private val syncStatus: SyncStatus,
    private val clock: Clock,
    private val version: String,
    private val zone: ZoneId = ZoneId.systemDefault(),
) : ViewModel() {
    private val pendingBulk = MutableStateFlow<BulkConfirmation?>(null)

    private val effects = Channel<SettingsEffect>(Channel.BUFFERED)
    val effect: Flow<SettingsEffect> = effects.receiveAsFlow()

    val state: StateFlow<SettingsUiState> =
        combine(
            combine(
                settingsRepository.observeNextcloudAccount(),
                syncStatus.observeLastSyncAt(),
                counts.observeOutboxDepth(),
            ) { account, lastSync, depth ->
                NextcloudUi(
                    instanceUrl = account?.serverUrl,
                    loginName = account?.username,
                    // The account carries no connection date; the last sync is the honest
                    // "this worked" signal, and the row says so rather than inventing one.
                    connectedAt = null,
                    lastSyncAt = lastSync,
                    outboxDepth = depth,
                )
            },
            combine(folderStatus.observe(), settingsRepository.observeNaming(), ::Pair),
            combine(
                settingsRepository.observeAllowMobileData(),
                settingsRepository.observeSwipeMapping(),
                settingsRepository.observeMarkOldOlderThan(),
                settingsRepository.observeTheme(),
                ::Preferences,
            ),
            counts.observeErrorLogCount(),
            pendingBulk,
        ) { nextcloud, folderAndNaming, preferences, logCount, bulk ->
            SettingsUiState(
                nextcloud = nextcloud,
                downloadFolder = folderAndNaming.first,
                namingSummary = namingSummary.render(folderAndNaming.second),
                allowMobileData = preferences.allowMobileData,
                swipeMapping = preferences.swipeMapping,
                markOldOlderThan = preferences.olderThan,
                theme = preferences.theme,
                errorLogCount = logCount,
                version = version,
                pendingBulk = bulk,
            )
        }.stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(SUBSCRIPTION_TIMEOUT_MS),
            initialValue = SettingsUiState(version = version),
        )

    @Suppress("CyclomaticComplexMethod") // An exhaustive `when` over a sealed event hierarchy.
    fun onEvent(event: SettingsEvent) {
        when (event) {
            SettingsEvent.ConnectClicked -> emit(SettingsEffect.OpenConnect)
            SettingsEvent.DisconnectClicked -> viewModelScope.launch { disconnect() }
            SettingsEvent.ChooseFolderClicked -> emit(SettingsEffect.ChooseFolder)
            SettingsEvent.NamingClicked -> emit(SettingsEffect.OpenNaming)
            SettingsEvent.LastSyncClicked -> emit(SettingsEffect.OpenActivity)
            SettingsEvent.ErrorLogClicked -> emit(SettingsEffect.OpenErrorLog)
            is SettingsEvent.MobileDataChanged ->
                viewModelScope.launch { settingsRepository.setAllowMobileData(event.allowed) }
            is SettingsEvent.SwipeChanged -> viewModelScope.launch { changeSwipe(event) }
            is SettingsEvent.OlderThanChanged ->
                viewModelScope.launch { settingsRepository.setMarkOldOlderThan(event.value) }
            is SettingsEvent.ThemeChanged -> viewModelScope.launch { settingsRepository.setTheme(event.theme) }
            is SettingsEvent.BulkPreviewRequested -> viewModelScope.launch { preview(event.scope) }
            SettingsEvent.BulkConfirmed -> viewModelScope.launch { applyBulk() }
            SettingsEvent.BulkCancelled -> pendingBulk.value = null
        }
    }

    /**
     * Clears the credentials and **keeps the ledger**.
     *
     * That is the whole point of the warning the row carries: the ledger has no foreign key to
     * feeds (architecture §4), so reconnecting does not re-download a back catalogue the user
     * already handled.
     */
    private suspend fun disconnect() {
        settingsRepository.setNextcloudCredentials(null)
        emit(SettingsEffect.ShowMessage("Disconnected. Your download history is kept."))
    }

    /**
     * Delegates the "the two directions can't hold the same action" rule to [SwipeMapping.with],
     * which **swaps** rather than rejects — the user's most recent choice always wins and the
     * pair stays valid, so the swipe background needs no defensive branch (`docs/UI.md` §7).
     */
    private suspend fun changeSwipe(event: SettingsEvent.SwipeChanged) {
        val current = settingsRepository.observeSwipeMapping().first()
        settingsRepository.setSwipeMapping(current.with(event.direction, event.action))
    }

    /**
     * Counts, and writes nothing. The dialog this fills is the safeguard that replaced the old
     * rule against writing backlog rows at all (`docs/decisions/0013`), so it must run the same
     * predicate the write will use — which is why both go through [EpisodeListRepository].
     */
    private suspend fun preview(scope: BulkScope) {
        val resolved = scope.withCutoff()
        val perFeed = listRepository.previewUndecided(resolved)
        if (perFeed.isEmpty()) {
            emit(SettingsEffect.ShowMessage("Nothing to mark — every episode has been decided."))
            return
        }
        val titles = feedRepository.getAll().associate { it.url to it.title }
        pendingBulk.value =
            BulkConfirmation(
                scope = resolved,
                // The feed's title, falling back to its URL — never "Unknown podcast".
                perFeed = perFeed.map { FeedCount(titles[it.feedUrl] ?: it.feedUrl, it.count) },
            )
    }

    /**
     * Writes `SKIPPED` rows in **one transaction**, and only `SKIPPED` — never `QUEUED`. The
     * no-auto-download invariant is untouched by this operation (CLAUDE.md §1); what it produces
     * is `PLAY` actions, which drain through the normal outbox in batches rather than one POST.
     */
    private suspend fun applyBulk() {
        val confirmation = pendingBulk.value ?: return
        pendingBulk.value = null
        val episodes = listRepository.undecided(confirmation.scope)
        if (episodes.isEmpty()) return

        val now = clock.millis()
        ledgerRepository.upsertAll(episodes.map { it.toSkippedRow(now) })
        emit(SettingsEffect.ShowMessage("Marked ${episodes.size} episodes as played."))
    }

    /**
     * Resolves `OLDER_THAN` against the *stored* cutoff at the moment the preview is taken, so
     * the dialog's count and the write that follows cannot straddle a settings change.
     */
    private suspend fun BulkScope.withCutoff(): BulkScope =
        if (kind == BulkScopeKind.OLDER_THAN) {
            copy(
                olderThanMillis =
                    settingsRepository.observeMarkOldOlderThan().first().cutoffMillis(
                        clock.instant(),
                        zone,
                    ),
            )
        } else {
            this
        }

    private fun emit(effect: SettingsEffect) {
        effects.trySend(effect)
    }

    private data class Preferences(
        val allowMobileData: Boolean,
        val swipeMapping: net.drehtuer.podsilo.core.model.port.SwipeMapping,
        val olderThan: OlderThan,
        val theme: net.drehtuer.podsilo.core.model.port.ThemePreference,
    )
}

/**
 * Snapshots what a later reader needs, exactly as `TriageWriter` does for a swipe
 * (`docs/decisions/0001`) — and `syncedToServer = false`, because the durable row exists before
 * anything is posted and only a confirmed 2xx flips it (CLAUDE.md §5).
 */
internal fun Episode.toSkippedRow(now: Long): EpisodeLedgerRow =
    EpisodeLedgerRow(
        episodeKey = episodeKey,
        feedUrl = feedUrl,
        enclosureUrl = enclosureUrl,
        state = LedgerState.SKIPPED,
        actionedAt = now,
        syncedToServer = false,
        attempts = 0,
        lastError = null,
        writtenFileName = null,
        durationSeconds = durationMs?.let { (it / MILLIS_PER_SECOND).toInt() },
    )

/** Mirrors `DownloadFolderAccess` without dragging `:core:download` into the settings module. */
fun interface SettingsFolderStatus {
    fun observe(): Flow<FolderUi>
}

/** The one-line summary S4 shows under *File naming*; the same engine S6 previews with. */
fun interface NamingSummary {
    fun render(settings: NamingSettings): String
}

/** When the last sync pass finished, for the *Last sync* row. `null` means "never". */
fun interface SyncStatus {
    fun observeLastSyncAt(): Flow<java.time.Instant?>
}

/** Kept next to [SyncStatus] because it is the same conversion, done once. */
internal fun lastSyncInstant(epochMillis: Long): java.time.Instant? =
    EpochTime.ofMillisOrNull(epochMillis.takeIf { it > 0 })
