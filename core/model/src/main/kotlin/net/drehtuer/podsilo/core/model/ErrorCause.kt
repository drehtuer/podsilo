// SPDX-License-Identifier: GPL-3.0-or-later

package net.drehtuer.podsilo.core.model

/**
 * Why something failed, classified **where the failure happened** and stored, rather than re-derived
 * later by reading the message text (`docs/UI.md` §B1).
 *
 * This exists because a screen has to make a decision that a sentence cannot support:
 * `docs/UI.md` §12.11 and `docs/architecture.md` §11 require that a row whose download failed because the
 * folder grant is gone offers **Choose folder** and *never* a bare **Retry** — retrying cannot
 * possibly work until the user re-picks the folder, so a Retry button there is a button that lies.
 *
 * Parsing `lastError` in the UI to work that out would be exactly the kind of fragile string-matching
 * that breaks the first time a message is reworded, and it would break silently, into the *unsafe*
 * direction. So the pipeline records what it already knows (same principle as
 * `docs/architecture.md` §4: snapshot at write time what a later reader will need).
 */
enum class ErrorCause {
    NETWORK,
    SERVER,
    AUTH,
    FEED_PARSE,
    DISK_FULL,
    FOLDER_UNAVAILABLE,
    TAG_WRITE,
    UNKNOWN,
}
