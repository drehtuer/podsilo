// SPDX-License-Identifier: GPL-3.0-or-later

package net.drehtuer.podsilo.feature.episodes

import net.drehtuer.podsilo.core.model.EpochTime
import net.drehtuer.podsilo.core.model.LedgerState
import net.drehtuer.podsilo.core.model.port.EpisodeListItem
import java.time.Duration
import java.time.Instant

/**
 * One row of the triage list (`docs/UI_interface.md` §1).
 *
 * **[actions] is computed here, once**, rather than derived by a `when (state)` inside each
 * Composable — the row body, the overflow menu, the swipe label and the accessibility custom
 * actions all read this one set, so they cannot disagree about what an episode currently offers
 * (`docs/UI.md` §12.6).
 *
 * @property ledgerState `null` **is** "to decide". There is no `NEW` in `LedgerState`: new means the
 *   absence of a ledger row (CLAUDE.md §9), and modelling it as a null here keeps that true all the
 *   way to the screen.
 * @property progress non-null **only** while this process has seen a progress update. A percentage
 *   is never reconstructed from a stale `DOWNLOADING` row after process death — see §7 of the seam
 *   document; a row with no live progress reads *resuming*, not "0 %".
 */
data class EpisodeUi(
    val episodeKey: String,
    val feedUrl: String,
    val feedTitle: String,
    val title: String,
    val artworkUrl: String?,
    val publishedAt: Instant?,
    val duration: Duration?,
    /** `<enclosure length>` in bytes, when the feed gave one. Advisory — see `Episode.sizeBytes`. */
    val sizeBytes: Long? = null,
    val descriptionSnippet: String,
    val ledgerState: LedgerState?,
    val progress: DownloadProgress? = null,
    val writtenFileName: String? = null,
    /** The typed failure, so a row can tell *Retry* from *Choose folder* (`docs/decisions/0011`). */
    val lastError: FailureUi? = null,
    val hasEnclosure: Boolean = true,
    val episodePageUrl: String? = null,
) {
    val actions: Set<EpisodeUiAction> = actionsFor(ledgerState, hasEnclosure, episodePageUrl != null)

    /** Terminal rows render de-emphasised but stay fully interactive (`docs/UI.md` §5). */
    val isDeEmphasised: Boolean
        get() = ledgerState in TERMINAL_STATES
}

/** Never reconstructed from a ledger row — only ever from a live update (`docs/UI_interface.md` §7). */
data class DownloadProgress(
    val bytesDownloaded: Long,
    val totalBytes: Long?,
) {
    val percent: Int?
        get() = totalBytes?.takeIf { it > 0 }?.let { ((bytesDownloaded * PERCENT) / it).toInt() }

    private companion object {
        const val PERCENT = 100L
    }
}

/**
 * What a row may currently do.
 *
 * Not `EpisodeAction` — that name is taken in `:core:model` by the GPodder wire type. Two different
 * things called `EpisodeAction` in one dependency graph is a mix-up waiting to happen.
 */
enum class EpisodeUiAction {
    DOWNLOAD,
    DOWNLOAD_AGAIN,
    MARK_AS_PLAYED,
    RETRY,
    CANCEL,
    OPEN_IN_BROWSER,
    COPY_LINK,
}

private val TERMINAL_STATES = setOf(LedgerState.DOWNLOADED, LedgerState.SKIPPED, LedgerState.HANDLED_REMOTELY)

/**
 * The single source of "what can this row do", per `docs/UI.md` §12.6's table.
 *
 * An episode with no enclosure is not downloadable at all — the affordance is **absent**, not
 * present-and-failing, because a Download button that always errors is worse than none
 * (`docs/UI_interface.md` §14.3).
 */
internal fun actionsFor(
    state: LedgerState?,
    hasEnclosure: Boolean,
    hasPage: Boolean,
): Set<EpisodeUiAction> {
    val browse =
        buildSet {
            if (hasPage) {
                add(EpisodeUiAction.OPEN_IN_BROWSER)
                add(EpisodeUiAction.COPY_LINK)
            }
        }
    if (!hasEnclosure) return browse

    val triage =
        when (state) {
            null -> setOf(EpisodeUiAction.DOWNLOAD, EpisodeUiAction.MARK_AS_PLAYED)
            LedgerState.QUEUED, LedgerState.DOWNLOADING -> setOf(EpisodeUiAction.CANCEL)
            LedgerState.DOWNLOADED -> setOf(EpisodeUiAction.DOWNLOAD_AGAIN, EpisodeUiAction.MARK_AS_PLAYED)
            // "Download anyway": the user may override a decision made here or on another client.
            LedgerState.SKIPPED, LedgerState.HANDLED_REMOTELY -> setOf(EpisodeUiAction.DOWNLOAD)
            LedgerState.ERROR -> setOf(EpisodeUiAction.RETRY, EpisodeUiAction.MARK_AS_PLAYED)
        }
    return triage + browse
}

