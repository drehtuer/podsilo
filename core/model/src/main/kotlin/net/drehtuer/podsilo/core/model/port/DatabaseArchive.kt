// SPDX-License-Identifier: GPL-3.0-or-later

package net.drehtuer.podsilo.core.model.port

/**
 * Backup and restore of the whole local database as a single zip file.
 *
 * **Why this exists at all.** `episode_ledger` is the one table the app cannot rebuild from anything
 * else it owns — CLAUDE.md §5 calls it "the ONE table that must never be lost". Most of it *can* be
 * recovered from Nextcloud's action log, but only the part that reached the server: rows still in
 * the outbox, the `writtenFileName` a retry needs, and every `DOWNLOAD` action (which Nextcloud
 * discards outright — `docs/decisions/0008`) exist nowhere else. A phone that dies takes them with
 * it. This is the export that doesn't.
 *
 * **The archive holds no credentials.** The Nextcloud app password lives in DataStore behind a
 * Keystore-backed cipher (`docs/architecture.md` §2), not in the database, so it is not in the zip and
 * a restored install must be reconnected. That is deliberate: a backup file the user may copy to a
 * PC or a cloud drive must not be a credential file. It *does* contain feed and enclosure URLs,
 * episode titles and show notes — the user's subscription list in readable form.
 *
 * URIs cross this seam as strings, the same way `SettingsRepository.observeDownloadFolderUri` does,
 * so `:core:model` stays free of `android.net.Uri` (`docs/architecture.md` §2).
 */
interface DatabaseArchive {
    /** Writes a zip to [destinationUri], a SAF document the host just created. */
    suspend fun exportTo(destinationUri: String): ArchiveOutcome

    /**
     * Replaces the **entire** local database with the contents of the zip at [sourceUri].
     *
     * Destructive and not undoable, which is why the UI confirms it first. All-or-nothing: the
     * replacement runs in one transaction, so a corrupt or truncated archive leaves the existing
     * database exactly as it was rather than half-overwritten.
     */
    suspend fun importFrom(sourceUri: String): ArchiveOutcome
}

sealed interface ArchiveOutcome {
    data class Exported(
        val contents: ArchiveContents,
    ) : ArchiveOutcome

    data class Imported(
        val contents: ArchiveContents,
    ) : ArchiveOutcome

    /**
     * @property detail the underlying exception message, for the error log — never shown as the
     *   user-facing sentence, which comes from [reason] so it can be phrased as advice.
     */
    data class Failed(
        val reason: ArchiveFailure,
        val detail: String? = null,
    ) : ArchiveOutcome
}

/** What the archive turned out to hold. Reported back so the confirmation is about real numbers. */
data class ArchiveContents(
    val feeds: Int,
    val episodes: Int,
    val ledgerRows: Int,
)

/**
 * The failures worth telling apart, because each has a different next step for the user.
 *
 * [NEWER_SCHEMA] is separate from [NOT_AN_ARCHIVE] on purpose: "this is a Podsilo backup, but from a
 * newer version" is fixable by updating the app, and reporting it as "not a backup" would send the
 * user looking for a file that is right there.
 */
enum class ArchiveFailure {
    /** No manifest, or the zip has no database in it. */
    NOT_AN_ARCHIVE,

    /** Written by a later Podsilo whose schema this build cannot read. Room only migrates forward. */
    NEWER_SCHEMA,

    /** A real backup that could not be opened — truncated, corrupt, or the SAF grant went away. */
    UNREADABLE,

    /** The destination could not be written: no space, or the picked document vanished. */
    WRITE_FAILED,
}
