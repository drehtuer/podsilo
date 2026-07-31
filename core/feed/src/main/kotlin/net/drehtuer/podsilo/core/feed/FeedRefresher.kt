// SPDX-License-Identifier: GPL-3.0-or-later

package net.drehtuer.podsilo.core.feed

import android.util.Log
import kotlinx.coroutines.CancellationException
import net.drehtuer.podsilo.core.model.Feed
import net.drehtuer.podsilo.core.model.port.EpisodeRepository
import net.drehtuer.podsilo.core.model.port.FeedRefreshMetadata
import net.drehtuer.podsilo.core.model.port.FeedRepository
import java.time.Clock

private const val TAG = "FeedRefresher"
private const val HTTP_SERVER_ERROR_FLOOR = 500

/**
 * Refreshes followed feeds: conditional `GET` → parse → replace that feed's cached episodes
 * (`docs/architecture.md` §7's refresh sequence).
 *
 * **Refreshing is not downloading.** This writes `Episode` rows and nothing else — no ledger row,
 * no episode action, no file. A feed exposing 5,000 back-catalogue episodes therefore costs a list
 * query, not 5,000 downloads (CLAUDE.md §1's no-auto-download rule; the backlog is handled by the
 * "New" filter's `pubDate >= firstSeenAt` cutoff, not here). It has no ledger, download or GPodder
 * dependency at all, which is what makes that guarantee structural rather than a matter of care.
 *
 * Separate from [FeedRefreshWorker] so the logic is a plain class: the worker adds only WorkManager's
 * retry contract on top.
 */
class FeedRefresher(
    private val feedRepository: FeedRepository,
    private val episodeRepository: EpisodeRepository,
    private val feedFetcher: FeedFetcher,
    private val feedXmlParser: FeedXmlParser,
    private val clock: Clock,
) {
    /**
     * Refreshes every followed feed. One feed failing never stops the others.
     *
     * @return how many feeds failed *transiently* — i.e. how many are worth another attempt.
     */
    suspend fun refreshAll(): Int = feedRepository.getAll().count { feed -> !refresh(feed) }

    /** @return `false` only for a transient failure worth retrying. */
    private suspend fun refresh(feed: Feed): Boolean =
        when (val result = feedFetcher.fetch(feed.url, feed.httpEtag, feed.httpLastModified)) {
            is FeedFetchResult.NotModified -> true
            is FeedFetchResult.Fetched -> store(feed, result)
            // A 404/410 means the feed is gone; retrying achieves nothing, and the subscription list
            // is the server's business, not ours to prune (CLAUDE.md §1 — read-only follower).
            is FeedFetchResult.HttpError -> {
                Log.w(TAG, "feed ${feed.url} returned HTTP ${result.code} ${result.message}")
                result.code < HTTP_SERVER_ERROR_FLOOR
            }
            is FeedFetchResult.NetworkError -> {
                Log.w(TAG, "feed ${feed.url} unreachable: ${result.reason}")
                false
            }
        }

    private suspend fun store(
        feed: Feed,
        fetched: FeedFetchResult.Fetched,
    ): Boolean {
        val parsed =
            try {
                feedXmlParser.parse(feed.url, fetched.bytes)
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (
                @Suppress("TooGenericExceptionCaught") malformed: Exception,
            ) {
                // rssparser throws on XML it cannot make any sense of. That is a property of the
                // feed, not a transient condition, so retrying the same bytes is pointless — keep the
                // previously cached episodes, log what happened, and move on to the next feed.
                Log.w(TAG, "feed ${feed.url} could not be parsed; keeping the cached episodes", malformed)
                return true
            }

        episodeRepository.replaceForFeed(feed.url, parsed.episodes)
        feedRepository.updateRefreshMetadata(
            feedUrl = feed.url,
            metadata =
                FeedRefreshMetadata(
                    // The GPodder API has no feed titles, so the RSS is the only place one comes
                    // from; until the first successful fetch the URL stands in (architecture.md §4).
                    title = parsed.metadata.title ?: feed.title,
                    imageUrl = parsed.metadata.imageUrl ?: feed.imageUrl,
                    httpEtag = fetched.httpEtag,
                    httpLastModified = fetched.httpLastModified,
                    refreshedAt = clock.millis(),
                ),
        )
        return true
    }
}
