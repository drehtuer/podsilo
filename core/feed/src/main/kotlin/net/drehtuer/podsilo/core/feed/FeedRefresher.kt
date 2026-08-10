// SPDX-License-Identifier: GPL-3.0-or-later

package net.drehtuer.podsilo.core.feed

import android.util.Log
import kotlinx.coroutines.CancellationException
import net.drehtuer.podsilo.core.model.Feed
import net.drehtuer.podsilo.core.model.port.EpisodeRepository
import net.drehtuer.podsilo.core.model.port.FeedRefreshMetadata
import net.drehtuer.podsilo.core.model.port.FeedRepository
import net.drehtuer.podsilo.core.model.port.LogCategory
import net.drehtuer.podsilo.core.model.port.LogRepository
import net.drehtuer.podsilo.core.model.port.NewLogEntry
import java.time.Clock

private const val TAG = "FeedRefresher"
private const val HTTP_SERVER_ERROR_FLOOR = 500

/**
 * Refreshes followed feeds: conditional `GET` → parse → replace that feed's cached episodes
 * (`docs/architecture.md` §7's refresh sequence).
 *
 * **Refreshing never downloads.** This writes `Episode` rows, and — since `docs/decisions/0013` —
 * `SKIPPED` ledger rows for episodes older than the user's *mark old as played* cutoff, when they
 * have set one. It never writes `QUEUED`, never enqueues download work, and has no download or
 * GPodder dependency at all, which is what keeps CLAUDE.md §1's no-auto-download rule structural
 * rather than a matter of care. `FeedRefresherTest` and `NoAutoDownloadInvariantTest` both pin it.
 *
 * Separate from [FeedRefreshWorker] so the logic is a plain class: the worker adds only WorkManager's
 * retry contract on top.
 *
 * `@Suppress("LongParameterList")`: the same reasoning as `DownloadWorker`'s constructor — this is a
 * composition root, so its parameters *are* its dependency list, with no logic to simplify. The one
 * genuine cohesion in it (the mark-old rule's four dependencies) is already extracted into
 * [MarkOldEpisodesRule]; bundling the rest into a holder would move the list, not shorten it.
 */
@Suppress("LongParameterList")
class FeedRefresher(
    private val feedRepository: FeedRepository,
    private val episodeRepository: EpisodeRepository,
    private val feedFetcher: FeedFetcher,
    private val feedXmlParser: FeedXmlParser,
    private val clock: Clock,
    private val logRepository: LogRepository,
    private val markOldEpisodesRule: MarkOldEpisodesRule,
) {
    /**
     * Refreshes every followed feed, or just [feedUrl] when given — S2's pull-to-refresh is scoped
     * to the feed the user is looking at (`docs/UI.md` §5). One feed failing never stops the others.
     *
     * @return how many feeds failed *transiently* — i.e. how many are worth another attempt.
     */
    suspend fun refresh(feedUrl: String? = null): Int {
        val feeds =
            if (feedUrl == null) {
                feedRepository.getAll()
            } else {
                // A feed that vanished from the server between the tap and the work running is not an
                // error: the subscription list is the server's (CLAUDE.md §1), and the screen the user
                // pulled on will empty itself on the next sync.
                listOfNotNull(feedRepository.get(feedUrl))
            }
        val failures = feeds.count { feed -> !refreshOne(feed) }
        // Applied to whatever just arrived, once per pass — docs/decisions/0013.
        markOldEpisodesRule.apply()
        return failures
    }

    /** @return `false` only for a transient failure worth retrying. */
    private suspend fun refreshOne(feed: Feed): Boolean =
        when (val result = feedFetcher.fetch(feed.url, feed.httpEtag, feed.httpLastModified)) {
            // A 304 is a *successful* check: the feed was reached and is unchanged. It has to move
            // `lastRefreshedAt` or a feed that always 304s would show an ever-older "last refreshed"
            // on S1 despite being checked every pass — and S2's feed-error banner, which shows an
            // error newer than the last success, would never clear.
            is FeedFetchResult.NotModified -> {
                feedRepository.updateRefreshMetadata(
                    feedUrl = feed.url,
                    metadata =
                        FeedRefreshMetadata(
                            // Nothing was re-parsed, so everything but the timestamp is carried
                            // through unchanged — including the validators that produced the 304.
                            title = feed.title,
                            imageUrl = feed.imageUrl,
                            httpEtag = feed.httpEtag,
                            httpLastModified = feed.httpLastModified,
                            refreshedAt = clock.millis(),
                        ),
                )
                true
            }
            is FeedFetchResult.Fetched -> store(feed, result)
            // A 404/410 means the feed is gone; retrying achieves nothing, and the subscription list
            // is the server's business, not ours to prune (CLAUDE.md §1 — read-only follower).
            is FeedFetchResult.HttpError -> {
                Log.w(TAG, "feed ${feed.url} returned HTTP ${result.code} ${result.message}")
                logRepository.record(
                    NewLogEntry(
                        category = LogCategory.FEED,
                        feedUrl = feed.url,
                        message = "This podcast's feed could not be loaded (HTTP ${result.code}).",
                        detail = "HTTP ${result.code} ${result.message}",
                    ),
                )
                result.code < HTTP_SERVER_ERROR_FLOOR
            }
            is FeedFetchResult.NetworkError -> {
                Log.w(TAG, "feed ${feed.url} unreachable: ${result.reason}")
                logRepository.record(
                    NewLogEntry(
                        category = LogCategory.FEED,
                        feedUrl = feed.url,
                        message = "Feed server did not respond.",
                        detail = result.reason,
                    ),
                )
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
                logRepository.record(
                    NewLogEntry(
                        category = LogCategory.FEED,
                        feedUrl = feed.url,
                        message = "This podcast's feed could not be read. The previous episodes are still listed.",
                        detail = malformed::class.simpleName + ": " + malformed.message,
                    ),
                )
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
