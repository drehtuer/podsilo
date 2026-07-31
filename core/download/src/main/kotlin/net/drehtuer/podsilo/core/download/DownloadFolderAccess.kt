// SPDX-License-Identifier: GPL-3.0-or-later

package net.drehtuer.podsilo.core.download

import android.content.Context
import android.content.Intent
import android.net.Uri
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import net.drehtuer.podsilo.core.model.port.SettingsRepository

/** State of the user's download-folder grant. Re-derived from the system on every check, never cached. */
sealed interface DownloadFolderState {
    /** No folder picked yet — the settings screen should offer the picker, downloads can't run. */
    data object NotChosen : DownloadFolderState

    data class Granted(
        val treeUri: String,
    ) : DownloadFolderState

    /**
     * A folder was chosen once, but the persisted permission is gone: app data cleared, SD card
     * removed, or the user revoked it. The UI must prompt for a re-grant rather than letting
     * downloads fail mysteriously (CLAUDE.md §11).
     */
    data class Revoked(
        val treeUri: String,
    ) : DownloadFolderState
}

/**
 * Owns the `ACTION_OPEN_DOCUMENT_TREE` grant: takes the persistable permission when a folder is
 * picked, and re-checks on demand that it is still held (CLAUDE.md §11 — the grant *can* be lost,
 * and pretending otherwise turns a recoverable problem into silently failing downloads).
 *
 * The picker itself is launched by `:feature:settings` (an `ActivityResultContract` is a UI
 * concern); this class handles everything that happens with the returned URI.
 */
class DownloadFolderAccess(
    private val context: Context,
    private val settingsRepository: SettingsRepository,
) {
    /** Flags to request and to persist: SAF grants are per-flag, and a read-only grant can't deliver files. */
    private val persistableFlags: Int =
        Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION

    /**
     * Persists the grant returned by the system picker, then records the folder. Order matters: if
     * the permission can't be taken there is nothing worth storing, and a stored URI we cannot write
     * to would read as "configured" while failing every download.
     */
    suspend fun remember(treeUri: Uri) {
        context.contentResolver.takePersistableUriPermission(treeUri, persistableFlags)
        settingsRepository.setDownloadFolderUri(treeUri.toString())
    }

    suspend fun current(): DownloadFolderState = stateFor(settingsRepository.observeDownloadFolderUri().first())

    /** For the settings screen: re-evaluated whenever the stored folder changes. */
    fun observe(): Flow<DownloadFolderState> = settingsRepository.observeDownloadFolderUri().map(::stateFor)

    private fun stateFor(storedUri: String?): DownloadFolderState {
        if (storedUri == null) return DownloadFolderState.NotChosen
        val held =
            context.contentResolver.persistedUriPermissions.any { permission ->
                permission.uri.toString() == storedUri && permission.isReadPermission && permission.isWritePermission
            }
        return if (held) DownloadFolderState.Granted(storedUri) else DownloadFolderState.Revoked(storedUri)
    }
}
