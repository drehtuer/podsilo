// SPDX-License-Identifier: GPL-3.0-or-later

package net.drehtuer.podsilo.core.sync

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.map
import net.drehtuer.podsilo.core.model.EpisodeLedgerRow
import net.drehtuer.podsilo.core.model.port.BulkScope
import net.drehtuer.podsilo.core.model.port.EpisodeLedgerRepository
import net.drehtuer.podsilo.core.model.port.EpisodeListItem
import net.drehtuer.podsilo.core.model.port.FeedUndecidedCount
import net.drehtuer.podsilo.core.model.port.LedgerFilter

/**
 * Ignores [LedgerFilter] entirely -- resolving the UI-facing NEW/backlog join is Room's job
 * (`:core:database`, Tier 4), and `SyncOrchestrator` only ever reads the full ledger via `ALL`.
 */
class FakeEpisodeLedgerRepository(
    initial: List<EpisodeLedgerRow> = emptyList(),
) : EpisodeLedgerRepository {
    private val state = MutableStateFlow(initial.associateBy { it.episodeKey })

    val allRows: List<EpisodeLedgerRow> get() = state.value.values.toList()

    override fun observe(filter: LedgerFilter): Flow<List<EpisodeLedgerRow>> = state.map { it.values.toList() }

    // SyncOrchestrator never reads the UI-facing episode list; the New/backlog join is Room's job.
    override fun observeEpisodes(filter: LedgerFilter): Flow<List<EpisodeListItem>> = MutableStateFlow(emptyList())

    override suspend fun get(episodeKey: String): EpisodeLedgerRow? = state.value[episodeKey]

    override suspend fun upsert(row: EpisodeLedgerRow) {
        state.value = state.value + (row.episodeKey to row)
    }

    override suspend fun getUnsynced(): List<EpisodeLedgerRow> = state.value.values.filterNot { it.syncedToServer }

    override suspend fun markSynced(episodeKeys: List<String>) {
        val keys = episodeKeys.toSet()
        state.value = state.value.mapValues { (key, row) -> if (key in keys) row.copy(syncedToServer = true) else row }
    }

    override suspend fun upsertAll(rows: List<EpisodeLedgerRow>) = rows.forEach { row -> upsert(row) }

    // Bulk triage is a UI path; SyncOrchestrator never previews or writes in batches.
    override suspend fun previewUndecided(scope: BulkScope): List<FeedUndecidedCount> = emptyList()
}
