// SPDX-License-Identifier: GPL-3.0-or-later

package net.drehtuer.podsilo.feature.settings

import kotlinx.coroutines.flow.Flow
import net.drehtuer.podsilo.core.model.port.BulkScope
import net.drehtuer.podsilo.core.model.port.OlderThan
import net.drehtuer.podsilo.core.model.port.SwipeAction
import net.drehtuer.podsilo.core.model.port.SwipeDirection
import net.drehtuer.podsilo.core.model.port.SwipeMapping
import net.drehtuer.podsilo.core.model.port.ThemePreference
import java.time.Instant

/**
 * S4 — settings (`docs/UI_interface.md` §5).
 *
 * **There is no Save button**: every control commits on change (`docs/UI.md` §7), so this state is a
 * projection of what is already persisted rather than a form buffer. The one exception is
 * [pendingBulk], which exists precisely because that operation is *not* commit-on-change — it writes
 * `PLAY` actions to a shared log and cannot be undone in bulk, so it is named and confirmed first.
 */
data class SettingsUiState(
    val nextcloud: NextcloudUi = NextcloudUi(),
    val downloadFolder: FolderUi = FolderUi(),
    val namingSummary: String = "",
    val allowMobileData: Boolean = false,
    val swipeMapping: SwipeMapping = SwipeMapping(),
    val markOldOlderThan: OlderThan = OlderThan.OFF,
    val theme: ThemePreference = ThemePreference.SYSTEM,
    val errorLogCount: Int = 0,
    val version: String = "",
    val build: String = "",
    val pendingBulk: BulkConfirmation? = null,
    /** Shown before the file picker opens, because a restore cannot be undone. */
    val restoreConfirmationVisible: Boolean = false,
    /** Zipping or restoring. Both backup rows disable while it runs, so neither can be re-entered. */
    val archiveBusy: Boolean = false,
)

/**
 * @property instanceUrl `null` renders an **empty** value area, not a placeholder, and the row is
 *   not tappable (`docs/UI.md` §7).
 * @property outboxDepth rows still to push. Shown next to the last sync because "10 min ago" alone
 *   cannot distinguish "nothing to do" from "three things stuck".
 */
data class NextcloudUi(
    val instanceUrl: String? = null,
    val loginName: String? = null,
    val connectedAt: Instant? = null,
    val lastSyncAt: Instant? = null,
    val outboxDepth: Int = 0,
) {
    val isConnected: Boolean get() = instanceUrl != null
}

/** [label] is the folder's own name from the picker; a tree URI is never shown as if it were a path. */
data class FolderUi(
    val label: String? = null,
    val state: FolderState = FolderState.NOT_CHOSEN,
)

/** Mirrors `DownloadFolderAccess`'s three states without dragging `:core:download` into the UI. */
enum class FolderState { NOT_CHOSEN, GRANTED, REVOKED }

/**
 * The preview dialog's contents — mandatory, not decoration (`docs/decisions/0013`).
 *
 * A bulk *mark as played* reaches the shared action log and other clients act on it, so the count
 * and the per-feed breakdown are named **before** anything is written, and the dialog says in words
 * that the state goes to Nextcloud.
 *
 * @property scope carried so [SettingsEvent.BulkConfirmed] writes exactly the set that was counted,
 *   rather than re-deriving it from a control the user may have changed meanwhile.
 */
data class BulkConfirmation(
    val scope: BulkScope,
    val perFeed: List<FeedCount>,
) {
    val count: Int get() = perFeed.sumOf { it.count }
}

data class FeedCount(
    val feedTitle: String,
    val count: Int,
)

sealed interface SettingsEvent {
    data object ConnectClicked : SettingsEvent

    data object DisconnectClicked : SettingsEvent

    data object ChooseFolderClicked : SettingsEvent

    data object NamingClicked : SettingsEvent

    data object LastSyncClicked : SettingsEvent

    data object ErrorLogClicked : SettingsEvent

    data class MobileDataChanged(
        val allowed: Boolean,
    ) : SettingsEvent

    data class SwipeChanged(
        val direction: SwipeDirection,
        val action: SwipeAction,
    ) : SettingsEvent

    data class OlderThanChanged(
        val value: OlderThan,
    ) : SettingsEvent

    /** Opens the preview. Writes nothing — only [BulkConfirmed] does. */
    data class BulkPreviewRequested(
        val scope: BulkScope,
    ) : SettingsEvent

    data object BulkConfirmed : SettingsEvent

    data object BulkCancelled : SettingsEvent

    data class ThemeChanged(
        val theme: ThemePreference,
    ) : SettingsEvent

    data object SourceCodeClicked : SettingsEvent

    data object ExportDatabaseClicked : SettingsEvent

    /** Opens the warning. The picker only follows once it is confirmed. */
    data object RestoreDatabaseClicked : SettingsEvent

    data object RestoreConfirmed : SettingsEvent

    data object RestoreCancelled : SettingsEvent

    /**
     * The host came back from the SAF picker. [uri] is a document the user chose in *this* app
     * session; a `null` would mean they cancelled, which the host swallows rather than sending on.
     */
    data class BackupDestinationChosen(
        val uri: String,
    ) : SettingsEvent

    data class BackupSourceChosen(
        val uri: String,
    ) : SettingsEvent
}

sealed interface SettingsEffect {
    data object OpenConnect : SettingsEffect

    data object OpenNaming : SettingsEffect

    data object OpenActivity : SettingsEffect

    data object OpenErrorLog : SettingsEffect

    /** The SAF picker — an Activity result, so only the host can launch it. */
    data object ChooseFolder : SettingsEffect

    /** The host opens it in a browser; S4 has no other reason to leave the app. */
    data class OpenUrl(
        val url: String,
    ) : SettingsEffect

    /** `CreateDocument`, with the name the file should be offered under. */
    data class CreateBackupFile(
        val suggestedName: String,
    ) : SettingsEffect

    /** `OpenDocument`, filtered to zips. Only emitted after the restore warning is confirmed. */
    data object OpenBackupFile : SettingsEffect

    data class ShowMessage(
        val text: String,
    ) : SettingsEffect
}

/**
 * How many entries the error log holds, and how deep the outbox is.
 *
 * A port rather than the repositories themselves because both counts are one integer each, and
 * `:feature:settings` should not take a dependency on the ledger and the log just to render two
 * row subtitles.
 */
interface SettingsCounts {
    fun observeErrorLogCount(): Flow<Int>

    fun observeOutboxDepth(): Flow<Int>
}

/** Where the source lives. GPL-3.0 obliges us to be able to point at it; this is that pointer. */
const val PODSILO_REPOSITORY_URL: String = "https://github.com/drehtuer/podsilo"
