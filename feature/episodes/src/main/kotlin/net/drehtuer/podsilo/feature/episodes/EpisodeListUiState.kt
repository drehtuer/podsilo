// SPDX-License-Identifier: GPL-3.0-or-later

package net.drehtuer.podsilo.feature.episodes

import net.drehtuer.podsilo.core.model.ErrorCause
import net.drehtuer.podsilo.core.model.port.LedgerFilterState
import net.drehtuer.podsilo.core.model.port.SwipeAction
import net.drehtuer.podsilo.core.model.port.SwipeDirection
import net.drehtuer.podsilo.core.model.port.SwipeMapping

/**
 * S2's whole state (`UI.adoc` §B3).
 *
 * One non-null state type with **sealed content variants** — never an `isLoading` flag beside a
 * nullable payload, which is the shape that lets a screen render "empty" and "not loaded yet"
 * identically and then argue about which it meant.
 *
 * @property feedError renders as an inline banner *above* the list, never in place of it: a failed
 *   refresh must leave the previously parsed episodes on screen (`UI.adoc` §5).
 * @property swipeMapping the swipe background's icon and word are rendered **from** this, so the UI
 *   cannot advertise one verb and perform another.
 */
data class EpisodeListUiState(
    val feedUrl: String,
    val feedTitle: String,
    val filter: EpisodeFilter = EpisodeFilter.TO_DECIDE,
    val content: Content = Content.Loading,
    /** Sticky headers, indexed into [content]'s list so the two cannot drift. */
    val sections: List<MonthSection> = emptyList(),
    val queueStatus: QueueStatus = QueueStatus.Running,
    val selection: Selection? = null,
    val isRefreshing: Boolean = false,
    val isOffline: Boolean = false,
    val feedError: String? = null,
    val swipeMapping: SwipeMapping = SwipeMapping(),
    val downloadAllCount: Int = 0,
    /** Non-null while the *Download all* confirmation is up. Nothing is written until it is confirmed. */
    val pendingBulk: BulkPreview? = null,
    /**
     * Non-null while *Mark all as played* is being confirmed.
     *
     * A separate field from [pendingBulk] on purpose: the two dialogs say different things and one
     * of them writes `PLAY` actions to a shared log that no undo reaches (`decisions/0013`).
     * Sharing a field would make it possible to render the download wording over a mark-as-played
     * confirmation.
     */
    val pendingMarkAll: List<String>? = null,
    /**
     * Non-null while a **selection-mode** action is being confirmed (`UI.adoc` §5).
     *
     * Its own field, like [pendingBulk] and [pendingMarkAll], and for the same reason: the three
     * dialogs say different things, and one of them writes `PLAY` actions to a shared log no undo
     * reaches. Sharing a field would make it possible to render one confirmation's wording over
     * another's action.
     */
    val pendingSelectionAction: EpisodeUiAction? = null,
    /**
     * A swipe decision inside its undo window — **not yet written anywhere** (`UI.adoc` §12.3).
     *
     * The row renders as though the decision had been made, because a swipe that appeared to do
     * nothing for five seconds would read as the app ignoring it. The ledger, the outbox and the
     * server know nothing about it until the window elapses.
     */
    val pendingUndo: PendingUndo? = null,
) {
    sealed interface Content {
        data object Loading : Content

        data class Empty(
            val filter: EpisodeFilter,
        ) : Content

        data class Episodes(
            val items: List<EpisodeUi>,
        ) : Content
    }

    val inSelectionMode: Boolean get() = selection != null
}

/** Non-null only in selection mode; [allInFilter] backs *Select all*, which is scoped to the filter. */
data class Selection(
    val keys: Set<String>,
    val allInFilter: Int,
)

/**
 * A swipe decision being held for its undo window (`UI.adoc` §12.3).
 *
 * Exactly one at a time: a second swipe commits this one first. Two live undo windows would need two
 * snackbars and an answer to "which does *Undo* mean", and neither is worth having.
 */
data class PendingUndo(
    val episodeKey: String,
    val action: EpisodeUiAction,
)

/**
 * The four chips of `UI.adoc` §5. `TO_DECIDE` is the default and the primary working surface.
 *
 * Maps to [LedgerFilterState] rather than being it: the UI vocabulary ("Played / handled" covers
 * both a local skip and another client's decision) is not the storage vocabulary.
 */
enum class EpisodeFilter(
    val ledgerState: LedgerFilterState,
) {
    TO_DECIDE(LedgerFilterState.NEW),
    DOWNLOADED(LedgerFilterState.DOWNLOADED),
    PLAYED_OR_HANDLED(LedgerFilterState.SKIPPED),
    ALL(LedgerFilterState.ALL),
}

/** Everything S2 can emit upward. Composables never call a repository or `WorkManager` themselves. */
sealed interface EpisodeListEvent {
    /** Opens S3. **Never triages** — a mis-tap must not queue a download (`UI.adoc` §5). */
    data class RowClicked(
        val episodeKey: String,
    ) : EpisodeListEvent

    /** Up navigation. Back from S2 returns to S1 with its scroll position intact (`UI.adoc` §3). */
    data object BackClicked : EpisodeListEvent

    /** S2's app bar → S7, one of the two routes into Activity the navigation map draws. */
    data object ActivityClicked : EpisodeListEvent

