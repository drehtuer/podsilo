// SPDX-License-Identifier: GPL-3.0-or-later

package net.drehtuer.podsilo.core.naming

/**
 * Windows reserved device names (CLAUDE.md section 6) -- invalid as a full filename/foldername
 * component on Windows regardless of extension, e.g. `CON.mp3` is still reserved. Files may later
 * be synced to a desktop, so this is checked even though Android itself doesn't care.
 */
private const val MAX_RESERVED_DEVICE_NUMBER = 9

private val RESERVED_NAMES =
    setOf("CON", "PRN", "AUX", "NUL") +
        (1..MAX_RESERVED_DEVICE_NUMBER).map { "COM$it" } +
        (1..MAX_RESERVED_DEVICE_NUMBER).map { "LPT$it" }

fun isReservedName(candidate: String): Boolean = candidate.uppercase() in RESERVED_NAMES

/** Appends a harmless suffix so a reserved name still resembles the original. */
fun escapeReservedName(candidate: String): String = if (isReservedName(candidate)) "${candidate}_" else candidate
