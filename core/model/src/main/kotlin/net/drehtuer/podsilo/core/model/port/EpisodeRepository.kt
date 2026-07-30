// SPDX-License-Identifier: GPL-3.0-or-later

package net.drehtuer.podsilo.core.model.port

import kotlinx.coroutines.flow.Flow
import net.drehtuer.podsilo.core.model.Episode

/**
 * Port for the disposable parsed-feed cache. Implemented in `:core:database` (Room). Safe to wipe
 * and rebuild per feed on every refresh — the GPodder API has no episode catalogue, so this is the
 * only source of episode data, but nothing here is durable state (CLAUDE.md §5).
 */
interface EpisodeRepository {
    fun observeForFeed(feedUrl: String): Flow<List<Episode>>

    suspend fun replaceForFeed(
        feedUrl: String,
        episodes: List<Episode>,
    )

    /** Called when a feed disappears from the subscription list; never cascades to the ledger. */
    suspend fun deleteForFeed(feedUrl: String)
}
