// SPDX-License-Identifier: GPL-3.0-or-later

package net.drehtuer.podsilo.core.download

import net.drehtuer.podsilo.core.model.Episode
import net.drehtuer.podsilo.core.model.Feed
import net.drehtuer.podsilo.core.model.port.NamingSettings
import net.drehtuer.podsilo.core.model.port.ResolvedName
import net.drehtuer.podsilo.core.naming.DefaultNamingTemplateEngine
import net.drehtuer.podsilo.core.naming.TitleCleanupRule
import net.drehtuer.podsilo.core.naming.applyCleanupRules
import net.drehtuer.podsilo.core.naming.guidShort
import net.drehtuer.podsilo.core.naming.nextAvailableName
import java.io.File
import java.time.Instant
import java.time.ZoneId
import java.util.regex.PatternSyntaxException

private const val HTTP_REQUEST_TIMEOUT = 408
private const val HTTP_TOO_MANY_REQUESTS = 429
private const val HTTP_SERVER_ERROR_FLOOR = 500

/** What one run of the pipeline in `docs/architecture.md` §11 produced. */
sealed interface DownloadOutcome {
    /**
     * The file is in the user's folder under [fileName] (extension included) — the name to persist
     * as `EpisodeLedgerRow.writtenFileName` so a retry reuses it. [tagOutcome] is informational:
     * a tag failure never downgrades a delivery (CLAUDE.md §6).
     */
    data class Delivered(
        val fileName: String,
        val tagOutcome: TagWriteOutcome,
    ) : DownloadOutcome

    /**
     * @property retryable `true` for transient conditions worth handing back to WorkManager's
     *   backoff (network, 5xx, 408/429); `false` where retrying cannot help until the user does
     *   something — a 404, a revoked folder grant, a full disk, an unparseable cleanup rule.
     */
    data class Failed(
        val reason: String,
        val retryable: Boolean,
    ) : DownloadOutcome
}

/**
 * Sequences the whole download pipeline CLAUDE.md §6 mandates:
 * `download to app cache → verify → resolve name → rewrite tags → copy into the SAF tree → delete cache`.
 *
 * Nothing here touches an Android API: the SAF half sits behind [DownloadTarget]
 * (`docs/decisions/0011`) and the ledger writes belong to [DownloadWorker], which is what makes
 * this class — the part with all the branching — testable without an emulator.
 *
 * It contains **no** string-sanitisation logic of its own; every naming decision, including
 * collision suffixing, comes from `:core:naming` (`docs/architecture.md` §11).
 *
 * @property cacheDir app-private scratch space. One in-flight download at a time is the budget
 *   CLAUDE.md §6 asks for; the partial file is deliberately left behind on a failed or cancelled
 *   *download* so the next attempt resumes, and cleaned up once the bytes are safely delivered.
 */
