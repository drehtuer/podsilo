// SPDX-License-Identifier: GPL-3.0-or-later

package net.drehtuer.podsilo.feature.episodes

import kotlinx.coroutines.flow.Flow
import net.drehtuer.podsilo.core.model.EpisodeLedgerRow
import net.drehtuer.podsilo.core.model.ErrorCause

/**
 * A failure as a row renders it (`docs/UI.md` §B1).
 *
 * @property message passed through **verbatim** from whatever produced it, per the seam's rule that
 *   a server-supplied string is the one thing the view model does not re-word.
 * @property retryable whether another attempt could plausibly work. This is the field
 *   `docs/UI.md` §12.11 and `docs/architecture.md` §11 hang a real guarantee on: a lost folder grant must
 *   offer **Choose folder** and never a bare **Retry**, because retrying cannot succeed until the
 *   user acts. Historical rows written before the classification existed have no verdict, and
 *   default to retryable — offering a Retry that fails is recoverable; hiding the only useful button
 *   is not.
 */
data class FailureUi(
    val cause: ErrorCause,
    val message: String,
    val attempts: Int,
    val retryable: Boolean,
) {
    /**
     * What the row offers instead of *Retry* when retrying is pointless. `null` means an ordinary
     * *Retry* is the right affordance.
     */
    val remedy: FailureRemedy?
        get() =
            when (cause) {
                ErrorCause.FOLDER_UNAVAILABLE -> FailureRemedy.CHOOSE_FOLDER
                ErrorCause.DISK_FULL -> FailureRemedy.FREE_UP_SPACE
                else -> null
            }
}

/** The user action that can actually clear a failure the app cannot clear by trying again. */
enum class FailureRemedy { CHOOSE_FOLDER, FREE_UP_SPACE }

/** `null` when the row has no recorded failure. */
internal fun EpisodeLedgerRow.toFailureUi(): FailureUi? {
    val message = lastError ?: return null
    return FailureUi(
        cause = lastErrorCause ?: ErrorCause.UNKNOWN,
        message = message,
        attempts = attempts,
        retryable = lastErrorRetryable ?: true,
    )
}

/**
 * Folder-missing, permission-revoked and disk-full are three causes of **one** user-visible
 * condition (`docs/UI.md` §12.11). It is a queue-level state, not a per-episode one: existing
 * `QUEUED` rows stay queued and new requests are still accepted, because the app never refuses a
 * decision over a fixable configuration problem.
 */
sealed interface QueueStatus {
    data object Running : QueueStatus

    data class Paused(
        val cause: PauseCause,
        val queuedCount: Int,
    ) : QueueStatus

    enum class PauseCause { FOLDER_NOT_CHOSEN, FOLDER_REVOKED, DISK_FULL }
}

/**
 * Whether the download folder is usable, as the feature module sees it.
 *
 * A port for the same reason [EpisodeScheduler] and [DownloadSpaceProbe] are: `:feature:episodes`
 * must not depend on `:core:download`, which owns `DownloadFolderAccess`.
 */
fun interface DownloadFolderStatus {
    fun observe(): Flow<FolderState>
}

/** Mirrors `DownloadFolderAccess`'s three states without dragging `:core:download` into the UI. */
enum class FolderState { NOT_CHOSEN, GRANTED, REVOKED }

/**
 * Derives the one paused condition from the folder grant and the rows on screen.
 *
 * Disk-full is inferred from a row that actually failed that way rather than by probing free space:
 * a volume can be nearly full and still fit the next episode, so the honest trigger is "a download
 * already failed for space", not "space looks tight".
 */
fun queueStatusFor(
    folder: FolderState,
    rows: List<EpisodeUi>,
): QueueStatus {
    val queued = rows.count { it.ledgerState == net.drehtuer.podsilo.core.model.LedgerState.QUEUED }
    return when {
        folder == FolderState.NOT_CHOSEN -> QueueStatus.Paused(QueueStatus.PauseCause.FOLDER_NOT_CHOSEN, queued)
        folder == FolderState.REVOKED -> QueueStatus.Paused(QueueStatus.PauseCause.FOLDER_REVOKED, queued)
        rows.any { it.lastError?.cause == ErrorCause.DISK_FULL } ->
            QueueStatus.Paused(QueueStatus.PauseCause.DISK_FULL, queued)
        else -> QueueStatus.Running
    }
}
