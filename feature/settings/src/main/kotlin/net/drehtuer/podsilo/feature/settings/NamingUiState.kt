// SPDX-License-Identifier: GPL-3.0-or-later

package net.drehtuer.podsilo.feature.settings

import net.drehtuer.podsilo.core.model.port.NamingSettings

/**
 * S6 — the naming template editor (`UI.adoc` §B5).
 *
 * @property placeholders exactly the set `DefaultNamingTemplateEngine` resolves. `{ext}` is
 *   deliberately absent: the extension is appended after resolution and is not a variable, so
 *   offering a chip for it would put the literal text `{ext}` in a filename (CLAUDE.md §6).
 */
data class NamingUiState(
    val folderTemplate: String = NamingSettings.DEFAULT_FOLDER_TEMPLATE,
    val fileTemplate: String = NamingSettings.DEFAULT_FILE_TEMPLATE,
    val validation: Validation = Validation.Valid,
    val previews: List<NamingPreviewLine> = emptyList(),
    val placeholders: List<String> = PLACEHOLDERS,
) {
    sealed interface Validation {
        data object Valid : Validation

        /** Shown under the field; an invalid template is never persisted. */
        data class Invalid(
            val field: NamingField,
            val reason: String,
        ) : Validation
    }

    private companion object {
        val PLACEHOLDERS = listOf("{podcast}", "{title}", "{date}", "{description}", "{guid_short}")
    }
}

enum class NamingField { FOLDER, FILE }

data class NamingPreviewLine(
    val case: PreviewCase,
    val resolved: String,
)

/** The one real line plus the three that catch a template which only looks right (`UI.adoc` §9). */
enum class PreviewCase { RECENT_EPISODE, MISSING_DATE, OVERLONG_TITLE, ILLEGAL_CHARACTERS }

sealed interface NamingEvent {
    data class FolderTemplateChanged(
        val value: String,
    ) : NamingEvent

    data class FileTemplateChanged(
        val value: String,
    ) : NamingEvent

    data object ResetToDefault : NamingEvent
}
