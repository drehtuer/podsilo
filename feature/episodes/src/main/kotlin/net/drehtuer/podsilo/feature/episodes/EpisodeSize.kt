// SPDX-License-Identifier: GPL-3.0-or-later

package net.drehtuer.podsilo.feature.episodes

/**
 * The advertised download size, rendered for a decision rather than for accounting.
 *
 * **MB throughout**, never switching to GB past a threshold: a list where most rows read "48 MB" and
 * one reads "1.2 GB" makes the outlier harder to compare at a glance, and comparing is the one job
 * this number has.
 *
 * **Whole megabytes**, because the source is `<enclosure length>` — what a publisher claims, not what
 * the server will send. Decimals would imply a precision it does not have, the same reason durations
 * render in whole minutes.
 *
 * Anything under a megabyte reports `<1 MB` rather than `0 MB`: a feed advertising a few hundred
 * bytes is wrong, and "0" reads as free.
 */
internal fun Long.formatSize(): String {
    val megabytes = this / BYTES_PER_MB
    return if (megabytes < 1) "<1 MB" else "$megabytes MB"
}

// Decimal MB, matching how every podcast host and file manager states a download size. The binary
// mebibyte would be defensible and would disagree with what the user sees everywhere else.
private const val BYTES_PER_MB = 1_000_000L
