// SPDX-License-Identifier: GPL-3.0-or-later

package net.drehtuer.podsilo.feature.episodes

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import net.drehtuer.podsilo.core.model.Episode
import net.drehtuer.podsilo.core.model.LedgerState
import net.drehtuer.podsilo.core.model.port.EpisodeLedgerRepository
import net.drehtuer.podsilo.core.model.port.EpisodeListItem
import net.drehtuer.podsilo.core.model.port.EpisodeRepository
import net.drehtuer.podsilo.core.model.port.FeedRepository

/** Matches S2's grace period, so opening and closing the sheet doesn't restart its query. */
private const val SUBSCRIPTION_TIMEOUT_MS = 5_000L

/**
 * S3 — the detail sheet (`docs/UI.md` §B4).
 *
 * Shares [TriageWriter] and [EpisodeScheduler] with S2 rather than writing rows itself, which is the
 * whole reason that class exists: a decision taken in the sheet and the same decision taken in the
 * row must produce byte-identical ledger rows.
 *
 * @property folderLabel where a delivered file went, resolved once. Not a check that the file is
 *   still there — the player owns it and may have deleted it (CLAUDE.md §11).
 */
@Suppress("LongParameterList") // A view model's parameter list is its port list; see EpisodeListViewModel.
class EpisodeDetailViewModel(
    private val episodeKey: String,
    private val episodeRepository: EpisodeRepository,
    private val ledgerRepository: EpisodeLedgerRepository,
    private val feedRepository: FeedRepository,
    private val triageWriter: TriageWriter,
    private val scheduler: EpisodeScheduler,
    private val folderLabel: DownloadFolderLabel,
    private val workMonitor: DownloadWorkMonitor,
) : ViewModel() {
    private val effects = Channel<EpisodeDetailEffect>(Channel.BUFFERED)
    val effect: Flow<EpisodeDetailEffect> = effects.receiveAsFlow()

    /**
     * Read once — an episode's parsed fields do not change while a sheet is open, and re-reading
     * them on every ledger write would replace the description under a user mid-scroll.
     */
    private val source: Flow<Loaded?> =
        flow {
            val episode = episodeRepository.get(episodeKey)
            val feed = episode?.let { feedRepository.get(it.feedUrl) }
            emit(
                episode?.let {
                    Loaded(
                        episode = it,
                        // The URL until the first successful fetch supplies a title — never
                        // "Unknown podcast" (architecture §4).
                        feedTitle = feed?.title ?: it.feedUrl,
                        feedArtwork = feed?.imageUrl,
                        folder = folderLabel.current(),
                    )
                },
            )
        }

    /**
     * `null` while loading, and also for an episode that is genuinely gone — a feed can be
     * unsubscribed while its sheet is open, which prunes the episode cache (CLAUDE.md §5). The host
     * closes the sheet on `null` rather than the view model rendering a hollow one.
     */
    val state: StateFlow<EpisodeDetailUiState?> =
        combine(source, ledgerRepository.observeRow(episodeKey), workMonitor.observe()) { loaded, row, work ->
            loaded?.let {
                // Same §7 projection as S2's rows: a sheet open on a running download shows the same
                // bar, from the same source, as the row it was opened from (docs/UI.md §12.2).
                val ui = EpisodeListItem(it.episode, row).toUi(it.feedTitle, it.feedArtwork, work)
                EpisodeDetailUiState(
                    episode = ui,
                    // Raw. Sanitising is the Composable's job (architecture §4).
                    descriptionHtml = it.episode.description.orEmpty(),
                    deliveredTo = deliveredTo(row?.state, row?.writtenFileName, it.folder),
                )
            }
        }.stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(SUBSCRIPTION_TIMEOUT_MS),
            initialValue = null,
        )

    fun onEvent(event: EpisodeDetailEvent) {
        when (event) {
            is EpisodeDetailEvent.Triage -> viewModelScope.launch { triage(event.action) }
            EpisodeDetailEvent.Dismissed -> emit(EpisodeDetailEffect.Close)
            EpisodeDetailEvent.ErrorDetailsClicked -> emit(EpisodeDetailEffect.OpenErrorLog)
            // Opening a link is not a decision, so the sheet stays open behind the browser
            // (docs/UI.md §6) — no Close effect here, deliberately.
            is EpisodeDetailEvent.LinkClicked -> emit(EpisodeDetailEffect.OpenUrl(event.url))
            EpisodeDetailEvent.OpenInBrowserClicked ->
                state.value?.episodePageUrl?.let { emit(EpisodeDetailEffect.OpenUrl(it)) }
        }
    }

    private suspend fun triage(action: EpisodeUiAction) {
        val episode = episodeRepository.get(episodeKey) ?: return
        when (action) {
            EpisodeUiAction.MARK_AS_PLAYED -> {
                triageWriter.markAsPlayed(listOf(episode))
                emit(EpisodeDetailEffect.ShowMessage(SnackbarText.BulkApplied(1)))
            }
            EpisodeUiAction.DOWNLOAD, EpisodeUiAction.DOWNLOAD_AGAIN, EpisodeUiAction.RETRY -> {
                triageWriter.queue(listOf(episode))
                // Same rule as S2: only a re-decision carries userRequested, because that flag is
                // the sole way past DownloadWorker's terminal-row refusal (docs/decisions/0012).
                scheduler.enqueueDownload(episodeKey, userRequested = action == EpisodeUiAction.DOWNLOAD_AGAIN)
                emit(EpisodeDetailEffect.ShowMessage(SnackbarText.Queued(1)))
            }
            EpisodeUiAction.CANCEL -> scheduler.cancelDownload(episodeKey)
            EpisodeUiAction.OPEN_IN_BROWSER -> episode.link?.let { emit(EpisodeDetailEffect.OpenUrl(it)) }
            // Copying is not opening. Both used to emit OpenUrl here too, so the sheet's
            // *Copy episode link* launched a browser as well.
            EpisodeUiAction.COPY_LINK ->
                episode.link?.let {
                    emit(EpisodeDetailEffect.CopyLink(it))
                    emit(EpisodeDetailEffect.ShowMessage(SnackbarText.LinkCopied))
                }
        }
        // Deciding closes the sheet (docs/UI.md §6) — the list animates the row into its new state,
        // and a sheet left open over a row that has already changed is the confusing half of that.
        if (action != EpisodeUiAction.OPEN_IN_BROWSER && action != EpisodeUiAction.COPY_LINK) {
            emit(EpisodeDetailEffect.Close)
        }
    }

    private fun emit(effect: EpisodeDetailEffect) {
        effects.trySend(effect)
    }

    private data class Loaded(
        val episode: Episode,
        val feedTitle: String,
        val feedArtwork: String?,
        val folder: String?,
    )
}

/**
 * Where a delivered file was written, as a sentence rather than a URI.
 *
 * `null` [folder] (no folder chosen, or the provider cannot name it) degrades to just the file name:
 * "somewhere" is not worth a line, but the name the user will look for in their player is.
 */
internal fun deliveredTo(
    state: LedgerState?,
    writtenFileName: String?,
    folder: String?,
): String? {
    if (state != LedgerState.DOWNLOADED || writtenFileName == null) return null
    return if (folder == null) writtenFileName else "$folder/$writtenFileName"
}

/**
 * The download folder's human-readable name, for the one line that reports where a file went.
 *
 * A port for the same reason [DownloadFolderStatus] is: turning a SAF tree URI into "SD card /
 * Podcasts" needs a `DocumentFile` and a `ContentResolver`, and `:feature:episodes` must not depend
 * on `:core:download`.
 */
fun interface DownloadFolderLabel {
    suspend fun current(): String?
}
