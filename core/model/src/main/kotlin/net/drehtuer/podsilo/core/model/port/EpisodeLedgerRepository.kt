// SPDX-License-Identifier: GPL-3.0-or-later

package net.drehtuer.podsilo.core.model.port

import kotlinx.coroutines.flow.Flow
import net.drehtuer.podsilo.core.model.EpisodeLedgerRow

/**
 * Port for the ledger — "the one table that must never be lost" (CLAUDE.md §5). Implemented in
 * `:core:database` (Room). Doubles as the sync outbox: [getUnsynced] is the drain query,
 * [markSynced] is the only way `syncedToServer` flips to `true`.
 *
 * The joins a screen renders live on [EpisodeListRepository], mirroring the DAO split. This
 * interface is deliberately about *rows*, and therefore cannot express "new" at all — new is the
 * absence of a row.
 */
interface EpisodeLedgerRepository {
    /**
     * Ledger rows whose [EpisodeLedgerRow.state] matches [filter]. [LedgerFilterState.NEW] returns
     * an empty list by definition; [EpisodeListRepository.observeEpisodes] is the episode-backed
     * listing the UI actually renders. [LedgerFilter.feedUrl] narrows to a single feed when set.
     */
    fun observe(filter: LedgerFilter): Flow<List<EpisodeLedgerRow>>

    /**
     * The single durable "has this already been handled?" lookup (CLAUDE.md §11). `DownloadWorker`
     * reads it to reuse `writtenFileName` on a retry and to bail out when a remote action reached a
     * terminal state while the download was queued — never a file-existence check.
     */
    suspend fun get(episodeKey: String): EpisodeLedgerRow?

    /**
     * One episode's ledger row, live. `null` means the episode has no action anywhere.
     *
     * Exists for S3, which stays open while a download it started runs: the alternative is
     * subscribing to the whole feed's rows and filtering in Kotlin, which is a 500-row emission to
     * re-render one sheet.
     */
    fun observeRow(episodeKey: String): Flow<EpisodeLedgerRow?>

    suspend fun upsert(row: EpisodeLedgerRow)

    /**
     * Rows where `syncedToServer = false` — the outbox drain query.
     *
     * Its *depth* is a screen read and lives on [EpisodeListRepository.observeUnsyncedCount], not
     * here: `getUnsynced().size` would load every pending row on every ledger write just to render
     * one integer (issue #47).
     */
    suspend fun getUnsynced(): List<EpisodeLedgerRow>

    suspend fun markSynced(episodeKeys: List<String>)

    /**
     * Writes many rows in **one transaction and one [Flow] emission**. Bulk triage (`UI.adoc`
     * §5's *Download all* and selection mode, §7's *mark old/all as played*) routinely touches
     * hundreds of episodes; doing that as N calls to [upsert] is N transactions and N list
     * re-emissions into a `LazyColumn`.
     */
    suspend fun upsertAll(rows: List<EpisodeLedgerRow>)
}
