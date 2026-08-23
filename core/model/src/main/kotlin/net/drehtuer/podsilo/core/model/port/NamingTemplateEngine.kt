// SPDX-License-Identifier: GPL-3.0-or-later

package net.drehtuer.podsilo.core.model.port

import net.drehtuer.podsilo.core.model.Episode
import net.drehtuer.podsilo.core.model.Feed

/**
 * Port for template resolution, sanitisation, truncation, and collision suffixing (CLAUDE.md §6).
 * Implemented in `:core:naming` (pure JVM). `:core:download` calls [resolve] and otherwise
 * contains zero string-sanitisation logic of its own (`architecture.adoc` §11).
 */
interface NamingTemplateEngine {
    /**
     * @param contentType the downloaded response's `Content-Type`, which takes precedence over the
     *   enclosure URL when resolving the extension (CLAUDE.md §6 — the URL's extension is not
     *   trustworthy). `null` where no response exists: the settings live preview, and any caller
     *   naming an episode before fetching it.
     */
    fun resolve(
        feed: Feed,
        episode: Episode,
        folderTemplate: String,
        fileTemplate: String,
        contentType: String? = null,
    ): ResolvedName
}

/** [extension] is resolved separately (`Content-Type` → URL → `.mp3` fallback) and appended by the caller. */
data class ResolvedName(
    val folder: String,
    val fileNameWithoutExtension: String,
    val extension: String,
)
