// SPDX-License-Identifier: GPL-3.0-or-later

package net.drehtuer.podsilo.core.model

/**
 * Placeholder proving the `:core:model` <-> `:app` module wiring and the Tier 1
 * (plain-JVM) test setup both work end to end. Replace with real domain types
 * (Feed, Episode, EpisodeLedger...) as the build order progresses.
 */
fun greeting(name: String = "Podsilo"): String = "Hello, $name!"
