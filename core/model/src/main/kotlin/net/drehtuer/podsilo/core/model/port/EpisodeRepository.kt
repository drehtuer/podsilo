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

    /**
     * One episode by key, for `DownloadWorker` (which is handed only an `episodeKey` in its input
     * data). `null` when the row has been pruned — the cache is disposable, so a queued download
     * whose feed was unsubscribed mid-flight is an expected outcome, not an error.
     */
    suspend fun get(episodeKey: String): Episode?

    /**
     * The newest `pubDate` per feed, for S1's ordering. Feeds with no dated episode are absent from
     * the map rather than present with a zero — "never fetched" sorts last, and a fabricated date
     * would sort it as ancient instead (`docs/UI.md` §4).
     *
     * Deliberately `suspend` and not a [Flow]: S1's order is frozen between explicit refreshes, so
     * observing this would be the exact bug that rule exists to prevent — rows moving under the
     * user's finger (`docs/UI.md` §B2).
     */
    suspend fun latestPublicationByFeed(): Map<String, Long>

    suspend fun replaceForFeed(
        feedUrl: String,
        episodes: List<Episode>,
    )

    /** Called when a feed disappears from the subscription list; never cascades to the ledger. */
    suspend fun deleteForFeed(feedUrl: String)
}
