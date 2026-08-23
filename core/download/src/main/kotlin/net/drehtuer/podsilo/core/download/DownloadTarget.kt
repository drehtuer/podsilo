// SPDX-License-Identifier: GPL-3.0-or-later

package net.drehtuer.podsilo.core.download

import java.io.File

/**
 * The final step of the download pipeline: getting a finished, tagged cache file into the user's
 * chosen folder (`architecture.adoc` §8/§11).
 *
 * This is a deliberate seam, not a "in case we swap SAF later" wrapper — CLAUDE.md §3 forbids the
 * latter. SAF is the only implementation there will ever be ([SafDownloadTarget]), but a
 * `DocumentFile` write needs a real `DocumentsProvider`, which exists on a device and not in a JVM
 * unit test. Without this interface the entire pipeline — naming, collision suffixing, tagging,
 * retry reuse of `writtenFileName`, cache cleanup — would be testable only on an emulator, and this
 * project has never successfully booted one (`dev-environment.adoc` §6). See
 * `architecture.adoc` §11.
 */
interface DownloadTarget {
    /**
     * File names (with extension) already present in [folder], for collision suffixing. An absent
     * folder yields an empty set rather than an error — it will be created on [deliver].
     *
     * Explicitly **not** a de-duplication check: whether a previously downloaded episode's file is
     * still there says nothing about whether it was already handled (CLAUDE.md §11's single most
     * important invariant). The ledger answers that; this only stops two different episodes
     * fighting over one name.
     *
     * **One caller is licensed to use it as an existence check, and only one**
     * (`decisions/0012` §4): [EpisodeDownloader]'s pre-flight duplicate guard, which runs *only*
     * when `KEY_USER_REQUESTED` is set and the row already carries a `writtenFileName`. It asks a
     * narrower question — "is the file *this* episode previously wrote still here?" — because the
     * user asked for that specific file again. It never decides whether an episode is new or whether
     * it was handled. If you find that guard and conclude the rule above was abandoned, it wasn't.
     */
    suspend fun existingNames(folder: String): Result<Set<String>>

    /**
     * Copies [source] into [folder] under exactly [fileName], overwriting a file of that name if
     * one exists — a retry reuses the name the ledger recorded, and must replace its own partial
     * predecessor rather than create `… (2)`.
     */
    suspend fun deliver(
        folder: String,
        fileName: String,
        source: File,
    ): Result<Unit>

    /**
     * Free space on the volume the download folder lives on, or `null` when it cannot be
     * determined — a tree URI can point at a provider that reports no space at all (a network
     * share, some cloud providers), and that is normal rather than an error.
     *
     * Used only for the non-blocking warning line on the bulk-download confirmation
     * (`UI.adoc` §5). `null` means the warning is simply not shown: the estimate it feeds is
     * derived from `itunes:duration`, which is unreliable enough that it must never *block* a
     * decision — so being unable to compute it is not a failure worth surfacing.
     */
    suspend fun freeBytes(): Long?
}

/** Raised when the SAF grant is gone (revoked, card removed, app data cleared) — the user must re-pick the folder. */
class DownloadFolderUnavailableException(
    message: String,
) : Exception(message)
