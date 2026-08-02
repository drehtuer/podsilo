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
import net.drehtuer.podsilo.core.model.port.LedgerFilter
import net.drehtuer.podsilo.core.model.port.LedgerFilterState

/**
 * Room-backed [EpisodeLedgerRepository] + outbox. Reads only the ledger table; the joins a screen
 * renders are [EpisodeListRepositoryImpl]'s.
 */
class EpisodeLedgerRepositoryImpl(
    private val ledgerDao: EpisodeLedgerDao,
) : EpisodeLedgerRepository {
    override fun observe(filter: LedgerFilter): Flow<List<EpisodeLedgerRow>> {
        // NEW has no ledger rows by definition (CLAUDE.md §9) — the episode-backed list is
        // EpisodeListRepository.observeEpisodes.
        val stateName =
            when (filter.state) {
                LedgerFilterState.NEW -> return flowOf(emptyList())
                LedgerFilterState.DOWNLOADED -> LedgerState.DOWNLOADED.name
                LedgerFilterState.SKIPPED -> LedgerState.SKIPPED.name
                LedgerFilterState.ALL -> null
            }
        return ledgerDao.observeRows(filter.feedUrl, stateName).map { rows -> rows.map { it.toDomain() } }
    }

    override suspend fun get(episodeKey: String): EpisodeLedgerRow? = ledgerDao.get(episodeKey)?.toDomain()

    override fun observeRow(episodeKey: String): Flow<EpisodeLedgerRow?> =
        ledgerDao.observeRow(episodeKey).map { it?.toDomain() }

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
}
