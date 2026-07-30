// SPDX-License-Identifier: GPL-3.0-or-later

package net.drehtuer.podsilo.core.model.port

import net.drehtuer.podsilo.core.model.Episode
import net.drehtuer.podsilo.core.model.Feed

/**
 * Port for template resolution, sanitisation, truncation, and collision suffixing (CLAUDE.md §6).
 * Implemented in `:core:naming` (pure JVM). `:core:download` calls [resolve] and otherwise
 * contains zero string-sanitisation logic of its own (`docs/architecture.md` §11).
 */
interface NamingTemplateEngine {
    fun resolve(
        feed: Feed,
        episode: Episode,
        folderTemplate: String,
        fileTemplate: String,
    ): ResolvedName
}

/** [extension] is resolved separately (`Content-Type` → URL → `.mp3` fallback) and appended by the caller. */
data class ResolvedName(
    val folder: String,
    val fileNameWithoutExtension: String,
    val extension: String,
)
