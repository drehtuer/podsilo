// SPDX-License-Identifier: GPL-3.0-or-later

package net.drehtuer.podsilo.core.database.repository

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import net.drehtuer.podsilo.core.database.dao.EpisodeLedgerDao
import net.drehtuer.podsilo.core.database.toDomain
import net.drehtuer.podsilo.core.database.toEntity
import net.drehtuer.podsilo.core.model.EpisodeLedgerRow
import net.drehtuer.podsilo.core.model.LedgerState
import net.drehtuer.podsilo.core.model.port.EpisodeLedgerRepository
import net.drehtuer.podsilo.core.model.port.EpisodeListItem
import net.drehtuer.podsilo.core.model.port.LedgerFilter
import net.drehtuer.podsilo.core.model.port.LedgerFilterState

/** Room-backed [EpisodeLedgerRepository] + outbox. Bound to the port via Hilt `@Binds` in `:app` (Tier 4c). */
class EpisodeLedgerRepositoryImpl(
    private val ledgerDao: EpisodeLedgerDao,
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
                LedgerFilterState.NEW -> ledgerDao.observeNewEpisodes(filter.feedUrl, filter.includeBacklog)
                LedgerFilterState.ALL -> ledgerDao.observeAllEpisodes(filter.feedUrl)
                LedgerFilterState.DOWNLOADED ->
                    ledgerDao.observeEpisodesByState(filter.feedUrl, LedgerState.DOWNLOADED.name)
                LedgerFilterState.SKIPPED ->
                    ledgerDao.observeEpisodesByState(filter.feedUrl, LedgerState.SKIPPED.name)
            }
        return rows.map { list -> list.map { it.toDomain() } }
    }

    override suspend fun upsert(row: EpisodeLedgerRow) {
        ledgerDao.upsert(row.toEntity())
    }

    override suspend fun getUnsynced(): List<EpisodeLedgerRow> = ledgerDao.getUnsynced().map { it.toDomain() }

    override suspend fun markSynced(episodeKeys: List<String>) {
        ledgerDao.markSynced(episodeKeys)
    }
}
