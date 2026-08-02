// SPDX-License-Identifier: GPL-3.0-or-later

package net.drehtuer.podsilo.feature.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.sizeIn
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AssistChip
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

/**
 * S6 (`docs/UI.md` §9). Two fields, the placeholder chips the engine actually knows, and a live
 * preview that calls the same `resolve()` a download does.
 *
 * Existing files are never renamed, and the screen says so — a template change applies to what
 * arrives next, not retroactively.
 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun NamingScreen(
    state: NamingUiState,
    onEvent: (NamingEvent) -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Scaffold(
        modifier = modifier,
        topBar = {
            TopAppBar(
                title = { Text("File naming") },
                navigationIcon = {
                    TextButton(onClick = onBack, modifier = Modifier.sizeIn(minHeight = MinTouchTarget)) {
                        Text("Back")
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
                modifier =
                    Modifier
                        .widthIn(max = 600.dp)
                        .verticalScroll(rememberScrollState())
                        .padding(RowPadding),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                OutlinedTextField(
                    value = state.folderTemplate,
                    onValueChange = { onEvent(NamingEvent.FolderTemplateChanged(it)) },
                    label = { Text("Folder template") },
                    singleLine = true,
                    isError = state.errorFor(NamingField.FOLDER) != null,
                    supportingText = state.errorFor(NamingField.FOLDER)?.let { { Text(it) } },
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    value = state.fileTemplate,
                    onValueChange = { onEvent(NamingEvent.FileTemplateChanged(it)) },
                    label = { Text("File template") },
                    singleLine = true,
                    isError = state.errorFor(NamingField.FILE) != null,
                    supportingText = state.errorFor(NamingField.FILE)?.let { { Text(it) } },
                    modifier = Modifier.fillMaxWidth(),
                )

                PlaceholderChips(state, onEvent)

                HorizontalDivider()
                PreviewSection(state.previews)

                Text(
                    "Files already in your folder are never renamed.",
                    style = MaterialTheme.typography.bodySmall,
                )
                TextButton(
                    onClick = { onEvent(NamingEvent.ResetToDefault) },
                    modifier = Modifier.sizeIn(minHeight = MinTouchTarget),
                ) { Text("Reset to default") }
            }
        }
    }
}

internal fun NamingUiState.errorFor(field: NamingField): String? =
    (validation as? NamingUiState.Validation.Invalid)?.takeIf { it.field == field }?.reason

internal val PreviewCase.label: String
    get() =
        when (this) {
            PreviewCase.RECENT_EPISODE -> "A recent episode"
            PreviewCase.MISSING_DATE -> "No publication date"
            PreviewCase.OVERLONG_TITLE -> "A very long title"
            PreviewCase.ILLEGAL_CHARACTERS -> "Awkward characters"
        }

/**
 * The chips are exactly the set `DefaultNamingTemplateEngine` resolves. Offering one it does not
 * know would put its literal text in a filename, which is why the list lives in the state rather
 * than being written out here (CLAUDE.md §6).
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun PlaceholderChips(
    state: NamingUiState,
    onEvent: (NamingEvent) -> Unit,
) {
    Text("Available placeholders", style = MaterialTheme.typography.labelMedium)
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        state.placeholders.forEach { placeholder ->
            AssistChip(
                onClick = { onEvent(NamingEvent.FileTemplateChanged(state.fileTemplate + placeholder)) },
                label = { Text(placeholder) },
                modifier = Modifier.sizeIn(minHeight = MinTouchTarget),
            )
        }
    }
}

/** One real episode and three synthetic worst cases, all resolved by the engine (`docs/UI.md` §9). */
@Composable
private fun PreviewSection(previews: List<NamingPreviewLine>) {
    Text("PREVIEW", style = MaterialTheme.typography.labelMedium)
    previews.forEach { line ->
        Column {
            Text(line.case.label, style = MaterialTheme.typography.labelSmall)
            Text(
                text = line.resolved,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}
