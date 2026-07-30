// SPDX-License-Identifier: GPL-3.0-or-later

package net.drehtuer.podsilo.core.model

/**
 * "The one table that must never be lost" (CLAUDE.md §5) — the durable record of what has already
 * been handled for an episode, keyed by [episodeKey] and outliving the disposable [Episode] cache
 * row it was created from (a feed disappearing from the server deletes its [Episode] rows but
 * never its [EpisodeLedgerRow]s, see `docs/architecture.md` §5's subscription-mirroring rule).
 *
 * [feedUrl], [enclosureUrl], and [durationSeconds] are denormalised here (not looked up via
 * [Episode] at push time) so that a POST retry after a feed is unsubscribed can still build a
 * valid, accurate outbound [port.EpisodeAction] — see `docs/decisions/` for why: the documented
 * sync order pulls subscriptions (pruning [Episode] rows for removed feeds) *before* draining the
 * outbox, so an [Episode] row is not guaranteed to still exist when an unsynced ledger row is
 * finally pushed.
 *
 * @property state See [LedgerState] — there is no persisted "NEW" value.
 * @property actionedAt Epoch millis. For rows created from a remote action, parsed from that
 *   action's ISO-8601 `timestamp` (see the two-timestamp-format gotcha in CLAUDE.md §11).
 * @property syncedToServer The outbox flag: `false` on every local write, flipped to `true` only
 *   on a confirmed 2xx POST. `getUnsynced()` is `WHERE syncedToServer = 0`.
 * @property writtenFileName Retry idempotency **only** — never a file-existence check (CLAUDE.md
 *   §11's single most important invariant).
 * @property durationSeconds Snapshot of `Episode.durationMs` (converted to seconds) at the moment
 *   this row was written, used only to encode a skip's `PLAY` action `total`/`position`. `null`
 *   when the feed never supplied a usable duration — CLAUDE.md §5: send `PLAY` anyway, never
 *   invent a plausible-looking duration.
 */
data class EpisodeLedgerRow(
    val episodeKey: String,
    val feedUrl: String,
    val enclosureUrl: String,
    val state: LedgerState,
    val actionedAt: Long,
    val syncedToServer: Boolean,
    val attempts: Int,
    val lastError: String?,
    val writtenFileName: String?,
    val durationSeconds: Int? = null,
) {
    /** Derived, not stored: present only when [episodeKey] isn't just the enclosure URL. */
    val guid: String? get() = episodeKey.takeIf { it != enclosureUrl }
}
