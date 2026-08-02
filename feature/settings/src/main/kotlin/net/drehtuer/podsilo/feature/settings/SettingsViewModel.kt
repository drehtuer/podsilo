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
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import net.drehtuer.podsilo.core.model.Episode
import net.drehtuer.podsilo.core.model.EpisodeLedgerRow
import net.drehtuer.podsilo.core.model.EpochTime
import net.drehtuer.podsilo.core.model.LedgerState
import net.drehtuer.podsilo.core.model.port.ArchiveFailure
import net.drehtuer.podsilo.core.model.port.ArchiveOutcome
import net.drehtuer.podsilo.core.model.port.BulkScope
import net.drehtuer.podsilo.core.model.port.BulkScopeKind
import net.drehtuer.podsilo.core.model.port.DatabaseArchive
import net.drehtuer.podsilo.core.model.port.EpisodeLedgerRepository
import net.drehtuer.podsilo.core.model.port.EpisodeListRepository
import net.drehtuer.podsilo.core.model.port.FeedRepository
import net.drehtuer.podsilo.core.model.port.NamingSettings
import net.drehtuer.podsilo.core.model.port.OlderThan
import net.drehtuer.podsilo.core.model.port.SettingsRepository
import java.time.Clock
import java.time.LocalDate
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
    private val archive: DatabaseArchive,
    private val clock: Clock,
    private val version: String,
    private val zone: ZoneId = ZoneId.systemDefault(),
) : ViewModel() {
    private val pendingBulk = MutableStateFlow<BulkConfirmation?>(null)
    private val archiveUi = MutableStateFlow(ArchiveUi())

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
            // Paired rather than passed separately: `combine` tops out at five sources, and these
            // two are both "a dialog is open / an operation is running" transient UI state.
            combine(pendingBulk, archiveUi, ::Pair),
        ) { nextcloud, folderAndNaming, preferences, logCount, transient ->
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
                pendingBulk = transient.first,
                restoreConfirmationVisible = transient.second.confirmingRestore,
                archiveBusy = transient.second.busy,
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
            SettingsEvent.ExportDatabaseClicked -> emit(SettingsEffect.CreateBackupFile(backupFileName()))
            SettingsEvent.RestoreDatabaseClicked -> viewModelScope.launch { requestRestore() }
            SettingsEvent.RestoreCancelled -> archiveUi.update { it.copy(confirmingRestore = false) }
            SettingsEvent.RestoreConfirmed -> {
                archiveUi.update { it.copy(confirmingRestore = false) }
                emit(SettingsEffect.OpenBackupFile)
            }
            is SettingsEvent.BackupDestinationChosen ->
                viewModelScope.launch { runArchive { archive.exportTo(event.uri) } }
            is SettingsEvent.BackupSourceChosen ->
                viewModelScope.launch { runArchive { archive.importFrom(event.uri) } }
        }
    }

    /**
     * **A backup is never loaded before Nextcloud is connected** — the author's rule, and the guard
     * lives here rather than only on the row so it holds however the event arrives.
     *
     * The reason is sequencing, not secrecy. The archive deliberately carries no credentials
     * (`docs/decisions/0018`), so a restore onto an unconfigured install drops the ledger behind a
     * *not configured* screen that shows none of it — which is precisely how it read on the Pixel 5,
     * with the snackbar reporting restored podcasts the list could not display. Connecting first
     * means the restored ledger lands somewhere that renders it, and the next sync reconciles it.
     */
    private suspend fun requestRestore() {
        if (settingsRepository.observeNextcloudAccount().first() == null) {
            emit(SettingsEffect.ShowMessage("Connect Nextcloud before restoring a backup."))
            return
        }
        archiveUi.update { it.copy(confirmingRestore = true) }
    }

    /**
     * Dated, so successive backups sit next to each other in the file picker instead of one
     * silently replacing the last. The picker still lets the user rename it.
     */
    private fun backupFileName(): String = "podsilo-backup-${LocalDate.now(clock.withZone(zone))}.zip"

    private suspend fun runArchive(operation: suspend () -> ArchiveOutcome) {
        archiveUi.update { it.copy(busy = true) }
        val outcome = operation()
        archiveUi.update { it.copy(busy = false) }
        emit(SettingsEffect.ShowMessage(outcome.message()))
    }

    /**
     * Failures name the next step rather than the exception: "not a Podsilo backup" and "update the
     * app" are different instructions, and an error the user cannot act on is noise.
     */
    private fun ArchiveOutcome.message(): String =
        when (this) {
            is ArchiveOutcome.Exported ->
                "Backup saved — ${contents.feeds} podcasts, ${contents.ledgerRows} handled episodes."
            is ArchiveOutcome.Imported ->
                "Restored ${contents.feeds} podcasts and ${contents.ledgerRows} handled episodes."
            is ArchiveOutcome.Failed ->
                when (reason) {
                    ArchiveFailure.NOT_AN_ARCHIVE -> "That file isn't a Podsilo backup."
                    ArchiveFailure.NEWER_SCHEMA -> "That backup was made by a newer Podsilo. Update the app first."
                    ArchiveFailure.UNREADABLE -> "That backup couldn't be read. Nothing was changed."
                    ArchiveFailure.WRITE_FAILED -> "The backup couldn't be written."
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

/** Transient backup state: the warning dialog, and whether a zip is being written or read. */
private data class ArchiveUi(
    val confirmingRestore: Boolean = false,
    val busy: Boolean = false,
)
