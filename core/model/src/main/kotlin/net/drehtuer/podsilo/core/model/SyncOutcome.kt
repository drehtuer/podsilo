// SPDX-License-Identifier: GPL-3.0-or-later

package net.drehtuer.podsilo.core.model

/**
 * Result of one `:core:sync` `SyncOrchestrator.sync()` pass. `:app`'s `SyncWorker` translates this
 * to `Result.success()/retry()/failure()` — kept as a plain sealed type here (not a `WorkManager`
 * type) so `:core:sync` stays Android-free (CLAUDE.md §5/§8: model expected failures as return
 * types, don't leak framework types into pure-JVM modules).
 */
sealed interface SyncOutcome {
    data object Success : SyncOutcome

    /** Transient failure (network, timeout, 5xx) — worth `WorkManager` retrying with backoff. */
    data class Retry(
        val reason: String,
    ) : SyncOutcome

    /** Non-transient failure (auth, malformed response) — retrying without intervention won't help. */
    data class Failure(
        val reason: String,
    ) : SyncOutcome
}
