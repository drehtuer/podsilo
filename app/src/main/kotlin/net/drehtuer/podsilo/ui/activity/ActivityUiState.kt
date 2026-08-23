// SPDX-License-Identifier: GPL-3.0-or-later

package net.drehtuer.podsilo.ui.activity

import net.drehtuer.podsilo.core.model.LedgerState
import net.drehtuer.podsilo.feature.episodes.EpisodeUi
import net.drehtuer.podsilo.feature.episodes.QueueStatus
import java.time.Instant

/**
 * S7 — activity (`docs/UI.md` §B6). The one place that answers *what is the app doing, and
 * what is stuck?*
 *
 * @property recent the last ~20 delivered files. It exists to answer "did it actually land?" and
 *   nothing else — there is **no** delete, no open-file and no existence check. Podsilo is not a
 *   file manager (README), and file presence must never drive logic (CLAUDE.md §11).
 */
data class ActivityUiState(
    val queueStatus: QueueStatus = QueueStatus.Running,
    val sync: SyncUi = SyncUi(),
    val downloading: List<EpisodeUi> = emptyList(),
    val queued: List<QueuedUi> = emptyList(),
    val failed: List<EpisodeUi> = emptyList(),
    val recent: List<DeliveredUi> = emptyList(),
    /**
     * The last few decisions, newest first (issue #90) — *what did I just do, and can I take it
     * back?* Distinct from [recent], which answers "did the file land?" and renders a filename.
     */
    val history: List<ActionUi> = emptyList(),
) {
    val isIdle: Boolean
        get() =
            downloading.isEmpty() &&
                queued.isEmpty() &&
                failed.isEmpty() &&
                recent.isEmpty() &&
                history.isEmpty()
}

/**
 * One decision in S7's *recent actions* group (issue #90).
 *
 * @property canMarkAsUnplayed a decision that can be withdrawn. `UNPLAYED` is already withdrawn, so
 *   it renders as history without an action rather than offering a no-op.
 */
data class ActionUi(
    val episodeKey: String,
    val feedUrl: String,
    val episodeTitle: String,
    val feedTitle: String,
    val state: LedgerState,
    val actionedAt: Instant,
    val canMarkAsUnplayed: Boolean,
)

/**
 * @property canSyncNow `false` with [blockedReason] set rather than a button that fails — an offline
 *   tap should say so instead of timing out (`docs/UI.md` §12.10).
 */
data class SyncUi(
    val lastSyncAt: Instant? = null,
    val outboxDepth: Int = 0,
    val canSyncNow: Boolean = true,
    val blockedReason: BlockedReason? = null,
)

enum class BlockedReason { OFFLINE, NOT_CONFIGURED }

/** The reason a queued episode has not started, so the row is never a silent nothing. */
data class QueuedUi(
    val episode: EpisodeUi,
    val reason: WaitReason,
)

enum class WaitReason { WIFI, NETWORK, FOLDER, RESUMING }

/** [fileName] and [folderLabel] are what we *wrote*, never a look at the folder. */
data class DeliveredUi(
    val fileName: String,
    val folderLabel: String?,
    val episodeKey: String,
    val feedUrl: String,
)

sealed interface ActivityEvent {
    data object SyncNowClicked : ActivityEvent

    data class CancelClicked(
        val episodeKey: String,
    ) : ActivityEvent

    data class RetryClicked(
        val episodeKey: String,
    ) : ActivityEvent

    data class MarkAsPlayedClicked(
        val episodeKey: String,
    ) : ActivityEvent

    data class DetailsClicked(
        val episodeKey: String,
    ) : ActivityEvent

    /**
     * Opens **that episode**, not its podcast.
     *
     * It navigated to S2 (the feed's whole episode list) and left the user to find the row again —
     * which reads as being bounced back to the podcast rather than opening what was tapped. A row in
     * Activity names one episode; tapping it should show that episode.
     */
    data class RowClicked(
        val feedUrl: String,
        val episodeKey: String,
    ) : ActivityEvent

    data object PausedBannerActionClicked : ActivityEvent

    /** Empties the *delivered* list. A display cursor — no file and no ledger row is touched. */
    data object ClearDeliveredClicked : ActivityEvent

    /**
     * Withdraws a decision from the *recent actions* group (issue #90).
     *
     * A new `UNPLAYED` row, never a deleted one (`docs/decisions/0024`): the row is the dedup
     * authority and has to outlive the decision it records.
     */
    data class MarkAsUnplayedClicked(
        val episodeKey: String,
    ) : ActivityEvent

    data object ErrorLogClicked : ActivityEvent
}

sealed interface ActivityEffect {
    data class OpenEpisodeDetail(
        val episodeKey: String,
    ) : ActivityEffect

    data object OpenErrorLog : ActivityEffect

    data object ChooseFolder : ActivityEffect

    data class ShowMessage(
        val text: String,
    ) : ActivityEffect
}
