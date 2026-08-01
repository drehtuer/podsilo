// SPDX-License-Identifier: GPL-3.0-or-later

package net.drehtuer.podsilo.core.download

import java.io.File

/**
 * [DownloadTarget] over a plain temp directory — the test seam `docs/decisions/0011` exists for.
 * Behaves like the SAF implementation in the ways the pipeline depends on (folders created on
 * demand, same-named documents replaced) without needing a `DocumentsProvider`.
 */
class FakeDownloadTarget(
    private val root: File,
) : DownloadTarget {
    /** Set to simulate a revoked grant / removed SD card on the next call. */
    var unavailable: DownloadFolderUnavailableException? = null

    /** What [freeBytes] reports; `null` is the "provider doesn't say" case the UI must tolerate. */
    var freeSpace: Long? = null

    val deliveries = mutableListOf<String>()

    override suspend fun existingNames(folder: String): Result<Set<String>> {
        unavailable?.let { return Result.failure(it) }
        val names =
            File(root, folder)
                .listFiles()
                ?.map { it.name }
                ?.toSet()
                .orEmpty()
        return Result.success(names)
    }

    override suspend fun deliver(
        folder: String,
        fileName: String,
        source: File,
    ): Result<Unit> {
        unavailable?.let { return Result.failure(it) }
        val directory = File(root, folder)
        directory.mkdirs()
        source.copyTo(File(directory, fileName), overwrite = true)
        deliveries += "$folder/$fileName"
        return Result.success(Unit)
    }

    override suspend fun freeBytes(): Long? = freeSpace

    /** Puts a file in the folder without going through [deliver] — i.e. "it was already there". */
    fun seed(
        folder: String,
        fileName: String,
    ) {
        val directory = File(root, folder)
        directory.mkdirs()
        File(directory, fileName).writeText("previously delivered")
    }

    fun delivered(
        folder: String,
        fileName: String,
    ): File = File(File(root, folder), fileName)
}
