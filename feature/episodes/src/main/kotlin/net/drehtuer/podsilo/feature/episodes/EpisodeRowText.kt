// SPDX-License-Identifier: GPL-3.0-or-later

package net.drehtuer.podsilo.feature.episodes

import net.drehtuer.podsilo.core.model.LedgerState
import net.drehtuer.podsilo.core.ui.PodsiloIcons
import java.time.Duration
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle

internal const val MINUTES_PER_HOUR = 60

// How an episode reads as words and badges, split from what the row draws.
//
// The seam detekt asked for when `EpisodeRow.kt` hit its function ceiling, and a real one: these are
// pure functions of an `EpisodeUi` with no Compose in them, which is why the row, the detail sheet
// and S7 can all share them — and why they are unit-testable without a Compose runtime.

/**
 * A `FOLDER_UNAVAILABLE` or `DISK_FULL` failure replaces *Retry* with the action that can actually
 * clear it (`docs/UI.md` §12.11, `docs/architecture.md` §11) — a Retry there is a button that cannot work.
 */
internal fun EpisodeUiAction.labelFor(episode: EpisodeUi): String? =
    when (this) {
        EpisodeUiAction.DOWNLOAD -> "Download"
        EpisodeUiAction.DOWNLOAD_AGAIN -> "Download again"
        EpisodeUiAction.MARK_AS_PLAYED -> "Mark as played"
        EpisodeUiAction.CANCEL -> "Cancel"
        EpisodeUiAction.RETRY ->
            when (episode.lastError?.remedy) {
                FailureRemedy.CHOOSE_FOLDER -> "Choose folder"
                FailureRemedy.FREE_UP_SPACE -> "Free up space"
                null -> "Retry"
            }
        // Reachable from the row overflow and the detail sheet, not as a primary button.
        EpisodeUiAction.OPEN_IN_BROWSER, EpisodeUiAction.COPY_LINK -> null
    }

internal fun EpisodeUi.metaLine(zone: ZoneId): String =
    listOfNotNull(publishedAt?.formatDate(zone), duration?.formatDuration(), sizeBytes?.formatSize())
        .joinToString(" · ")

internal fun EpisodeUi.statusLine(): String? =
    when (ledgerState) {
        null -> null
        LedgerState.QUEUED -> "queued"
        LedgerState.DOWNLOADING -> null
        LedgerState.DOWNLOADED -> "downloaded"
        LedgerState.SKIPPED -> "played"
        LedgerState.HANDLED_REMOTELY -> "handled elsewhere"
        // The message is passed through verbatim: it is the one string the UI does not re-word.
        LedgerState.ERROR -> lastError?.let { "failed — ${it.message} (attempt ${it.attempts})" } ?: "failed"
    }

/**
 * The badge beside [statusLine].
 *
 * `HANDLED_REMOTELY` gets `cloud-check`, **not** `check`: rendering it as the same tick as a download
 * this device performed would claim a decision the user did not make here, and the affordances differ
 * (`docs/UI.md` §12.6, §18). `play` is the *played* marker and never playback — Podsilo has no player
 * — which is why it only ever appears beside the word.
 */
internal fun EpisodeUi.statusIcon(): Int? =
    when (ledgerState) {
        null, LedgerState.DOWNLOADING -> null
        LedgerState.QUEUED -> PodsiloIcons.Download
        LedgerState.DOWNLOADED -> PodsiloIcons.Check
        LedgerState.SKIPPED -> PodsiloIcons.Played
        LedgerState.HANDLED_REMOTELY -> PodsiloIcons.HandledRemotely
        LedgerState.ERROR -> PodsiloIcons.Warning
    }

private fun Instant.formatDate(zone: ZoneId): String =
    DateTimeFormatter.ofLocalizedDate(FormatStyle.MEDIUM).withZone(zone).format(this)

/** Never fabricated: an absent duration simply has no part in the meta line (`docs/UI.md` §5). */
private fun Duration.formatDuration(): String {
    val minutes = toMinutes()
    return if (minutes >= MINUTES_PER_HOUR) {
        "${minutes / MINUTES_PER_HOUR} h ${minutes % MINUTES_PER_HOUR} min"
    } else {
        "$minutes min"
    }
}

/**
 * A percentage is only ever drawn from an update seen **in this process** (`docs/UI.md` Part B
 * §7). After process death WorkManager's progress is gone, so a `DOWNLOADING` row with none reads
 * *resuming* rather than implying it knows how far along it is.
 */
