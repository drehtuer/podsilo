// SPDX-License-Identifier: GPL-3.0-or-later

package net.drehtuer.podsilo.feature.episodes

import net.drehtuer.podsilo.core.model.Episode

/** A podcast's contribution to a pending bulk action, for the confirmation dialog's breakdown. */
data class FeedBreakdown(
    val feedUrl: String,
    val count: Int,
)

/**
 * What a bulk action *would* do, rendered by the confirmation dialog before anything is written
 * (`docs/UI.md` §5, `docs/decisions/0014`).
 *
 * The dialog is the safeguard that made bulk download acceptable at all, so this type exists to
 * make "name the count before you write" structural: `DownloadAllRequested` produces a preview and
 * writes nothing; only `DownloadAllConfirmed` writes.
 *
 * @property estimatedBytes `null` when **any** episode's duration is unknown. Partially estimating
 *   would understate the total and produce a "it fits" impression that is worse than no number at
 *   all — `itunes:duration` is unreliable enough that this must never look authoritative.
 * @property freeBytes `null` when the volume cannot answer, which is normal for some providers.
 * @property exceedsFreeSpace drives a **warning line only**. It never disables the action: the
 *   estimate is a guess, and a guess must not veto a decision the user has made.
 */
data class BulkPreview(
    val episodeKeys: List<String>,
    val perFeed: List<FeedBreakdown>,
    val estimatedBytes: Long?,
    val freeBytes: Long?,
) {
    val count: Int get() = episodeKeys.size

    val exceedsFreeSpace: Boolean
        get() = estimatedBytes != null && freeBytes != null && estimatedBytes > freeBytes
}

/**
 * Free space on the download volume, as the feature module sees it.
 *
 * A separate one-method port for the same reason [EpisodeScheduler] is one: `:feature:episodes` must
 * not depend on `:core:download` (or on Android), and `:app` already holds the `DownloadTarget` this
 * delegates to.
 */
fun interface DownloadSpaceProbe {
    /** `null` when unknowable — the dialog then shows no size warning at all. */
    suspend fun freeBytes(): Long?
}

/**
 * Rough bytes-per-second for a podcast enclosure, used only for the dialog's approximate total.
 *
 * 128 kbps is the common case for spoken-word MP3. This is openly a guess: it exists to answer "will
 * this obviously not fit?", never to be displayed as a precise figure, and it is why
 * [BulkPreview.exceedsFreeSpace] only ever adds a warning line.
 */
private const val ESTIMATED_BYTES_PER_SECOND = 16_000L
private const val MILLIS_PER_SECOND = 1_000L

internal fun buildBulkPreview(
    episodes: List<Episode>,
    freeBytes: Long?,
): BulkPreview =
    BulkPreview(
        episodeKeys = episodes.map { it.episodeKey },
        perFeed =
            episodes
                .groupingBy { it.feedUrl }
                .eachCount()
                .map { FeedBreakdown(it.key, it.value) }
                .sortedByDescending { it.count },
        estimatedBytes = episodes.estimatedBytes(),
        freeBytes = freeBytes,
    )

/** All-or-nothing: one unknown duration makes the whole estimate unreportable. See [BulkPreview]. */
private fun List<Episode>.estimatedBytes(): Long? {
    if (isEmpty() || any { it.durationMs == null }) return null
    return sumOf { (it.durationMs ?: 0L) / MILLIS_PER_SECOND * ESTIMATED_BYTES_PER_SECOND }
}
