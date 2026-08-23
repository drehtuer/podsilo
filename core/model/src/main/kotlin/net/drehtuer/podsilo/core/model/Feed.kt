// SPDX-License-Identifier: GPL-3.0-or-later

package net.drehtuer.podsilo.core.model

/**
 * A read-only mirror of one entry in the server's subscription list (CLAUDE.md §1/§5). Podsilo
 * never creates, edits, or removes a [Feed] locally in response to user action — the whole table
 * is wholesale-replaced from [port.GpodderClient.fetchSubscriptions] each sync pass.
 *
 * @property url Stable identity, also the value written into an outbound
 *   [port.EpisodeAction.podcast].
 * @property title Only known after the first successful feed fetch; the URL is a reasonable
 *   placeholder until then, since the GPodder API itself carries no feed titles.
 * @property firstSeenAt Epoch millis, local clock, set once when [url] first appears in the
 *   server's `add[]`. Never updated after that.
 *
 *   **No longer a query predicate.** It used to drive a read-time `pubDate >= firstSeenAt` cutoff on
 *   the "New" filter; `decisions/0013` retired that in favour of *writing* `SKIPPED` rows, so
 *   "new" now means exactly "no ledger row". This is kept because it is the only sensible default
 *   cutoff date to offer for a feed that has just appeared.
 * @property lastRefreshedAt Epoch millis, local clock, updated after a successful (200, not 304)
 *   feed fetch.
 * @property httpEtag Last-seen response `ETag`, for conditional `GET`.
 * @property httpLastModified Last-seen response `Last-Modified`, for conditional `GET`.
 */
data class Feed(
    val url: String,
    val title: String,
    val imageUrl: String?,
    val firstSeenAt: Long,
    val lastRefreshedAt: Long?,
    val httpEtag: String?,
    val httpLastModified: String?,
)
