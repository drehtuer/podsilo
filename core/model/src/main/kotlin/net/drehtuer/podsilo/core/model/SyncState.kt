// SPDX-License-Identifier: GPL-3.0-or-later

package net.drehtuer.podsilo.core.model

/**
 * Single-row cursor for incremental episode-action sync.
 *
 * @property lastEpisodeActionSyncTs **Unix seconds**, persisted verbatim from the GPodder
 *   `episode_action` response's top-level `timestamp` and sent back as the next `since`. Never
 *   computed from local device time — clock skew between the server and this device would
 *   silently drop or duplicate actions (CLAUDE.md §11).
 * @property deviceId Generated once (e.g. `UUID.randomUUID()`) on first run and persisted forever.
 */
data class SyncState(
    val lastEpisodeActionSyncTs: Long,
    val deviceId: String,
)
