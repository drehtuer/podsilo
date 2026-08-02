// SPDX-License-Identifier: GPL-3.0-or-later

package net.drehtuer.podsilo.core.model

/**
 * A disposable cache of one parsed RSS/Atom item (CLAUDE.md §5). The GPodder API has no episode
 * catalogue at all, so this table is the only source of episode data locally and is safe to wipe
 * and rebuild on every feed refresh — nothing here is durable state. Durable state (has this
 * episode been handled?) lives in [EpisodeLedgerRow], keyed by the same [episodeKey].
 *
 * @property episodeKey `guid ?: enclosureUrl` — see [episodeKey], not a free choice.
 * @property description Raw as received (CDATA and all); sanitised for display at render time,
 *   never at write time.
 * @property link The episode's own page (`<item><link>`, or Atom `<link rel="alternate">`) — what
 *   *Open in browser* opens (`docs/UI.md` §6). **Not** derivable from [enclosureUrl], which points
 *   at an audio file rather than a page, so a feed that omits it gets `null` and the UI omits the
 *   affordance rather than offering a dead tap.
 * @property pubDate Epoch millis, or null if the feed supplied nothing usable. Fallback chain and
 *   timezone normalisation are `:core:feed`'s and `:core:naming`'s job respectively, not modelled
 *   here.
 * @property durationMs `itunes:duration` is notoriously unreliable — never block logic on this
 *   being present.
 * @property imageUrl The item's own artwork (`<itunes:image href>`, or a plain `<image>` on the
 *   item) — the episode-specific cover a feed may supply per episode. `null` is the common case;
 *   the podcast's own [Feed.imageUrl] is the fallback when embedding artwork on download.
 */
data class Episode(
    val episodeKey: String,
    val feedUrl: String,
    val guid: String?,
    val enclosureUrl: String,
    val title: String,
    val description: String?,
    val pubDate: Long?,
    val durationMs: Long?,
    val link: String? = null,
    val imageUrl: String? = null,
)
