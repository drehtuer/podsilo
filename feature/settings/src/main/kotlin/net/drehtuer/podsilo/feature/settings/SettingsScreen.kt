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
import net.drehtuer.podsilo.core.ui.MaxContentWidth
import net.drehtuer.podsilo.core.ui.MinTouchTarget
import net.drehtuer.podsilo.core.ui.PodsiloIcon
import net.drehtuer.podsilo.core.ui.PodsiloIcons
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
                NextcloudGroup(state.nextcloud, now, onEvent)
                DownloadsGroup(state, onEvent)
                TriageGroup(state, onEvent)
                AppearanceGroup(state.theme, onEvent)
                TroubleshootingGroup(state.errorLogCount, onEvent)
                AboutGroup(state.version)
            }
        }
    }

    state.pendingBulk?.let { BulkPreviewDialog(it, onEvent) }
}

@Composable
private fun NextcloudGroup(
    nextcloud: NextcloudUi,
    now: Instant,
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

@Composable
private fun AboutGroup(version: String) {
    GroupHeader("ABOUT")
    SettingsRow(title = "Version $version", subtitle = "GPL-3.0-or-later", onClick = null)
}
