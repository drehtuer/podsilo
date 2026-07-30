// SPDX-License-Identifier: GPL-3.0-or-later

package net.drehtuer.podsilo.core.naming

/**
 * Deterministically finds a name not in [existingNames] by appending ` (2)`, ` (3)`, ... (CLAUDE.md
 * section 6 -- daily shows genuinely reuse titles). Purely a naming decision: `:core:download` is
 * the one that knows which names already exist in the SAF folder and persists the winner as
 * `EpisodeLedgerRow.writtenFileName` so a retry reuses it instead of colliding again.
 */
fun nextAvailableName(
    candidate: String,
    existingNames: Set<String>,
): String {
    if (candidate !in existingNames) return candidate
    var suffix = 2
    while ("$candidate ($suffix)" in existingNames) suffix++
    return "$candidate ($suffix)"
}
