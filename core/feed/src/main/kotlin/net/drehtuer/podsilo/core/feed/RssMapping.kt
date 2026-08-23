// SPDX-License-Identifier: GPL-3.0-or-later

package net.drehtuer.podsilo.core.feed

import com.prof18.rssparser.model.RssChannel
import com.prof18.rssparser.model.RssItem
import net.drehtuer.podsilo.core.model.Episode
import net.drehtuer.podsilo.core.model.episodeKey
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.time.format.DateTimeParseException

/** The feed-level fields parsing can actually produce -- `firstSeenAt`/`lastRefreshedAt`/HTTP
 * validators are not derivable from feed bytes and are the caller's (Tier 4's `FeedRefreshWorker`)
 * job to merge into a stored [net.drehtuer.podsilo.core.model.Feed].
 */
data class ParsedFeedMetadata(
    val title: String?,
    val imageUrl: String?,
)

data class ParsedFeed(
    val metadata: ParsedFeedMetadata,
    val episodes: List<Episode>,
)

private val RFC_822_DATE: DateTimeFormatter = DateTimeFormatter.RFC_1123_DATE_TIME

/**
 * Upgrades a cover-art URL from `http://` to `https://`.
 *
 * Android blocks cleartext at `targetSdk` 28+, and feeds still advertise artwork over `http://` —
 * the author's own `heute journal` does, which is why that podcast rendered a monogram instead of a
 * cover. Requesting the same path over TLS either works, or fails exactly as the blocked request
 * did and `PodsiloArtwork` falls back to the monogram it already draws. There is no case where this
 * is worse, which is what makes it preferable to a network-security config that would weaken every
 * request in the app.
 *
 * **Only artwork.** [Episode.enclosureUrl] is deliberately not touched anywhere: it is
 * `episodeKey`'s fallback when a feed omits `<guid>`, and it is the `episode` field of every action
 * posted to the shared GPodder log, so an upgraded one is a *different episode* to every other
 * client (`docs/architecture.adoc` §4/§6). A cleartext enclosure is reported instead, as
 * `ErrorCause.CLEARTEXT_BLOCKED`.
 */
internal fun String.artworkOverTls(): String =
    if (startsWith("http://", ignoreCase = true)) "https://" + substring("http://".length) else this

/**
 * Maps a parsed [RssChannel] to [ParsedFeed] for [feedUrl]. Items without an enclosure are
 * excluded -- they aren't downloadable and have no other purpose in this app (CLAUDE.md section 7).
 * A duplicate `episodeKey` across items keeps the first (feed) occurrence, since RSS items are
 * conventionally newest-first.
 */
fun RssChannel.toParsedFeed(feedUrl: String): ParsedFeed {
    val metadata =
        ParsedFeedMetadata(
            title = title?.trim()?.takeIf(String::isNotEmpty),
            imageUrl = (itunesChannelData?.image ?: image?.url)?.trim()?.takeIf(String::isNotEmpty)?.artworkOverTls(),
        )
    val episodes =
        items
            .mapNotNull { it.toEpisodeOrNull(feedUrl) }
            .distinctBy { it.episodeKey }
    return ParsedFeed(metadata, episodes)
}

private fun RssItem.toEpisodeOrNull(feedUrl: String): Episode? {
    val enclosureUrl = rawEnclosure?.url?.takeIf(String::isNotBlank) ?: return null
    return Episode(
        episodeKey = episodeKey(guid, enclosureUrl),
        feedUrl = feedUrl,
        guid = guid,
        enclosureUrl = enclosureUrl,
        title = title.orEmpty(),
        description = content?.takeIf(String::isNotBlank) ?: description,
        pubDate = parseRfc822Date(pubDate),
        durationMs = parseItunesDuration(itunesItemData?.duration),
        // The item's own page, for "Open in browser" (docs/UI.adoc section 6). Never synthesised from
        // the enclosure, which points at an audio file — a feed that omits it simply has no link.
        link = link?.trim()?.takeIf(String::isNotEmpty),
        // itunes:image first: it is the per-episode cover feeds actually use. A bare <image> on an
        // item is rare but legal, and costs nothing to accept.
        imageUrl = (itunesItemData?.image ?: image)?.trim()?.takeIf(String::isNotEmpty)?.artworkOverTls(),
        // Advisory, and only when positive: feeds write `length="0"` when they mean "no idea", and a
        // row reading "0 MB" is worse than one with no size at all.
        sizeBytes = rawEnclosure?.length?.takeIf { it > 0 },
    )
}

/**
 * Malformed or missing `pubDate` degrades to `null` -- `:core:naming`'s sortable placeholder
 * (`docs/architecture.adoc` §11) handles the rest. This covers the common RFC-822 form feeds use; it does
 * not attempt every date variant real-world feeds are known to produce.
 */
private fun parseRfc822Date(raw: String?): Long? {
    val trimmed = raw?.trim()
    if (trimmed.isNullOrEmpty()) return null
    return try {
        ZonedDateTime.parse(trimmed, RFC_822_DATE).toInstant().toEpochMilli()
    } catch (
        @Suppress("SwallowedException") malformed: DateTimeParseException,
    ) {
        null
    }
}
