// SPDX-License-Identifier: GPL-3.0-or-later

package net.drehtuer.podsilo.core.gpodder

import net.drehtuer.podsilo.core.model.port.EpisodeAction
import net.drehtuer.podsilo.core.model.port.EpisodeActionPage
import net.drehtuer.podsilo.core.model.port.EpisodeActionType
import net.drehtuer.podsilo.core.model.port.SubscriptionDelta

/**
 * `nextcloud-gpodder` writes `-1` into `started`/`position`/`total` when a client didn't supply
 * them (`$episodeAction["started"] ?? -1`), rather than omitting the field. `opodsync` omits them
 * instead. Both mean "absent", so `-1` is normalised to `null` at this boundary and never reaches
 * the domain type.
 */
private const val ABSENT_PLAYBACK_SENTINEL = -1

internal fun SubscriptionsResponseDto.toDomain() =
    SubscriptionDelta(
        add = add,
        remove = remove,
        timestamp = timestamp,
    )

internal fun EpisodeActionPageDto.toDomain() =
    EpisodeActionPage(
        // An unrecognised action type is dropped rather than failing the whole page: the log is shared
        // with other clients, and a future/unknown action type must not break this device's sync.
        actions = actions.mapNotNull { it.toDomainOrNull() },
        timestamp = timestamp,
    )

internal fun EpisodeActionDto.toDomainOrNull(): EpisodeAction? {
    val actionType = parseActionType(action) ?: return null
    return EpisodeAction(
        podcast = podcast,
        episode = episode,
        guid = guid,
        action = actionType,
        timestamp = timestamp.orEmpty(),
        started = started.normalisePlaybackValue(),
        position = position.normalisePlaybackValue(),
        total = total.normalisePlaybackValue(),
    )
}

internal fun EpisodeAction.toDto() =
    EpisodeActionDto(
        podcast = podcast,
        episode = episode,
        guid = guid,
        // Upper-case on the wire: nextcloud-gpodder upper-cases on write anyway, and opodsync
        // lower-cases whatever it receives, so both are satisfied by sending a consistent form.
        action = action.name,
        timestamp = timestamp,
        started = started,
        position = position,
        total = total,
    )

/** Case-insensitive: `nextcloud-gpodder` stores upper-case, `opodsync` lower-case. */
private fun parseActionType(raw: String): EpisodeActionType? =
    EpisodeActionType.entries.firstOrNull { it.name.equals(raw.trim(), ignoreCase = true) }

private fun Int?.normalisePlaybackValue(): Int? = this?.takeIf { it != ABSENT_PLAYBACK_SENTINEL }
