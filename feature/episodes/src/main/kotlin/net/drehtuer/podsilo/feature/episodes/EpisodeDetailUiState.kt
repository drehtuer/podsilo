// SPDX-License-Identifier: GPL-3.0-or-later

package net.drehtuer.podsilo.feature.episodes

/**
 * S3 — the episode detail sheet (`docs/UI.md` §B4).
 *
 * A **read step inside triage**, reachable for every episode regardless of state, including the
 * de-emphasised ones (`docs/UI.md` §6). It carries the same [EpisodeUi] the row does — so the sheet
 * and the row it opened from cannot offer different actions — plus the two things a row has no space
 * for: the full description and where the file went.
 *
 * @property descriptionHtml **raw**, straight from `Episode.description`. Sanitising happens at
 *   render (architecture §4), so this field is the unsanitised feed string by design; the only
 *   consumer is [sanitizeEpisodeHtml].
 * @property deliveredTo the folder label, shown only for a `DOWNLOADED` episode. Never a check that
 *   the file is still there — the player owns the file and may have deleted it (CLAUDE.md §11).
 */
data class EpisodeDetailUiState(
    val episode: EpisodeUi,
    val descriptionHtml: String,
    val deliveredTo: String? = null,
) {
    /** `null` → no browser row at all, rather than a dead tap (`docs/UI.md` §6). */
    val episodePageUrl: String? get() = episode.episodePageUrl
}

sealed interface EpisodeDetailEvent {
    data class Triage(
        val action: EpisodeUiAction,
    ) : EpisodeDetailEvent

    data object Dismissed : EpisodeDetailEvent

    /** The failure's technical half lives in S8; the sheet shows the sentence and a way there. */
    data object ErrorDetailsClicked : EpisodeDetailEvent

    /** A link inside the sanitised description. Only ever http(s) — see [sanitizeEpisodeHtml]. */
    data class LinkClicked(
        val url: String,
    ) : EpisodeDetailEvent

    data object OpenInBrowserClicked : EpisodeDetailEvent
}

sealed interface EpisodeDetailEffect {
    /**
     * Handed to a Custom Tab by the host. **Not navigation**: the sheet stays open behind it,
     * because leaving to read show notes is not a triage decision (`docs/UI.md` §6).
     */
    data class OpenUrl(
        val url: String,
    ) : EpisodeDetailEffect

    /** *Copy episode link*, which used to emit [OpenUrl] and therefore opened a browser instead. */
    data class CopyLink(
        val url: String,
    ) : EpisodeDetailEffect

    /** Deciding closes the sheet (`docs/UI.md` §6); dismissal is the host's to perform. */
    data object Close : EpisodeDetailEffect

    data object OpenErrorLog : EpisodeDetailEffect

    data class ShowMessage(
        val text: SnackbarText,
    ) : EpisodeDetailEffect
}
