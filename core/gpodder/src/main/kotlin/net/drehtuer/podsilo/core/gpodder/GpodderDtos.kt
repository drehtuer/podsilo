// SPDX-License-Identifier: GPL-3.0-or-later

package net.drehtuer.podsilo.core.gpodder

import kotlinx.serialization.Serializable

/**
 * Wire DTOs for the Nextcloud GPodder API. Kept separate from `:core:model`'s domain types so the
 * serialization annotations and the API's quirks stay at the boundary -- see [GpodderDtoMapping]
 * for the translation.
 *
 * Field names and shapes verified against `thrillfall/nextcloud-gpodder`'s controllers/repositories
 * (the reference implementation CLAUDE.md section 5 says to infer the contract from) and
 * cross-checked against `kd2org/opodsync`. Differences between the two are handled here, not
 * pushed onto callers -- see `docs/decisions/0008`.
 */
@Serializable
internal data class SubscriptionsResponseDto(
    val add: List<String> = emptyList(),
    val remove: List<String> = emptyList(),
    val timestamp: Long = 0L,
)

@Serializable
internal data class EpisodeActionPageDto(
    val actions: List<EpisodeActionDto> = emptyList(),
    val timestamp: Long = 0L,
)

/**
 * @property action Case differs by server: `nextcloud-gpodder` upper-cases on write
 *   (`strtoupper` in `EpisodeActionReader::fromArray`), `opodsync` lower-cases
 *   (`strtolower` in `API.php`). Compared case-insensitively when mapping.
 * @property started `nextcloud-gpodder` defaults these to `-1` rather than omitting them
 *   (`$episodeAction["started"] ?? -1`); `opodsync` omits them entirely. Both are normalised to
 *   `null` when mapping to the domain type.
 * @property timestamp ISO-8601. `nextcloud-gpodder` emits an offset (PHP `format("c")` ->
 *   `2021-10-06T11:49:23+00:00`), `opodsync` emits a trailing `Z`, and the (stale) API README
 *   shows a bare local-time form. All three are parsed -- see `docs/architecture.adoc` §6.
 */
@Serializable
internal data class EpisodeActionDto(
    val podcast: String,
    val episode: String,
    val guid: String? = null,
    val action: String,
    val timestamp: String? = null,
    val started: Int? = null,
    val position: Int? = null,
    val total: Int? = null,
)
