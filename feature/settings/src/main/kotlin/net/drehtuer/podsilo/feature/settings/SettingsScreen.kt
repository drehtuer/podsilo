// SPDX-License-Identifier: GPL-3.0-or-later

package net.drehtuer.podsilo.feature.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.sizeIn
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import net.drehtuer.podsilo.core.model.port.BulkScope
import net.drehtuer.podsilo.core.model.port.BulkScopeKind
import net.drehtuer.podsilo.core.model.port.OlderThan
import net.drehtuer.podsilo.core.model.port.SwipeAction
import net.drehtuer.podsilo.core.model.port.SwipeDirection
import net.drehtuer.podsilo.core.model.port.ThemePreference
import net.drehtuer.podsilo.core.ui.LogoGap
import net.drehtuer.podsilo.core.ui.MaxContentWidth
import net.drehtuer.podsilo.core.ui.MinTouchTarget
import net.drehtuer.podsilo.core.ui.PodsiloIcon
import net.drehtuer.podsilo.core.ui.PodsiloIcons
import net.drehtuer.podsilo.core.ui.PodsiloLockup
import net.drehtuer.podsilo.core.ui.RowPadding
import java.time.Instant

/**
 * S4 — settings (`docs/UI.md` §7). A plain scrolling list of grouped rows, reached from S1's gear.
 *
 * **No Save button**: every control commits on change, so this renders [state] and emits [onEvent]
 * with nothing held locally. The one thing that does not commit immediately is the bulk *mark as
 * played*, which opens a preview first — see [BulkPreviewDialog].
 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun SettingsScreen(
    state: SettingsUiState,
    onEvent: (SettingsEvent) -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    now: Instant = Instant.now(),
) {
    Scaffold(
        modifier = modifier,
        topBar = {
            TopAppBar(
                title = { Text("Settings") },
                navigationIcon = {
                    IconButton(onClick = onBack, modifier = Modifier.sizeIn(minHeight = MinTouchTarget)) {
                        PodsiloIcon(PodsiloIcons.Back, contentDescription = "Back")
                    }
                },
            )
        },
    ) { padding ->
        Column(
            modifier = Modifier.padding(padding).fillMaxSize(),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Column(
                modifier = Modifier.widthIn(max = MaxContentWidth).verticalScroll(rememberScrollState()),
            ) {
                NextcloudGroup(state.nextcloud, now, state.directionalSyncBusy, onEvent)
                DownloadsGroup(state, onEvent)
                TriageGroup(state, onEvent)
                AppearanceGroup(state.theme, onEvent)
                BackupGroup(state, onEvent)
                TroubleshootingGroup(state.errorLogCount, onEvent)
                AboutGroup(state.version, state.build, onEvent)
            }
        }
    }

    state.pendingBulk?.let { BulkPreviewDialog(it, onEvent) }
    if (state.restoreConfirmationVisible) RestoreWarningDialog(onEvent)
    state.pendingDirectionalSync?.let { DirectionalSyncDialog(it, onEvent) }
}

/**
 * Backup and restore of the local database (`docs/UI.md` §7).
 *
 * The subtitles name what is actually at stake. Most of the database can be rebuilt — feeds come
 * from Nextcloud, episodes from the RSS — but the ledger is the app's own memory of what has been
 * handled, and the parts of it Nextcloud never sees (`DOWNLOAD` actions, which the server discards,
 * and anything still in the outbox) exist on this phone and nowhere else.
 */
@Composable
private fun BackupGroup(
    state: SettingsUiState,
    onEvent: (SettingsEvent) -> Unit,
) {
    GroupHeader("BACKUP")
    SettingsRow(
        title = "Export database",
        subtitle = "Save podcasts, episodes and download history as a zip",
        enabled = !state.archiveBusy,
        onClick = { onEvent(SettingsEvent.ExportDatabaseClicked) },
    )
    // A restore is refused until Nextcloud is connected, by the author's rule. The reason is
    // sequencing: the archive deliberately carries no credentials, so restoring first leaves the
    // ledger sitting behind a "not configured" screen that shows none of it — which is exactly how
    // it read on the Pixel 5. Connecting first means the restored ledger lands somewhere that can
    // display it, and the very next sync reconciles it against the server.
    val restorable = state.nextcloud.isConnected
    SettingsRow(
        title = "Restore from backup",
        subtitle =
            if (restorable) {
                "Replaces everything currently on this device"
            } else {
                "Connect Nextcloud first"
            },
        isWarning = !restorable,
        enabled = restorable && !state.archiveBusy,
        onClick = { onEvent(SettingsEvent.RestoreDatabaseClicked) },
    )
}

