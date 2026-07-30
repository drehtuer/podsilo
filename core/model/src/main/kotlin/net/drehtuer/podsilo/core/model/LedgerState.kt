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
}
