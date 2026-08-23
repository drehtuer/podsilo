// SPDX-License-Identifier: GPL-3.0-or-later

package net.drehtuer.podsilo.ui.errorlog

import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
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
import net.drehtuer.podsilo.core.model.port.LogCategory
import net.drehtuer.podsilo.core.model.port.LogEntry
import net.drehtuer.podsilo.core.ui.MaxContentWidth
import net.drehtuer.podsilo.core.ui.MinTouchTarget
import net.drehtuer.podsilo.core.ui.PodsiloIcon
import net.drehtuer.podsilo.core.ui.PodsiloIcons
import net.drehtuer.podsilo.core.ui.RowPadding
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/**
 * S8 — the error log (`UI.adoc` §11).
 *
 * Read-only and device-local. Every entry leads with a **plain-language sentence**; the technical
 * half is collapsed behind *show technical detail* and is what gets pasted into a bug report.
 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun ErrorLogScreen(
    state: ErrorLogUiState,
    onEvent: (ErrorLogEvent) -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    zone: ZoneId = ZoneId.systemDefault(),
) {
    Scaffold(
        modifier = modifier,
        topBar = {
            TopAppBar(
                title = { Text("Error log") },
                navigationIcon = {
                    IconButton(onClick = onBack, modifier = Modifier.sizeIn(minHeight = MinTouchTarget)) {
                        PodsiloIcon(PodsiloIcons.Back, contentDescription = "Back")
                    }
                },
                actions = {
                    // Disabled, not hidden, when the log is empty — the affordance stays where the
                    // user learned it (UI.adoc §11).
                    IconButton(
                        onClick = { onEvent(ErrorLogEvent.CopyAllClicked) },
                        enabled = state.canClear,
                    ) { PodsiloIcon(PodsiloIcons.Copy, contentDescription = "Copy all") }
                    IconButton(
                        onClick = { onEvent(ErrorLogEvent.ShareClicked) },
                        enabled = state.canClear,
                    ) { PodsiloIcon(PodsiloIcons.Share, contentDescription = "Share") }
                    IconButton(
                        onClick = { onEvent(ErrorLogEvent.ClearRequested) },
                        enabled = state.canClear,
                    ) { PodsiloIcon(PodsiloIcons.Clear, contentDescription = "Clear") }
                },
            )
        },
    ) { padding ->
        Column(
            modifier = Modifier.padding(padding).fillMaxSize(),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Column(modifier = Modifier.widthIn(max = MaxContentWidth)) {
                CategoryChips(state.filter, onEvent)
                if (state.entries.isEmpty()) NothingFailed() else Entries(state, onEvent, zone)
            }
        }
    }

    if (state.pendingClear) ClearConfirmationDialog(state.entries.size, onEvent)
}

@Composable
private fun CategoryChips(
    selected: LogCategory?,
    onEvent: (ErrorLogEvent) -> Unit,
) {
    FlowRow(
        modifier = Modifier.fillMaxWidth().padding(horizontal = RowPadding),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        FilterChip(
            selected = selected == null,
            onClick = { onEvent(ErrorLogEvent.FilterChanged(null)) },
            label = { Text("All") },
            modifier = Modifier.sizeIn(minHeight = MinTouchTarget),
        )
        LogCategory.entries.forEach { category ->
            FilterChip(
                selected = category == selected,
                onClick = { onEvent(ErrorLogEvent.FilterChanged(category)) },
                label = { Text(category.label) },
                modifier = Modifier.sizeIn(minHeight = MinTouchTarget),
            )
        }
    }
}

internal val LogCategory.label: String
    get() = name.lowercase().replaceFirstChar { it.uppercase() }

@Composable
private fun NothingFailed() {
    Column(
        modifier = Modifier.fillMaxSize().padding(RowPadding),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        PodsiloIcon(PodsiloIcons.AllDone, contentDescription = null)
        Text("Nothing has failed.", style = MaterialTheme.typography.bodyLarge)
        Text(
            "Keeps the last 200 entries · nothing leaves the device",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun Entries(
    state: ErrorLogUiState,
    onEvent: (ErrorLogEvent) -> Unit,
    zone: ZoneId,
) {
    LazyColumn(modifier = Modifier.fillMaxSize()) {
        items(state.entries, key = { it.id }) { entry ->
            EntryRow(entry, entry.id in state.expanded, onEvent, zone)
            HorizontalDivider()
        }
        item {
            Text(
                "Keeps the last 200 entries · nothing leaves the device",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.fillMaxWidth().padding(RowPadding),
            )
        }
    }
}

@Composable
private fun EntryRow(
    entry: LogEntry,
    isExpanded: Boolean,
    onEvent: (ErrorLogEvent) -> Unit,
    zone: ZoneId,
) {
    Column(
        modifier =
            Modifier
                .fillMaxWidth()
                .clickable { onEvent(ErrorLogEvent.EntryClicked(entry.id)) }
                .padding(RowPadding),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            // A user-fixable input problem, or a condition the app is in — never interchangeable
            // (UI.adoc §18).
            PodsiloIcon(entry.category.icon, contentDescription = null)
            Text(entry.header(zone), style = MaterialTheme.typography.labelMedium)
        }
        // The plain sentence first: it is what the user reads (UI.adoc §11).
        Text(entry.message, style = MaterialTheme.typography.bodyMedium)
        entry.subtitle()?.let {
            Text(
                it,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        entry.detail?.let { detail ->
            TextButton(
                onClick = { onEvent(ErrorLogEvent.DetailToggled(entry.id)) },
                modifier = Modifier.sizeIn(minHeight = MinTouchTarget),
            ) {
                PodsiloIcon(PodsiloIcons.ChevronRight, contentDescription = null)
                Text(if (isExpanded) "Hide technical detail" else "Show technical detail")
            }
            if (isExpanded) {
                Text(
                    text = detail,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

/** `STORAGE` and `DOWNLOAD` are conditions; `SYNC`, `FEED` and `AUTH` usually point at a setting. */
internal val LogCategory.icon: Int
    get() =
        when (this) {
            LogCategory.STORAGE, LogCategory.DOWNLOAD -> PodsiloIcons.Warning
            LogCategory.SYNC, LogCategory.FEED, LogCategory.AUTH -> PodsiloIcons.InputError
        }

