// SPDX-License-Identifier: GPL-3.0-or-later

package net.drehtuer.podsilo.core.database.dao

import androidx.room.Embedded
import net.drehtuer.podsilo.core.database.entity.EpisodeEntity
import net.drehtuer.podsilo.core.database.entity.EpisodeLedgerEntity

/**
 * Query result for the UI episode list: an [EpisodeEntity] plus its [EpisodeLedgerEntity] if one
 * exists. On a `LEFT JOIN` miss every `l_`-prefixed column is `NULL`, so Room leaves [ledger]
 * `null` — which is exactly "this episode is new, no action anywhere" (CLAUDE.md §9). The three
 * columns the two tables share (`episodeKey`, `feedUrl`, `enclosureUrl`) are aliased with the `l_`
 * prefix in each query's `SELECT` so they don't collide with the embedded episode's columns.
 */
data class EpisodeWithLedger(
    @Embedded val episode: EpisodeEntity,
    @Embedded(prefix = "l_") val ledger: EpisodeLedgerEntity?,
)

/**
 * One row of `countUndecidedByFeed` — how many undecided episodes a feed contributes to a pending
 * bulk operation. Maps straight to `FeedUndecidedCount` in `:core:model` at the repository
 * boundary.
 */
data class FeedUndecidedCountRow(
    val feedUrl: String,
    val count: Int,
)