class EpisodeDownloader(
    private val enclosureDownloader: EnclosureDownloader,
    private val audioTagWriter: AudioTagWriter,
    private val downloadTarget: DownloadTarget,
    private val cacheDir: File,
    private val zoneId: ZoneId = ZoneId.systemDefault(),
) {
    /**
     * @param previousFileName the name a previous successful delivery recorded in the ledger.
     *   Reused verbatim so a retry overwrites its own file instead of creating a second copy
     *   (CLAUDE.md §6). Never derived from what is on disk — file presence is not an input to any
     *   decision here (§11).
     *
     * `@Suppress("ReturnCount")`: three exits, each a distinct outcome of a linear pipeline —
     * unusable settings, a fetch that didn't complete, and the delivery result. Collapsing them
     * would need nesting that reads worse than the sequence does.
     */
    @Suppress("ReturnCount")
    suspend fun download(
        feed: Feed,
        episode: Episode,
        naming: NamingSettings,
        previousFileName: String? = null,
        onProgress: (bytesWritten: Long, totalBytes: Long?) -> Unit = { _, _ -> },
    ): DownloadOutcome {
        // Compiled once, up front: a user-entered pattern that doesn't compile is a settings bug the
        // author has to fix, so it surfaces as a visible non-retryable error rather than being
        // silently dropped or blowing up halfway through the pipeline.
        val cleanupRules =
            try {
                naming.compiledCleanupRules()
            } catch (invalidRule: PatternSyntaxException) {
                val reason = invalidRule.message ?: "invalid title cleanup rule"
                return DownloadOutcome.Failed("naming settings contain an invalid rule: $reason", retryable = false)
            }

        val cacheFile = File(cacheDir, "${guidShort(episode.episodeKey)}.partial")
        val result = enclosureDownloader.download(episode.enclosureUrl, cacheFile, onProgress)
        val fetched = result as? EnclosureDownloadResult.Completed ?: return result.toFailure()

        return try {
            deliver(
                Delivery(
                    feed = feed,
                    episode = episode,
                    naming = naming,
                    cleanupRules = cleanupRules,
                    previousFileName = previousFileName,
                    fetched = fetched,
                ),
            )
        } finally {
            // Past this point the bytes are either in the user's folder or unrecoverable by a
            // resume anyway, so the scratch copies have no further use. Prefix-matched because the
            // file gets renamed to carry its real extension before tagging (see renameForTagging).
            val prefix = guidShort(episode.episodeKey)
            cacheDir.listFiles { file -> file.name.startsWith(prefix) }?.forEach { it.delete() }
        }
    }

    /** Everything one delivery needs, grouped so the steps below read as a pipeline, not a parameter list. */
    private data class Delivery(
        val feed: Feed,
        val episode: Episode,
        val naming: NamingSettings,
        val cleanupRules: List<TitleCleanupRule>,
        val previousFileName: String?,
        val fetched: EnclosureDownloadResult.Completed,
    )

    private suspend fun deliver(delivery: Delivery): DownloadOutcome {
        val engine =
            DefaultNamingTemplateEngine(
                zoneId = zoneId,
                titleCleanupRules = delivery.cleanupRules,
                transliterate = delivery.naming.transliterate,
            )
        val resolved =
            engine.resolve(
                feed = delivery.feed,
                episode = delivery.episode,
                folderTemplate = delivery.naming.folderTemplate,
                fileTemplate = delivery.naming.fileTemplate,
                contentType = delivery.fetched.contentType,
            )

        // Best-effort, and deliberately before the copy: an untagged file must never reach the
        // user's folder, but a failed tag write must never lose the download (CLAUDE.md §6).
        val taggable = renameForTagging(delivery.fetched.file, delivery.episode, resolved.extension)
        val tagData = tagDataFor(delivery.feed, delivery.episode, delivery.cleanupRules)
        val tagOutcome = audioTagWriter.writeTags(taggable, tagData)

        val fileName =
            delivery.previousFileName ?: uniqueFileName(resolved).getOrElse { failure ->
                return DownloadOutcome.Failed(failure.message ?: "download folder unavailable", retryable = false)
            }

        return downloadTarget.deliver(resolved.folder, fileName, taggable).fold(
            onSuccess = { DownloadOutcome.Delivered(fileName, tagOutcome) },
            onFailure = { failure ->
                DownloadOutcome.Failed(failure.message ?: "could not write to the download folder", false)
            },
        )
    }

    /**
     * jaudiotagger chooses its reader from the **file extension**, so a `.partial` scratch file can
     * never be tagged — it reports "no reader associated with this extension" and every download
     * would silently arrive untagged. Renaming to the extension the file is about to be delivered
     * under is what makes the tagging step work at all. The download itself keeps the stable
     * `.partial` name so a resume always knows what to look for.
     *
     * A failed rename is not fatal: tagging will fail on the original file, and CLAUDE.md §6 says a
     * tag failure must still deliver the audio.
     */
    private fun renameForTagging(
        downloaded: File,
        episode: Episode,
        extension: String,
    ): File {
        val taggable = File(cacheDir, "${guidShort(episode.episodeKey)}.$extension")
        if (taggable == downloaded) return downloaded
        taggable.delete()
        return if (downloaded.renameTo(taggable)) taggable else downloaded
    }

    /**
     * Collisions are suffixed ` (2)`, ` (3)`, … *before* the extension — daily shows genuinely
     * reuse episode titles (CLAUDE.md §6). Only names sharing this file's extension can collide,
     * since a differing extension already makes a different file name.
     */
    private suspend fun uniqueFileName(resolved: ResolvedName): Result<String> =
        downloadTarget.existingNames(resolved.folder).map { existing ->
            val suffix = ".${resolved.extension}"
            val takenBases = existing.filter { it.endsWith(suffix) }.map { it.removeSuffix(suffix) }.toSet()
            nextAvailableName(resolved.fileNameWithoutExtension, takenBases) + suffix
        }

    private fun tagDataFor(
        feed: Feed,
        episode: Episode,
        cleanupRules: List<TitleCleanupRule>,
    ): AudioTagData =
        AudioTagData(
            title = applyCleanupRules(episode.title, cleanupRules),
            artist = feed.title,
            album = feed.title,
            // No track number: the filename already carries a sortable date, and a fabricated
            // track would show up as "Track 20260714" in players that display it verbatim.
            year =
                episode.pubDate?.let {
                    Instant
                        .ofEpochMilli(it)
                        .atZone(zoneId)
                        .year
                        .toString()
                },
            comment = episode.description,
        )
}

private fun NamingSettings.compiledCleanupRules(): List<TitleCleanupRule> =
    titleCleanupRules.map { TitleCleanupRule(Regex(it.pattern), it.replacement) }

/**
 * Maps a non-[EnclosureDownloadResult.Completed] fetch to a pipeline failure, deciding what is
 * worth another attempt: 5xx, 408 and 429 are the server saying "later", and a lost connection is
 * transient by definition. Every other 4xx (404 for a pulled episode, 403 for an expired signed
 * URL) and every disk problem will fail identically on retry, and belongs in the ledger's ERROR
 * state for the user to act on.
 */
private fun EnclosureDownloadResult.toFailure(): DownloadOutcome.Failed =
    when (this) {
        is EnclosureDownloadResult.Completed -> error("a completed download is not a failure")
        is EnclosureDownloadResult.HttpError ->
            DownloadOutcome.Failed(
                "HTTP $code $message".trim(),
                retryable =
                    code >= HTTP_SERVER_ERROR_FLOOR ||
                        code == HTTP_REQUEST_TIMEOUT ||
                        code == HTTP_TOO_MANY_REQUESTS,
            )
        is EnclosureDownloadResult.NetworkError -> DownloadOutcome.Failed(reason, retryable = true)
        is EnclosureDownloadResult.WriteError -> DownloadOutcome.Failed(reason, retryable = false)
    }
