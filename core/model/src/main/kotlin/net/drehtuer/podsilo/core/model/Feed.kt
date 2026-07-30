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
 *   server's `add[]`. Never updated after that. Drives the backlog cutoff: the default "New"
 *   filter is `pubDate >= firstSeenAt` (CLAUDE.md §5's "backlog is a UI problem" section).
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
