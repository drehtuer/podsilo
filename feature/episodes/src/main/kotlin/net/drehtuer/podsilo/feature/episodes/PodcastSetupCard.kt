// SPDX-License-Identifier: GPL-3.0-or-later

package net.drehtuer.podsilo.feature.episodes

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.sizeIn
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp

/**
 * The first-run checklist (`docs/UI.md` §4). Steps are shown in order with a live ✓/○; step 3 is
 * explicitly optional, which is why it never keeps the card open.
 */
@Composable
internal fun SetupCard(
    setup: SetupChecklist,
    onEvent: (PodcastListEvent) -> Unit,
) {
    Column(
        modifier =
            Modifier
                .fillMaxWidth()
                .background(MaterialTheme.colorScheme.surfaceVariant)
                .padding(RowPadding),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Text("Finish setting up", style = MaterialTheme.typography.titleSmall)
        SetupStep(
            done = setup.nextcloudConnected,
            label = "1. Connect Nextcloud",
            detail = setup.instanceLabel,
            actionLabel = "Connect".takeUnless { setup.nextcloudConnected },
            onAction = { onEvent(PodcastListEvent.ConnectNextcloudClicked) },
        )
        SetupStep(
            done = setup.folderState == FolderState.GRANTED,
            label = "2. Choose a download folder",
            // A revoked grant is not the same as never having chosen: the wording has to say the
            // folder is gone, or the user re-picks the one that just failed (CLAUDE.md §11).
            detail = "the folder you chose is no longer available".takeIf { setup.folderState == FolderState.REVOKED },
            actionLabel = "Choose folder".takeUnless { setup.folderState == FolderState.GRANTED },
            onAction = { onEvent(PodcastListEvent.ChooseFolderClicked) },
        )
        SetupStep(
            done = true,
            label = "3. Check file naming (optional)",
            detail = setup.namingPreview,
            actionLabel = "Naming",
            onAction = { onEvent(PodcastListEvent.NamingClicked) },
        )
    }
}

@Composable
private fun SetupStep(
    done: Boolean,
    label: String,
    detail: String?,
    actionLabel: String?,
    onAction: () -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = if (done) "✓ $label" else "○ $label",
                style = MaterialTheme.typography.bodyMedium,
            )
            detail?.let {
                Text(
                    text = it,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        actionLabel?.let {
            TextButton(onClick = onAction, modifier = Modifier.sizeIn(minHeight = MinTouchTarget)) {
                Text(it)
            }
        }
    }
}
