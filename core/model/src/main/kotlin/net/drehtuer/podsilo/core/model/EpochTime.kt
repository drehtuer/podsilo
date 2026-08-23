// SPDX-License-Identifier: GPL-3.0-or-later

package net.drehtuer.podsilo.core.model

import java.time.Duration
import java.time.Instant

/**
 * The single conversion between the `Long` epoch numbers every stored type uses and the
 * `java.time` values the UI renders (`architecture.adoc` §5).
 *
 * Storage keeps `Long`s — no Room type converters, no migration. UI state classes carry [Instant]
 * and [Duration], which is free at this project's `minSdk` and is already the time vocabulary of
 * `:core:naming`, `:core:sync`, `:core:feed`, and `:core:download`. Neither `kotlinx-datetime` nor
 * `kotlin.time.Instant` is used: either would be a second vocabulary for no gain here.
 *
 * **This object exists for its function names, not its arithmetic.** Everything in the schema is
 * epoch **millis** except [SyncState.lastEpisodeActionSyncTs], which is Unix **seconds** taken
 * verbatim from the server and never computed locally (CLAUDE.md §11). A single `Long`-taking
 * helper would let those two be swapped silently, in the exact place where the bug is invisible —
 * a wrong `since` doesn't crash, it just makes incremental sync quietly skip or repeat actions.
 * Two differently-named functions can be mistyped but not confused.
 *
 * There is deliberately no `now()`: code that needs the current time takes an injected
 * `java.time.Clock`, so it stays testable (CLAUDE.md §7).
 */
object EpochTime {
    /** Epoch millis (every timestamp in the schema except [SyncState.lastEpisodeActionSyncTs]). */
    fun ofMillis(millis: Long): Instant = Instant.ofEpochMilli(millis)

    /** Null-tolerant [ofMillis], for the many nullable timestamps (`pubDate`, `lastRefreshedAt`). */
    fun ofMillisOrNull(millis: Long?): Instant? = millis?.let(::ofMillis)

    /**
     * Unix **seconds**, and only ever for [SyncState.lastEpisodeActionSyncTs] and the GPodder
     * `since`/`timestamp` wire values it comes from. If you are reaching for this anywhere else,
     * the value is probably millis and this is the wrong function.
     */
    fun ofServerSeconds(seconds: Long): Instant = Instant.ofEpochSecond(seconds)

    /** Back to the storage representation — epoch millis. */
    fun toMillis(instant: Instant): Long = instant.toEpochMilli()

    /**
     * `Episode.durationMs` as a [Duration]. Null in, null out: `itunes:duration` is unreliable and
     * a missing duration renders as no duration at all, never as a fabricated or zero one
     * (`UI.adoc` §5).
     */
    fun durationOfMillis(millis: Long?): Duration? = millis?.let(Duration::ofMillis)
}
