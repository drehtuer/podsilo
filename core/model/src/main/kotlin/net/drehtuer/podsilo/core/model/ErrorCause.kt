// SPDX-License-Identifier: GPL-3.0-or-later

package net.drehtuer.podsilo.core.model

/**
 * Why something failed, classified **where the failure happened** and stored, rather than re-derived
 * later by reading the message text (`UI.adoc` §B1).
 *
 * This exists because a screen has to make a decision that a sentence cannot support:
 * `UI.adoc` §12.11 and `architecture.adoc` §11 require that a row whose download failed because the
 * folder grant is gone offers **Choose folder** and *never* a bare **Retry** — retrying cannot
 * possibly work until the user re-picks the folder, so a Retry button there is a button that lies.
 *
 * Parsing `lastError` in the UI to work that out would be exactly the kind of fragile string-matching
 * that breaks the first time a message is reworded, and it would break silently, into the *unsafe*
 * direction. So the pipeline records what it already knows (same principle as
 * `architecture.adoc` §4: snapshot at write time what a later reader will need).
 *
 * **Every value here has a writer.** `FEED_PARSE` and `TAG_WRITE` were declared with the rest and
 * never produced by anything, because neither failure can reach a ledger row: a tag-write failure
 * must never fail a download (CLAUDE.md §6), and a feed failure is recorded in the error log under
 * [port.LogCategory.FEED] rather than against an episode. They were removed on 2026-08-14 rather
 * than left as vocabulary the UI has to handle and no test can produce.
 *
 * Removing a value is safe because the mapper reads the stored column with
 * `runCatching { enumValueOf(...) }.getOrNull()` — a name this enum no longer has becomes `null`,
 * which the UI already renders as [UNKNOWN]. Adding one is the ordinary case; before you add one,
 * write the code that produces it.
 */
enum class ErrorCause {
    NETWORK,
    SERVER,
    AUTH,
    DISK_FULL,
    FOLDER_UNAVAILABLE,

    /**
     * The enclosure is served over plain `http://` and Android refused the connection.
     *
     * Its own value rather than [NETWORK] because it is permanent — the request never left the
     * device, and every retry is refused identically — and because the sentence a user needs is
     * about the *feed*, not about their connection.
     *
     * **Podsilo does not rewrite the URL to `https://` to get around it.** An enclosure URL is not
     * decoration: it is `episodeKey`'s fallback when a feed omits `<guid>`, and it is the `episode`
     * field of every action posted to the shared GPodder log (`architecture.adoc` §4/§6). A
     * rewritten one is a *different episode* to AntennaPod and to Nextcloud, which is the one kind
     * of drift this app exists to prevent. Cover art carries no identity and is upgraded, in
     * `:core:feed`; enclosures are left exactly as the feed published them.
     */
    CLEARTEXT_BLOCKED,
    UNKNOWN,
}
