// SPDX-License-Identifier: GPL-3.0-or-later

package net.drehtuer.podsilo.ui

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.map
import net.drehtuer.podsilo.core.download.DownloadFolderAccess
import net.drehtuer.podsilo.core.download.DownloadFolderState
import net.drehtuer.podsilo.core.model.Episode
import net.drehtuer.podsilo.core.model.EpochTime
import net.drehtuer.podsilo.core.model.port.EpisodeLedgerRepository
import net.drehtuer.podsilo.core.model.port.FeedRepository
import net.drehtuer.podsilo.core.model.port.LedgerFilter
import net.drehtuer.podsilo.core.model.port.LedgerFilterState
import net.drehtuer.podsilo.core.model.port.LogRepository
import net.drehtuer.podsilo.core.model.port.SyncStateRepository
import net.drehtuer.podsilo.feature.settings.FolderState
import net.drehtuer.podsilo.feature.settings.FolderUi
import net.drehtuer.podsilo.feature.settings.NamingSampleSource
import net.drehtuer.podsilo.feature.settings.SettingsCounts
import net.drehtuer.podsilo.feature.settings.SettingsFolderStatus
import net.drehtuer.podsilo.feature.settings.SyncStatus
import java.time.Instant
import javax.inject.Inject
import javax.inject.Singleton

/**
 * S4's ports, over the adapters `:app` already owns — the same arrangement as `EpisodeAdapters`, so
 * `:feature:settings` sees neither WorkManager nor `:core:download`.
 */
@Singleton
class SettingsFolderStatusAdapter
    @Inject
    constructor(
        private val access: DownloadFolderAccess,
        private val label: DocumentFolderLabel,
    ) : SettingsFolderStatus {
        override fun observe(): Flow<FolderUi> =
            access.observe().map { state ->
                FolderUi(
                    // Only resolve the name when there is a grant to resolve it through; a revoked
                    // tree URI cannot be queried, and a stale name would be worse than none.
                    label = if (state is DownloadFolderState.Granted) label.current() else null,
                    state =
                        when (state) {
                            DownloadFolderState.NotChosen -> FolderState.NOT_CHOSEN
                            is DownloadFolderState.Granted -> FolderState.GRANTED
                            is DownloadFolderState.Revoked -> FolderState.REVOKED
                        },
                )
            }
    }

/**
 * The two integers S4 shows as row subtitles.
 *
 * The outbox depth is the *whole* unsynced set, not one feed's: it answers "is anything stuck",
 * which is what a user checking the last-sync row wants to know.
 */
@Singleton
class SettingsCountsAdapter
    @Inject
    constructor(
        private val logRepository: LogRepository,
        private val ledgerRepository: EpisodeLedgerRepository,
    ) : SettingsCounts {
        override fun observeErrorLogCount(): Flow<Int> = logRepository.observe(category = null).map { it.size }

        override fun observeOutboxDepth(): Flow<Int> =
            ledgerRepository
                .observe(LedgerFilter(state = LedgerFilterState.ALL))
                .map { rows -> rows.count { !it.syncedToServer } }
    }

/**
 * When the last sync pass finished.
 *
 * Reads the **server's** timestamp, which is what `SyncState` persists (CLAUDE.md §11: never compute
 * it from local device time). `0` means no pass has ever completed, and renders as *never* rather
 * than as 1970.
 */
@Singleton
class SyncStatusAdapter
    @Inject
    constructor(
        private val syncStateRepository: SyncStateRepository,
    ) : SyncStatus {
        override fun observeLastSyncAt(): Flow<Instant?> =
            flow { emit(EpochTime.ofMillisOrNull(syncStateRepository.get().lastEpisodeActionSyncTs.takeIf { it > 0 })) }
    }

/**
 * A real episode for S6's first preview line, so the author sees their own feed rather than only a
 * made-up one. `null` before the first refresh, which is normal.
 */
@Singleton
class NamingSampleSourceAdapter
    @Inject
    constructor(
        private val feedRepository: FeedRepository,
        private val episodeRepository: net.drehtuer.podsilo.core.model.port.EpisodeRepository,
    ) : NamingSampleSource {
        override suspend fun mostRecent(): Episode? {
            // The feed with the newest episode, then that feed's head — the DAO already returns
            // its episodes newest first.
            val newest = episodeRepository.latestPublicationByFeed().maxByOrNull { it.value } ?: return null
            val feed = feedRepository.get(newest.key) ?: return null
            return episodeRepository.observeForFeed(feed.url).first().firstOrNull()
        }
    }