/** Absolute and local: a relative time is useless when correlating with a server log. */
internal fun LogEntry.header(zone: ZoneId): String {
    val stamp = ENTRY_FORMAT.withZone(zone).format(Instant.ofEpochMilli(at))
    val repeat = if (occurrences > 1) "  × $occurrences" else ""
    return "$stamp · ${category.name}$repeat"
}

/**
 * The collapse window, shown only when there *was* one — a single occurrence has no range worth
 * printing, and "first 21:14 · last 21:14" is noise.
 */
internal fun LogEntry.subtitle(): String? =
    when {
        occurrences > 1 -> "first ${SHORT_FORMAT.format(
            Instant.ofEpochMilli(firstSeenAt).atZone(ZoneId.systemDefault()),
        )}"
        feedUrl != null -> feedUrl
        else -> null
    }

private val ENTRY_FORMAT = DateTimeFormatter.ofPattern("dd MMM HH:mm")
private val SHORT_FORMAT = DateTimeFormatter.ofPattern("dd MMM HH:mm")

/**
 * Clearing always confirms (`UI.adoc` §11): the dialog names the count and says the log is
 * device-local, because there is no copy anywhere else and this is not undoable.
 */
@Composable
private fun ClearConfirmationDialog(
    count: Int,
    onEvent: (ErrorLogEvent) -> Unit,
) {
    AlertDialog(
        onDismissRequest = { onEvent(ErrorLogEvent.ClearCancelled) },
        title = { Text(if (count == 1) "Clear 1 log entry?" else "Clear all $count log entries?") },
        text = {
            Text("The log is only on this device — there is no copy anywhere else, and this can't be undone.")
        },
        confirmButton = {
            TextButton(onClick = { onEvent(ErrorLogEvent.ClearConfirmed) }) { Text("Clear") }
        },
        dismissButton = {
            TextButton(onClick = { onEvent(ErrorLogEvent.ClearCancelled) }) { Text("Cancel") }
        },
    )
}
