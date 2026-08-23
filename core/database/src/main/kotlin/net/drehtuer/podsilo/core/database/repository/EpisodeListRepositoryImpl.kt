// SPDX-License-Identifier: GPL-3.0-or-later

package net.drehtuer.podsilo.core.database.repository

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import net.drehtuer.podsilo.core.database.dao.EpisodeListDao
import net.drehtuer.podsilo.core.database.toDomain
import net.drehtuer.podsilo.core.model.Episode
import net.drehtuer.podsilo.core.model.EpisodeLedgerRow
import net.drehtuer.podsilo.core.model.LedgerState
import net.drehtuer.podsilo.core.model.port.BulkScope
import net.drehtuer.podsilo.core.model.port.BulkScopeKind
import net.drehtuer.podsilo.core.model.port.EpisodeListItem
import net.drehtuer.podsilo.core.model.port.EpisodeListRepository
import net.drehtuer.podsilo.core.model.port.FeedUndecidedCount
import net.drehtuer.podsilo.core.model.port.LedgerFilter
import net.drehtuer.podsilo.core.model.port.LedgerFilterState

/**
 * Room-backed [EpisodeListRepository] — every query a screen makes, over the one DAO that owns the
 * `episodes ⨝ episode_ledger` join.
 */
class EpisodeListRepositoryImpl(
    private val listDao: EpisodeListDao,
) : EpisodeListRepository {
    override fun observeEpisodes(filter: LedgerFilter): Flow<List<EpisodeListItem>> {
        val rows =
            when (filter.state) {
                LedgerFilterState.NEW -> listDao.observeNewEpisodes(filter.feedUrl)
                LedgerFilterState.ALL -> listDao.observeAllEpisodes(filter.feedUrl)
                LedgerFilterState.DOWNLOADED ->
                    listDao.observeEpisodesByState(filter.feedUrl, LedgerState.DOWNLOADED.name)
                LedgerFilterState.SKIPPED ->
                    listDao.observeEpisodesByState(filter.feedUrl, LedgerState.SKIPPED.name)
            }
        return rows.map { list -> list.map { it.toDomain() } }
    }

    override fun observeInFlight(): Flow<List<EpisodeListItem>> =
        listDao.observeInFlight().map { list -> list.map { it.toDomain() } }

    override fun observeRecentActions(limit: Int): Flow<List<EpisodeListItem>> =
        listDao.observeRecentActions(limit).map { list -> list.map { it.toDomain() } }

    override fun observeRecentlyDelivered(
        since: Long,
        limit: Int,
    ): Flow<List<EpisodeLedgerRow>> =
        listDao.observeRecentlyDelivered(since, limit).map { list ->
            list.map { it.toDomain() }
        }

    override fun observeUnsyncedCount(): Flow<Int> = listDao.observeUnsyncedCount()

    override fun observeUndecidedCounts(): Flow<List<FeedUndecidedCount>> =
        listDao.observeUndecidedCounts().map { rows ->
            rows.map { FeedUndecidedCount(feedUrl = it.feedUrl, count = it.count) }
        }

    override suspend fun previewUndecided(scope: BulkScope): List<FeedUndecidedCount> =
        listDao
            .countUndecidedByFeed(feedUrl = scope.feedUrl, olderThanMillis = scope.cutoffOrNull())
            .map { FeedUndecidedCount(feedUrl = it.feedUrl, count = it.count) }

    override suspend fun undecided(scope: BulkScope): List<Episode> =
        listDao
            .undecided(feedUrl = scope.feedUrl, olderThanMillis = scope.cutoffOrNull())
            .map { it.toDomain() }
}

/**
 * `ALL_UNDECIDED` means "no date restriction", which the SQL expresses as a null cutoff — not as a
 * cutoff of `Long.MAX_VALUE`, which would silently also sweep up undated episodes.
 */
private fun BulkScope.cutoffOrNull(): Long? =
    when (kind) {
        BulkScopeKind.ALL_UNDECIDED -> null
        BulkScopeKind.OLDER_THAN -> olderThanMillis
    }
