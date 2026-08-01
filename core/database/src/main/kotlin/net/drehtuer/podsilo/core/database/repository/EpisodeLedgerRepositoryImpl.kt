// SPDX-License-Identifier: GPL-3.0-or-later

package net.drehtuer.podsilo.core.database.repository

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import net.drehtuer.podsilo.core.database.dao.EpisodeLedgerDao
import net.drehtuer.podsilo.core.database.dao.EpisodeListDao
import net.drehtuer.podsilo.core.database.toDomain
import net.drehtuer.podsilo.core.database.toEntity
import net.drehtuer.podsilo.core.model.EpisodeLedgerRow
import net.drehtuer.podsilo.core.model.LedgerState
import net.drehtuer.podsilo.core.model.port.BulkScope
import net.drehtuer.podsilo.core.model.port.BulkScopeKind
import net.drehtuer.podsilo.core.model.port.EpisodeLedgerRepository
import net.drehtuer.podsilo.core.model.port.EpisodeListItem
import net.drehtuer.podsilo.core.model.port.FeedUndecidedCount
import net.drehtuer.podsilo.core.model.port.LedgerFilter
import net.drehtuer.podsilo.core.model.port.LedgerFilterState

/** Room-backed [EpisodeLedgerRepository] + outbox. Bound to the port via Hilt `@Binds` in `:app` (Tier 4c). */
class EpisodeLedgerRepositoryImpl(
    private val ledgerDao: EpisodeLedgerDao,
    private val listDao: EpisodeListDao,
) : EpisodeLedgerRepository {
    override fun observe(filter: LedgerFilter): Flow<List<EpisodeLedgerRow>> {
        // NEW has no ledger rows by definition (CLAUDE.md §9) — the episode-backed list is observeEpisodes.
        val stateName =
            when (filter.state) {
                LedgerFilterState.NEW -> return flowOf(emptyList())
                LedgerFilterState.DOWNLOADED -> LedgerState.DOWNLOADED.name
                LedgerFilterState.SKIPPED -> LedgerState.SKIPPED.name
                LedgerFilterState.ALL -> null
            }
        return ledgerDao.observeRows(filter.feedUrl, stateName).map { rows -> rows.map { it.toDomain() } }
    }

    override fun observeEpisodes(filter: LedgerFilter): Flow<List<EpisodeListItem>> {
        val rows =
            when (filter.state) {
                LedgerFilterState.NEW -> listDao.observeNewEpisodes(filter.feedUrl, filter.includeBacklog)
                LedgerFilterState.ALL -> listDao.observeAllEpisodes(filter.feedUrl)
                LedgerFilterState.DOWNLOADED ->
                    listDao.observeEpisodesByState(filter.feedUrl, LedgerState.DOWNLOADED.name)
                LedgerFilterState.SKIPPED ->
                    listDao.observeEpisodesByState(filter.feedUrl, LedgerState.SKIPPED.name)
            }
        return rows.map { list -> list.map { it.toDomain() } }
    }

    override suspend fun get(episodeKey: String): EpisodeLedgerRow? = ledgerDao.get(episodeKey)?.toDomain()

    override suspend fun upsert(row: EpisodeLedgerRow) {
        ledgerDao.upsert(row.toEntity())
    }

    override suspend fun getUnsynced(): List<EpisodeLedgerRow> = ledgerDao.getUnsynced().map { it.toDomain() }

    override suspend fun markSynced(episodeKeys: List<String>) {
        ledgerDao.markSynced(episodeKeys)
    }

    override suspend fun upsertAll(rows: List<EpisodeLedgerRow>) {
        ledgerDao.upsertAll(rows.map { it.toEntity() })
    }

    override suspend fun previewUndecided(scope: BulkScope): List<FeedUndecidedCount> =
        listDao
            .countUndecidedByFeed(feedUrl = scope.feedUrl, olderThanMillis = scope.cutoffOrNull())
            .map { FeedUndecidedCount(feedUrl = it.feedUrl, count = it.count) }
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
