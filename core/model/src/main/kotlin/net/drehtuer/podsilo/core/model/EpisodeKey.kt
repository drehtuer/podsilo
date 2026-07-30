// SPDX-License-Identifier: GPL-3.0-or-later

package net.drehtuer.podsilo.core.model

/**
 * The GPodder API identifies an episode action by `guid`, falling back to the enclosure URL
 * (`episode` field) when `guid` is absent (CLAUDE.md §5). [Episode.episodeKey] and the key used to
 * match incoming [net.drehtuer.podsilo.core.model.port.EpisodeAction]s must both go through this
 * single function, or actions from other gpodder clients (AntennaPod, RePod, ...) silently stop
 * lining up with local records.
 */
fun episodeKey(
    guid: String?,
    enclosureUrl: String,
): String = guid ?: enclosureUrl
