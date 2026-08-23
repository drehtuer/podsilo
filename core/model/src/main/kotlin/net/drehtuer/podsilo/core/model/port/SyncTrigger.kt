// SPDX-License-Identifier: GPL-3.0-or-later

package net.drehtuer.podsilo.core.model.port

/**
 * How anything that has durably written an outbox row asks for it to be pushed.
 *
 * Callers: [DownloadWorker] after a download lands, the triage writer after a *mark as played*, S4's
 * bulk mark, and the mark-old rule inside a feed refresh. Each of those writes a row with
 * `syncedToServer = false` and then asks for a pass; none of them posts anything itself.
 *
 * `docs/architecture.adoc` §10/§12 (decision #4) settles the "who POSTs the episode action" question
 * in favour of *write the ledger, then trigger a sync pass* rather than having the download path
 * call `GpodderClient` itself: exactly one piece of code posts episode actions, and `:core:download`
 * keeps no knowledge of the GPodder API at all.
 *
 * Implemented in `:app` (where `SyncWorker` and WorkManager scheduling live) — a port here rather
 * than a direct enqueue because no other module can see `:app`'s worker class, and because a port in
 * `:core:model` is the one place every module can already reach (`docs/architecture.adoc` §2).
 *
 * **Fire-and-forget on purpose.** A screen that needs to know when the pass *finished* — S1 and S2's
 * pull-to-refresh, which holds an indicator for the duration — asks through
 * `EpisodeScheduler.syncAndAwait()` instead. Same work, two contracts: a writer must never block on
 * the network to record a decision.
 */
fun interface SyncTrigger {
    /** Best-effort: a dropped or delayed trigger costs a later sync pass, never a lost ledger row. */
    fun requestSyncNow()
}