    data class Triage(
        val episodeKey: String,
        val action: EpisodeUiAction,
    ) : EpisodeListEvent

    data class SwipeCommitted(
        val episodeKey: String,
        val direction: SwipeDirection,
    ) : EpisodeListEvent

    data class FilterChanged(
        val filter: EpisodeFilter,
    ) : EpisodeListEvent

    data class SelectionToggled(
        val episodeKey: String,
    ) : EpisodeListEvent

    data class SelectionStarted(
        val episodeKey: String,
    ) : EpisodeListEvent

    data object SelectionCleared : EpisodeListEvent

    data object SelectAllInFilter : EpisodeListEvent

    /**
     * Opens the confirmation for a selection-mode action. **Writes nothing** — only
     * [BulkConfirmed] does, which is the same "name the count before you write" rule
     * *Download all* and *Mark all as played* already follow (`UI.adoc` §5).
     */
    data class SelectionActionRequested(
        val action: EpisodeUiAction,
    ) : EpisodeListEvent

    data object SelectionActionDismissed : EpisodeListEvent

    data class BulkConfirmed(
        val action: EpisodeUiAction,
        val keys: Set<String>,
    ) : EpisodeListEvent

    /** Opens the confirmation for marking every episode in the current filter as played. */
    data object MarkAllRequested : EpisodeListEvent

    data object MarkAllConfirmed : EpisodeListEvent

    data object MarkAllDismissed : EpisodeListEvent

    data object DownloadAllRequested : EpisodeListEvent

    data class DownloadAllConfirmed(
        val keys: List<String>,
    ) : EpisodeListEvent

    /** Dismissing the confirmation writes nothing — the whole point of the dialog. */
    data object DownloadAllDismissed : EpisodeListEvent

    /** *Undo* on the swipe snackbar. Discards the pending decision; nothing was ever written. */
    data object UndoRequested : EpisodeListEvent

    data object PullToRefresh : EpisodeListEvent

    /** The banner always carries its fix as a button (`UI.adoc` §12.11). */
    data object PausedBannerActionClicked : EpisodeListEvent

    /** *Try again* on the feed-error banner. Same refresh as the pull gesture, different affordance. */
    data object RetryFeedClicked : EpisodeListEvent
}

/**
 * One-shot effects, delivered over a `Channel` rather than held in state so a snackbar or a
 * navigation cannot replay on rotation (`UI.adoc` §B0.7).
 */
sealed interface EpisodeListEffect {
    data class OpenDetail(
        val episodeKey: String,
    ) : EpisodeListEffect

    /** Pops back to S1. An effect rather than a screen-local `popBackStack`: the screen owns no navigation. */
    data object NavigateUp : EpisodeListEffect

    data object OpenActivity : EpisodeListEffect

    data class OpenUrl(
        val url: String,
    ) : EpisodeListEffect

    /**
     * *Copy episode link*. Its own effect because copying is not opening — both actions existed and
     * both emitted `OpenUrl`, so "copy" launched a browser and `SnackbarText.LinkCopied` had no
     * producer at all. Found when the row overflow gave the action its first call site.
     */
    data class CopyLink(
        val url: String,
    ) : EpisodeListEffect

    data class ShowMessage(
        val text: SnackbarText,
    ) : EpisodeListEffect

    /**
     * The snackbar that carries *Undo* (`UI.adoc` §12.3).
     *
     * Its own effect rather than a [ShowMessage] variant because it needs an action button and a
     * reply — the host turns a tap into [EpisodeListEvent.UndoRequested]. The **view model** owns the
     * window, not the snackbar's own duration: an undo that arrives after the write must be ignored,
     * and only one of the two can be the authority on when that is.
     */
    data class ShowUndo(
        val action: EpisodeUiAction,
    ) : EpisodeListEffect

    /** Ask the host to fix whatever is holding the queue — in practice, the folder picker. */
    data object ResolvePausedQueue : EpisodeListEffect
}

/**
 * A snackbar's *identity*, not its text — the string is resolved at render, so nothing here holds a
 * user-facing sentence (`UI.adoc` §B0.6).
 */
sealed interface SnackbarText {
    data class Queued(
        val count: Int,
    ) : SnackbarText

    data class BulkApplied(
        val count: Int,
    ) : SnackbarText

    /** The inverse of [BulkApplied] — a decision withdrawn (`decisions/0024`). */
    data class MarkedUnplayed(
        val count: Int,
    ) : SnackbarText

    /** Informational, **not** an error: the file was already there (`decisions/0012` §4). */
    data class AlreadyInFolder(
        val fileName: String,
    ) : SnackbarText

    data class DownloadFailed(
        val cause: ErrorCause,
    ) : SnackbarText

    data object LinkCopied : SnackbarText

    data object Offline : SnackbarText

    data object AlreadyUpToDate : SnackbarText
}

/** The action a swipe in [direction] performs, per the user's mapping. `NONE` means the swipe is disabled. */
internal fun SwipeMapping.triageFor(direction: SwipeDirection): EpisodeUiAction? =
    when (actionFor(direction)) {
        SwipeAction.DOWNLOAD -> EpisodeUiAction.DOWNLOAD
        SwipeAction.MARK_AS_PLAYED -> EpisodeUiAction.MARK_AS_PLAYED
        SwipeAction.NONE -> null
    }