@Composable
private fun NextcloudGroup(
    nextcloud: NextcloudUi,
    now: Instant,
    directionalSyncBusy: Boolean,
    onEvent: (SettingsEvent) -> Unit,
) {
    GroupHeader("NEXTCLOUD")
    SettingsRow(
        title = "Instance",
        // Empty, not a placeholder, and the row is not tappable when nothing is set (§7).
        subtitle = nextcloud.instanceUrl,
        onClick = null,
    )
    if (nextcloud.isConnected) {
        SettingsRow(title = "Account", subtitle = nextcloud.loginName, onClick = null)
        SettingsRow(
            title = "Last sync",
            subtitle = lastSyncLine(nextcloud, now),
            onClick = { onEvent(SettingsEvent.LastSyncClicked) },
        )
        // The two directional passes sit under *Last sync*, which already answers "when did this
        // happen" — so "make it happen, in this direction" belongs beside it (`docs/UI.md` §7).
        // Absent entirely when no account is connected, rather than disabled: there is nothing to
        // apply and nowhere to send.
        SettingsRow(
            title = "Apply Nextcloud's state here",
            subtitle = "Marks episodes played here if they are played in Nextcloud. Nothing is unmarked.",
            onClick =
                { onEvent(SettingsEvent.DirectionalSyncRequested(SyncDirection.PULL)) }
                    .takeUnless { directionalSyncBusy },
        )
        SettingsRow(
            title = "Send this device's state to Nextcloud",
            subtitle = "Re-sends every decision made here, including ones already sent.",
            onClick =
                { onEvent(SettingsEvent.DirectionalSyncRequested(SyncDirection.PUSH)) }
                    .takeUnless { directionalSyncBusy },
        )
    }
    Row(modifier = Modifier.fillMaxWidth().padding(horizontal = RowPadding)) {
        TextButton(
            onClick = { onEvent(SettingsEvent.ConnectClicked) },
            modifier = Modifier.sizeIn(minHeight = MinTouchTarget),
        ) {
            Text(if (nextcloud.isConnected) "Change Nextcloud instance" else "Connect Nextcloud")
        }
        if (nextcloud.isConnected) {
            TextButton(
                onClick = { onEvent(SettingsEvent.DisconnectClicked) },
                modifier = Modifier.sizeIn(minHeight = MinTouchTarget),
            ) { Text("Disconnect") }
        }
    }
}

/**
 * "10 min ago" alone cannot distinguish "nothing to do" from "three things stuck", which is exactly
 * what a user checking this row wants to know (`docs/UI.md` §7).
 */
internal fun lastSyncLine(
    nextcloud: NextcloudUi,
    now: Instant,
): String {
    val when0 = nextcloud.lastSyncAt?.let { relativeTime(it, now) } ?: "never"
    return when (nextcloud.outboxDepth) {
        0 -> when0
        1 -> "$when0 · 1 action pending"
        else -> "$when0 · ${nextcloud.outboxDepth} actions pending"
    }
}

@Composable
private fun DownloadsGroup(
    state: SettingsUiState,
    onEvent: (SettingsEvent) -> Unit,
) {
    GroupHeader("DOWNLOADS")
    SettingsRow(
        title = "Download folder",
        // A revoked grant says so in words: re-picking the folder that just failed is the mistake
        // this wording prevents (CLAUDE.md §11).
        subtitle =
            when (state.downloadFolder.state) {
                FolderState.NOT_CHOSEN -> "not chosen"
                FolderState.REVOKED -> "not available — choose it again"
                FolderState.GRANTED -> state.downloadFolder.label
            },
        isWarning = state.downloadFolder.state == FolderState.REVOKED,
        onClick = { onEvent(SettingsEvent.ChooseFolderClicked) },
    )
    SettingsRow(
        title = "File naming",
        subtitle = state.namingSummary,
        onClick = { onEvent(SettingsEvent.NamingClicked) },
    )
    SwitchRow(
        // Named as a constraint, not a rule, so it cannot be mistaken for auto-download (§7).
        title = "Download over mobile data",
        checked = state.allowMobileData,
        onChange = { onEvent(SettingsEvent.MobileDataChanged(it)) },
    )
}