/**
 * Projects a stored [EpisodeListItem] into its row, applying `docs/UI_interface.md` §7's table.
 *
 * [work] is supplied by the ViewModel from WorkManager, deliberately as a parameter rather than read
 * from the ledger: **the ledger knows an episode is `DOWNLOADING`, but only this process knows how
 * far along it is.** The three cases §7 enumerates all resolve here, in one place, so S2, S3 and S7
 * cannot each answer them slightly differently:
 *
 * | Ledger row | Live work | Live update | Renders as |
 * |---|---|---|---|
 * | `DOWNLOADING` | yes | yes | determinate bar, `%` and bytes |
 * | `DOWNLOADING` | yes | no | indeterminate, *resuming* |
 * | `DOWNLOADING` | no | — | ***queued*** — see [isStranded] |
 *
 * The last row is the one worth naming: a `DOWNLOADING` ledger row with no work behind it is a
 * download the process died in the middle of. Rendering it as *downloading* would claim something
 * is happening that is not, so it renders as queued and the ViewModel re-enqueues it.
 */
fun EpisodeListItem.toUi(
    feedTitle: String,
    feedArtworkUrl: String? = null,
    work: DownloadWork = DownloadWork(),
): EpisodeUi =
    EpisodeUi(
        episodeKey = episode.episodeKey,
        feedUrl = episode.feedUrl,
        feedTitle = feedTitle,
        title = episode.title,
        // "episode image if the feed supplies one, else the feed's" (docs/UI.md §5). `Episode.imageUrl`
        // has existed since schema v4 and was ignored here — every row showed the podcast's cover
        // even for the 9,558-of-9,565 episodes in the author's own feeds that carry their own.
        artworkUrl = episode.imageUrl ?: feedArtworkUrl,
        publishedAt = EpochTime.ofMillisOrNull(episode.pubDate),
        duration = EpochTime.durationOfMillis(episode.durationMs),
        sizeBytes = episode.sizeBytes,
        // Stripped here, not at write time: the raw HTML stays in the database so the detail sheet
        // can render it properly (architecture §4). This is only the two-line list preview.
        descriptionSnippet = sanitizeEpisodeHtml(episode.description).text.replace('\n', ' ').trim(),
        // Presentation only — nothing rewrites the ledger row, which still says DOWNLOADING and is
        // still the durable record. This is what the *user* is told is happening.
        ledgerState = if (work.isStranded(this)) LedgerState.QUEUED else ledger?.state,
        progress = work.progress[episode.episodeKey],
        writtenFileName = ledger?.writtenFileName,
        lastError = ledger?.toFailureUi(),
        hasEnclosure = episode.enclosureUrl.isNotBlank(),
        episodePageUrl = episode.link,
    )

/**
 * How a row looks while its swipe decision is still inside the undo window
 * (`docs/decisions/0021`).
 *
 * **Presentation only.** No ledger row exists yet, nothing has been posted, and nothing is queued;
 * this is the app showing the user that their gesture registered. If the window is undone the row
 * simply reverts, because there was never anything to revert *in storage*.
 *
 * The state shown is the one the decision will produce, so the row does not change appearance a
 * second time when the write finally lands.
 */
internal fun EpisodeUi.asPending(pending: PendingUndo): EpisodeUi =
    copy(
        ledgerState =
            when (pending.action) {
                EpisodeUiAction.MARK_AS_PLAYED -> LedgerState.SKIPPED
                else -> LedgerState.QUEUED
            },
    )

/**
 * A `DOWNLOADING` ledger row with **no work behind it at all** — killed before WorkManager could
 * resume it (`docs/UI_interface.md` §7's third case).
 *
 * Distinct from "downloading but has not reported yet", which is live work and reads *resuming*. The
 * distinction matters because only this case needs re-enqueueing, and re-enqueueing a download that
 * is already running would be a second worker for one file.
 */
fun DownloadWork.isStranded(item: EpisodeListItem): Boolean =
    item.ledger?.state == LedgerState.DOWNLOADING && item.episode.episodeKey !in live

/** The keys [isStranded] identifies, for the ViewModel that has to re-enqueue them. */
fun DownloadWork.strandedIn(items: List<EpisodeListItem>): List<String> =
    items.filter { isStranded(it) }.map { it.episode.episodeKey }
