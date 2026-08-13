// SPDX-License-Identifier: GPL-3.0-or-later

package net.drehtuer.podsilo.core.model

/**
 * The only values [EpisodeLedgerRow.state] ever holds. There is deliberately no `NEW` value here
 * (CLAUDE.md §5/§9) — "new" is the absence of any ledger row for an episode key, not a stored
 * state. See `docs/architecture.md` §9 for the full transition diagram.
 *
 * [DOWNLOADED], [SKIPPED], and [HANDLED_REMOTELY] are terminal: once reached, sync reconciliation
 * never revisits them, even if a later remote action arrives for the same episode. That is the
 * idempotency the "triage durability" tests (CLAUDE.md §7 item 8) rely on.
 */
enum class LedgerState {
    QUEUED,
    DOWNLOADING,
    DOWNLOADED,
    SKIPPED,
    ERROR,
    HANDLED_REMOTELY,

    /**
     * *Undo a decision*: the user has said this episode is **not** handled after all, so it belongs
     * back in *To decide* (`docs/decisions/0024`).
     *
     * **This is why the ledger still needs no delete.** "Undecided" is normally the *absence* of a
     * row, and the obvious way to un-mark an episode is to remove one — which the project refused
     * three times, because that row is the only thing standing between the user and downloading a
     * file they already have (CLAUDE.md §11). A state instead of a deletion keeps `writtenFileName`,
     * `attempts` and the history intact while the list treats the episode as undecided again.
     *
     * Emitted as a `PLAY` with `position = 0` — the encoding every gpodder client already uses to say
     * *unread*, because the API can neither delete an action nor express the idea any other way
     * (`docs/decisions/0022`). Not terminal, and deliberately never reached by reconciliation: a
     * remote unread mark does not re-open a decision made on this device.
     */
    UNPLAYED,
}
