// SPDX-License-Identifier: GPL-3.0-or-later

package net.drehtuer.podsilo.core.datastore

import kotlinx.serialization.Serializable
import net.drehtuer.podsilo.core.model.port.TitleCleanupRuleSetting

/**
 * Wire form for the persisted title-cleanup rules. The domain [TitleCleanupRuleSetting] lives in
 * Android-free `:core:model` and stays free of a serialisation dependency, so this mirror carries
 * the `@Serializable` annotation and the JSON round-trip happens entirely inside this module.
 */
@Serializable
internal data class SerializableTitleCleanupRule(
    val pattern: String,
    val replacement: String,
)

internal fun List<TitleCleanupRuleSetting>.toDto(): List<SerializableTitleCleanupRule> =
    map { SerializableTitleCleanupRule(it.pattern, it.replacement) }

internal fun List<SerializableTitleCleanupRule>.toDomain(): List<TitleCleanupRuleSetting> =
    map { TitleCleanupRuleSetting(it.pattern, it.replacement) }
