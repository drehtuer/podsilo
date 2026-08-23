// SPDX-License-Identifier: GPL-3.0-or-later

package net.drehtuer.podsilo.feature.episodes

import kotlinx.coroutines.flow.Flow

/**
 * What the download queue is doing **right now**, as the feature module sees it.
 *
 * A port for the same reason [EpisodeScheduler] is: `:feature:episodes` must not depend on
 * WorkManager, and `:app` owns it. The implementation reads `WorkInfo`s; nothing here knows that.
 *
 * This is the piece issue #47 was missing entirely. `DownloadWorker` reported bytes only to its
 * notification and never called `setProgress`, and no screen observed work at all — so every
 * `DOWNLOADING` row in S2, S3 and S7 drew the indeterminate *resuming* bar for the whole download,
 * and S1's per-feed "n downloading" was permanently zero because nothing ever assigned it.
 */
fun interface DownloadWorkMonitor {
    fun observe(): Flow<DownloadWork>
}

/**
 * A snapshot of the queue, keyed by `episodeKey`.
 *
 * The two fields answer different questions and `UI.adoc` §B7's table needs both: [live]
 * is "is there work for this episode at all", [progress] is "and has it told us how far along it is
 * *in this process*". A `DOWNLOADING` ledger row that is in [live] but absent from [progress] reads
 * *resuming*; one in neither is stranded — the process died before the worker could resume — and the
 * view model re-enqueues it on first observation.
 *
 * @property progress **Never persisted and never reconstructed.** After process death WorkManager's
 *   progress is gone, and this map is correspondingly empty, which is precisely why a stale
 *   percentage cannot be drawn.
 */
data class DownloadWork(
    val progress: Map<String, DownloadProgress> = emptyMap(),
    val live: Set<String> = emptySet(),
)
