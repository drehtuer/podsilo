// SPDX-License-Identifier: GPL-3.0-or-later

package net.drehtuer.podsilo.core.download

/**
 * How [DownloadWorker] asks for the outbox to be drained after it has durably recorded a download.
 *
 * `docs/architecture.md` §10/§12 (decision #4) settles the "who POSTs the episode action" question
 * in favour of *write the ledger, then trigger a sync pass* rather than having the download path
 * call `GpodderClient` itself: exactly one piece of code posts episode actions, and `:core:download`
 * keeps no knowledge of the GPodder API at all.
 *
 * Implemented in `:app` (where `SyncWorker` and WorkManager scheduling live) — an interface here
 * rather than a direct enqueue because `:core:download` cannot see `:app`'s worker class.
 */
fun interface SyncTrigger {
    /** Best-effort: a dropped or delayed trigger costs a later sync pass, never a lost ledger row. */
    fun requestSyncNow()
}
