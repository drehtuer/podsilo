// SPDX-License-Identifier: GPL-3.0-or-later

package net.drehtuer.podsilo.core.download

import android.content.Context
import android.net.Uri
import androidx.documentfile.provider.DocumentFile
import kotlinx.coroutines.flow.first
import net.drehtuer.podsilo.core.model.port.SettingsRepository
import java.io.File
import java.io.IOException

/** MIME type for files we create through SAF; the real container is carried by the extension we chose. */
private const val AUDIO_MIME_TYPE = "audio/*"

/**
 * [DownloadTarget] over the Storage Access Framework — the only production implementation.
 *
 * Everything here goes through `DocumentFile`/`ContentResolver`: on modern Android there is no
 * writable `java.io.File` path into a user-chosen folder, and a tree URI cannot be converted to
 * one (CLAUDE.md §11). The grant can vanish between downloads (revoked, SD card pulled, app data
 * cleared), so every operation re-resolves the tree and reports
 * [DownloadFolderUnavailableException] rather than assuming yesterday's permission still holds.
 *
 * **Not unit-tested**: `DocumentFile.fromTreeUri` needs a real `DocumentsProvider`, which no
 * headless test runner supplies. The pipeline around it is tested through [DownloadTarget]'s fake;
 * this class itself is verified only by running the app. Stated plainly per CLAUDE.md §9.
 */
class SafDownloadTarget(
    private val context: Context,
    private val settingsRepository: SettingsRepository,
) : DownloadTarget {
    override suspend fun existingNames(folder: String): Result<Set<String>> =
        runCatchingSaf {
            val root = requireTree()
            val target = root.findFile(folder)?.takeIf { it.isDirectory } ?: return@runCatchingSaf emptySet()
            target.listFiles().mapNotNull { it.name }.toSet()
        }

    override suspend fun deliver(
        folder: String,
        fileName: String,
        source: File,
    ): Result<Unit> =
        runCatchingSaf {
            val directory = resolveOrCreateFolder(requireTree(), folder)
            // A same-named document is this episode's own previous attempt (the ledger hands retries
            // back the recorded name). Replacing it in place keeps one file per episode; SAF's
            // createFile would otherwise happily produce "name (1)" behind our back.
            directory.findFile(fileName)?.delete()
            val document =
                directory.createFile(AUDIO_MIME_TYPE, fileName)
                    ?: throw DownloadFolderUnavailableException("could not create '$fileName' in the download folder")

            val written =
                context.contentResolver.openOutputStream(document.uri, "wt")
                    ?: throw DownloadFolderUnavailableException("could not open '$fileName' for writing")
            written.use { sink -> source.inputStream().use { it.copyTo(sink) } }
        }

    private suspend fun requireTree(): DocumentFile {
        val uri =
            settingsRepository.observeDownloadFolderUri().first()
                ?: throw DownloadFolderUnavailableException("no download folder has been chosen yet")
        val tree = DocumentFile.fromTreeUri(context, Uri.parse(uri))
        if (tree == null || !tree.isDirectory || !tree.canWrite()) {
            throw DownloadFolderUnavailableException("the download folder is no longer accessible: $uri")
        }
        return tree
    }

    /** Template folders are a single component today ("{podcast}"), but nested ones must not silently flatten. */
    private fun resolveOrCreateFolder(
        root: DocumentFile,
        folder: String,
    ): DocumentFile =
        folder
            .split('/')
            .filter { it.isNotBlank() }
            .fold(root) { parent, segment ->
                val existing = parent.findFile(segment)
                when {
                    existing != null && existing.isDirectory -> existing
                    else ->
                        parent.createDirectory(segment)
                            ?: throw DownloadFolderUnavailableException("could not create folder '$segment'")
                }
            }
}

/**
 * SAF surfaces a lost grant as [SecurityException] and a dead provider as [IllegalArgumentException]
 * or [IOException] depending on the OEM implementation — all of them mean "the folder isn't usable",
 * which the caller handles identically. Anything else is a real bug and propagates.
 */
private inline fun <T> runCatchingSaf(block: () -> T): Result<T> =
    try {
        Result.success(block())
    } catch (unavailable: DownloadFolderUnavailableException) {
        Result.failure(unavailable)
    } catch (revoked: SecurityException) {
        Result.failure(DownloadFolderUnavailableException(revoked.message ?: "download folder permission revoked"))
    } catch (badUri: IllegalArgumentException) {
        Result.failure(DownloadFolderUnavailableException(badUri.message ?: "download folder URI is no longer valid"))
    } catch (io: IOException) {
        Result.failure(DownloadFolderUnavailableException(io.message ?: "could not write to the download folder"))
    }
