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
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.dp
import net.drehtuer.podsilo.core.ui.MinTouchTarget
import net.drehtuer.podsilo.core.ui.PodsiloIcon
import net.drehtuer.podsilo.core.ui.PodsiloIcons
import net.drehtuer.podsilo.core.ui.RowPadding

/**
 * S6 (`UI.adoc` §9). Two fields, the placeholder chips the engine actually knows, and a live
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
                modifier =
                    Modifier
                        .widthIn(max = 600.dp)
                        .verticalScroll(rememberScrollState())
                        .padding(RowPadding),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                TemplateFields(state, onEvent)

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
 * The two fields, and the chips that insert into whichever one has focus.
 *
 * The caret lives here, in [TextFieldValue] state, rather than being reconstructed from the view
 * model's `String` on every keystroke — that reconstruction is what used to throw it to position 0.
 * See [insertAtCursor] and [syncedFromState].
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun TemplateFields(
    state: NamingUiState,
    onEvent: (NamingEvent) -> Unit,
) {
    var folder by remember { mutableStateOf(TextFieldValue(state.folderTemplate)) }
    var file by remember { mutableStateOf(TextFieldValue(state.fileTemplate)) }
    // Which field a chip lands in. Defaults to the file template, which is the one people edit.
    var focused by remember { mutableStateOf(NamingField.FILE) }

    folder = syncedFromState(folder, state.folderTemplate)
    file = syncedFromState(file, state.fileTemplate)

    OutlinedTextField(
        value = folder,
        onValueChange = {
            folder = it
            onEvent(NamingEvent.FolderTemplateChanged(it.text))
        },
        label = { Text("Folder template") },
        singleLine = true,
        isError = state.errorFor(NamingField.FOLDER) != null,
        supportingText = state.errorFor(NamingField.FOLDER)?.let { { Text(it) } },
        modifier =
            Modifier
                .fillMaxWidth()
                .onFocusChanged { if (it.isFocused) focused = NamingField.FOLDER },
    )
    OutlinedTextField(
        value = file,
        onValueChange = {
            file = it
            onEvent(NamingEvent.FileTemplateChanged(it.text))
        },
        label = { Text("File template") },
        singleLine = true,
        isError = state.errorFor(NamingField.FILE) != null,
        supportingText = state.errorFor(NamingField.FILE)?.let { { Text(it) } },
        modifier =
            Modifier
                .fillMaxWidth()
                .onFocusChanged { if (it.isFocused) focused = NamingField.FILE },
    )

    PlaceholderChips(state.placeholders) { placeholder ->
        // Into the field the user is actually editing, at the caret — not appended to the end of
        // the file template regardless of focus, as it used to be.
        when (focused) {
            NamingField.FOLDER -> {
                folder = insertAtCursor(folder, placeholder)
                onEvent(NamingEvent.FolderTemplateChanged(folder.text))
            }
            NamingField.FILE -> {
                file = insertAtCursor(file, placeholder)
                onEvent(NamingEvent.FileTemplateChanged(file.text))
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun PlaceholderChips(
    placeholders: List<String>,
    onInsert: (String) -> Unit,
) {
    Text("Available placeholders", style = MaterialTheme.typography.labelMedium)
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        placeholders.forEach { placeholder ->
            AssistChip(
                onClick = { onInsert(placeholder) },
                label = { Text(placeholder) },
                modifier = Modifier.sizeIn(minHeight = MinTouchTarget),
            )
        }
    }

    // The extension's absence from the chip list is deliberate but was reported as confusing: the
    // preview grows a ".mp3" from nowhere. Say where it comes from (CLAUDE.md §6).
    Text(
        "The file extension is added automatically from the download — .mp3, .m4a, .opus and so " +
            "on — so templates do not include it.",
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

/** One real episode and three synthetic worst cases, all resolved by the engine (`UI.adoc` §9). */
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
