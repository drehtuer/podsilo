// SPDX-License-Identifier: GPL-3.0-or-later

package net.drehtuer.podsilo.feature.episodes

import java.time.ZoneId

/** A month grouping key. `kotlinx.datetime` has no `YearMonth` and we added no date library (architecture §5). */
data class YearMonth(
    val year: Int,
    val month: Int,
)

/**
 * One sticky header and the run of rows under it (`docs/UI.adoc` §5).
 *
 * @property label `null` is the trailing *Date unknown* group. Undated episodes are still fully
 *   triageable, so they get a group rather than being hidden or given an invented date.
 * @property firstIndex index into the rendered list, so the list and its headers cannot drift.
 */
data class MonthSection(
    val label: YearMonth?,
    val firstIndex: Int,
    val count: Int,
)

/**
 * Groups already-sorted rows into month runs.
 *
 * Takes the list in its final display order and walks it, rather than sorting or grouping itself:
 * the ordering is the query's (newest first, undated last), and re-deriving it here would be a
 * second source of truth for what order the screen is in.
 */
internal fun monthSectionsFor(
    rows: List<EpisodeUi>,
    zone: ZoneId,
): List<MonthSection> {
    if (rows.isEmpty()) return emptyList()
    val sections = mutableListOf<MonthSection>()
    var currentLabel: YearMonth? = rows.first().monthLabel(zone)
    var runStart = 0

    rows.forEachIndexed { index, row ->
        val label = row.monthLabel(zone)
        if (index > 0 && label != currentLabel) {
            sections += MonthSection(currentLabel, runStart, index - runStart)
            currentLabel = label
            runStart = index
        }
    }
    sections += MonthSection(currentLabel, runStart, rows.size - runStart)
    return sections
}

private fun EpisodeUi.monthLabel(zone: ZoneId): YearMonth? =
    publishedAt?.atZone(zone)?.let { YearMonth(it.year, it.monthValue) }