@Composable
private fun TriageGroup(
    state: SettingsUiState,
    onEvent: (SettingsEvent) -> Unit,
) {
    GroupHeader("TRIAGE")
    SwipeRow("Swipe right", SwipeDirection.RIGHT, state.swipeMapping.right, onEvent)
    SwipeRow("Swipe left", SwipeDirection.LEFT, state.swipeMapping.left, onEvent)

    SettingsRow(title = "Mark old episodes as played", subtitle = "Older than", onClick = null)
    // FlowRow, not Row: five chips do not fit a phone's width, and the overflowing one wrapped to
    // one letter per line on the first device run.
    FlowRow(
        modifier = Modifier.fillMaxWidth().padding(horizontal = RowPadding),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        OlderThan.entries.forEach { option ->
            FilterChip(
                selected = option == state.markOldOlderThan,
                onClick = { onEvent(SettingsEvent.OlderThanChanged(option)) },
                label = { Text(option.label) },
                modifier = Modifier.sizeIn(minHeight = MinTouchTarget),
            )
        }
    }
    TextButton(
        onClick = {
            onEvent(SettingsEvent.BulkPreviewRequested(BulkScope(kind = BulkScopeKind.OLDER_THAN)))
        },
        enabled = state.markOldOlderThan != OlderThan.OFF,
        modifier = Modifier.padding(horizontal = RowPadding).sizeIn(minHeight = MinTouchTarget),
    ) { Text("Preview & apply") }

    SettingsRow(
        title = "Mark ALL episodes as played",
        subtitle = "Every undecided episode in every podcast",
        onClick = null,
    )
    TextButton(
        onClick = {
            onEvent(SettingsEvent.BulkPreviewRequested(BulkScope(kind = BulkScopeKind.ALL_UNDECIDED)))
        },
        modifier = Modifier.padding(horizontal = RowPadding).sizeIn(minHeight = MinTouchTarget),
    ) { Text("Preview & apply all") }
}

internal val OlderThan.label: String
    get() =
        when (this) {
            OlderThan.OFF -> "Off"
            OlderThan.MONTH_1 -> "1 month"
            OlderThan.MONTH_3 -> "3 months"
            OlderThan.MONTH_6 -> "6 months"
            OlderThan.YEAR_1 -> "1 year"
        }

@Composable
private fun SwipeRow(
    title: String,
    direction: SwipeDirection,
    selected: SwipeAction,
    onEvent: (SettingsEvent) -> Unit,
) {
    Column(modifier = Modifier.fillMaxWidth().padding(horizontal = RowPadding, vertical = 4.dp)) {
        Text(title, style = MaterialTheme.typography.bodyLarge)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            SwipeAction.entries.forEach { action ->
                FilterChip(
                    selected = action == selected,
                    onClick = { onEvent(SettingsEvent.SwipeChanged(direction, action)) },
                    label = { Text(action.label) },
                    modifier = Modifier.sizeIn(minHeight = MinTouchTarget),
                )
            }
        }
    }
}

internal val SwipeAction.label: String
    get() =
        when (this) {
            SwipeAction.DOWNLOAD -> "Download"
            SwipeAction.MARK_AS_PLAYED -> "Mark as played"
            SwipeAction.NONE -> "Nothing"
        }

@Composable
private fun AppearanceGroup(
    theme: ThemePreference,
    onEvent: (SettingsEvent) -> Unit,
) {
    GroupHeader("APPEARANCE")
    FlowRow(
        modifier = Modifier.fillMaxWidth().padding(horizontal = RowPadding),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        ThemePreference.entries.forEach { option ->
            FilterChip(
                selected = option == theme,
                onClick = { onEvent(SettingsEvent.ThemeChanged(option)) },
                label = { Text(option.name.lowercase().replaceFirstChar { it.uppercase() }) },
                modifier = Modifier.sizeIn(minHeight = MinTouchTarget),
            )
        }
    }
}

@Composable
private fun TroubleshootingGroup(
    errorLogCount: Int,
    onEvent: (SettingsEvent) -> Unit,
) {
    GroupHeader("TROUBLESHOOTING")
    SettingsRow(
        title = "Error log",
        subtitle = if (errorLogCount == 1) "1 entry" else "$errorLogCount entries",
        onClick = { onEvent(SettingsEvent.ErrorLogClicked) },
    )
}

/** The source is the licence in practice: GPL-3.0 means little without somewhere to get the code. */
@Composable
private fun AboutGroup(
    version: String,
    build: String,
    onEvent: (SettingsEvent) -> Unit,
) {
    GroupHeader("ABOUT")
    // `docs/UI.md` §C4.3: the horizontal lockup, flush left on the surface ground, no card and no
    // frame. One of only three in-app placements of the mark, and the only one that is purely the
    // app saying what it is.
    PodsiloLockup(modifier = Modifier.padding(horizontal = RowPadding, vertical = LogoGap))
    SettingsRow(title = "Version $version", subtitle = "GPL-3.0-or-later", onClick = null)
    // Build number, timestamp and commit. `versionName` alone cannot answer "is this the build I
    // just installed?", which is the question actually being asked of this screen during testing —
    // 0.1.0 stays 0.1.0 across every sideload of the day.
    SettingsRow(title = "Build", subtitle = build, onClick = null)
    SettingsRow(
        title = "Source code",
        subtitle = PODSILO_REPOSITORY_URL,
        onClick = { onEvent(SettingsEvent.SourceCodeClicked) },
    )
}
