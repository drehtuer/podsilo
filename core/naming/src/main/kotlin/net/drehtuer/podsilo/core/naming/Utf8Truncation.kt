// SPDX-License-Identifier: GPL-3.0-or-later

package net.drehtuer.podsilo.core.naming

import java.text.BreakIterator

/**
 * Truncates [value] to at most [maxBytes] UTF-8 bytes without splitting a multi-byte character or
 * a grapheme cluster (CLAUDE.md section 6: the 255-byte FAT32/exFAT limit is per byte, not per
 * character -- truncating naively can produce invalid UTF-8 or split e.g. an emoji + modifier).
 *
 * Walks [BreakIterator] character-boundary steps (JDK stdlib -- an approximation of Unicode
 * extended grapheme clusters, but handles surrogate pairs and combining marks, which is what
 * actually matters for podcast titles) and keeps the last boundary whose UTF-8 encoding still fits.
 */
fun truncateUtf8Safe(
    value: String,
    maxBytes: Int,
): String =
    when {
        maxBytes <= 0 -> ""
        value.toByteArray(Charsets.UTF_8).size <= maxBytes -> value
        else -> truncateToLastFittingBoundary(value, maxBytes)
    }

private fun truncateToLastFittingBoundary(
    value: String,
    maxBytes: Int,
): String {
    val boundary = BreakIterator.getCharacterInstance()
    boundary.setText(value)
    var end = 0
    var next = boundary.next()
    while (next != BreakIterator.DONE) {
        if (value.substring(0, next).toByteArray(Charsets.UTF_8).size > maxBytes) break
        end = next
        next = boundary.next()
    }
    return value.substring(0, end)
}
