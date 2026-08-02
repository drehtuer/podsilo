// SPDX-License-Identifier: GPL-3.0-or-later

package net.drehtuer.podsilo.feature.settings

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import net.drehtuer.podsilo.core.ui.MinTouchTarget
import net.drehtuer.podsilo.core.ui.PodsiloIcon
import net.drehtuer.podsilo.core.ui.PodsiloIcons
import net.drehtuer.podsilo.core.ui.RowPadding
import java.time.Duration
import java.time.Instant

/**
 * The shared pieces every group in S4 is built from, plus the two label mappings. Split from the
 * screen because the screen's job is which rows exist in which order, and this file's is what a row
 * looks like.
 */
@Composable
internal fun GroupHeader(text: String) {
    HorizontalDivider()
    Text(
        text = text,
        style = MaterialTheme.typography.labelMedium,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(horizontal = RowPadding, vertical = 8.dp),
    )
}

@Composable
internal fun SettingsRow(
    title: String,
    subtitle: String?,
    onClick: (() -> Unit)?,
    isWarning: Boolean = false,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(
            modifier = Modifier.weight(1f),
        ) {
            SettingsRowBody(title, subtitle, onClick, isWarning)
        }
        // The affordance, not a control — the whole row is the tap target (docs/UI.md §18).
        if (onClick != null) PodsiloIcon(PodsiloIcons.ChevronRight, contentDescription = null)
    }
}

@Composable
private fun SettingsRowBody(
    title: String,
    subtitle: String?,
    onClick: (() -> Unit)?,
    isWarning: Boolean = false,
) {
    Column(
        modifier =
            Modifier
                .fillMaxWidth()
                .heightIn(min = MinTouchTarget)
                .let { if (onClick != null) it.clickable(onClick = onClick) else it }
                .padding(horizontal = RowPadding, vertical = 8.dp),
    ) {
        Text(title, style = MaterialTheme.typography.bodyLarge)
        subtitle?.let {
            Text(
                text = it,
                style = MaterialTheme.typography.bodySmall,
                color =
                    if (isWarning) {
                        MaterialTheme.colorScheme.error
                    } else {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    },
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

@Composable
internal fun SwitchRow(
    title: String,
    checked: Boolean,
    onChange: (Boolean) -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth().heightIn(min = MinTouchTarget).padding(horizontal = RowPadding),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Text(title, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
        Switch(checked = checked, onCheckedChange = onChange)
    }
}

/** Coarse on purpose, and shared with S1's row: nobody needs seconds here. */
internal fun relativeTime(
    then: Instant,
    now: Instant,
): String {
    val minutes =
        java.time.Duration
            .between(then, now)
            .toMinutes()
    return when {
        minutes < 1 -> "just now"
        minutes < MINUTES_PER_HOUR -> "$minutes min ago"
        minutes < MINUTES_PER_DAY -> "${minutes / MINUTES_PER_HOUR} h ago"

        else -> "${minutes / MINUTES_PER_DAY} d ago"
    }
}

private const val MINUTES_PER_HOUR = 60
private const val MINUTES_PER_DAY = 60 * 24
