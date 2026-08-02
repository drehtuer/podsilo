# 0016 — Time is `java.time`, storage stays `Long`, and `EpochTime` is the only seam

## Status

**Accepted 2026-08-01.** Resolves the last third of `docs/architecture.md` §12's dependency item.
No new dependency is added.

## Context

`docs/UI_interface.md`'s state classes carry `publishedAt: Instant?`, `lastRefreshedAt: Instant?`,
`duration: Duration?` and similar, because seam rule §0.6 says state carries typed values and
`stringResource` happens at render — a relative time ("10 min ago") and an absolute one ("14 Jul
2026") are formatting decisions that belong in the Composable, and formatting needs a real date-time
type rather than a number.

The draft noted `kotlinx.datetime` for `Instant`. It is **not** in `gradle/libs.versions.toml` and is
not pre-approved by CLAUDE.md §3, so which type to use was genuinely open. Three candidates:

| Option | Cost | Benefit |
|---|---|---|
| `kotlinx-datetime` | A new dependency, and a **third** time vocabulary in a codebase that already has one — every boundary converts twice | Multiplatform portability the project explicitly does not want (CLAUDE.md §1: Android phone only) |
| `kotlin.time.Instant` | No dependency (stdlib at the pinned Kotlin 2.4.10), but still a **second** vocabulary: it is not `java.time.Instant`, so any formatting means `toJavaInstant()` | Nothing here; its reason to exist is multiplatform |
| Plain `Long` epoch millis | Nothing to add — but a `Long` does not say *which unit*, and this project already has a live hazard there | Zero churn, zero migration |

Two facts settled it:

1. **`minSdk = 33`**, so `java.time` is available natively — no core-library desugaring, no cost.
2. **`java.time` is already this project's time library**, in main source in `:core:naming`
   (`ZoneId` injection, ADR 0004), `:core:sync` (`OffsetDateTime` parsing, ADR 0003/0009),
   `:core:feed` and `:core:download`. `kotlin.time` appears nowhere.

## Decision

**Storage and domain types keep `Long`. UI state carries `java.time`. One conversion object bridges
them.**

- `Feed.firstSeenAt`, `Episode.pubDate`, `EpisodeLedgerRow.actionedAt`, `LogEntry.at` and the rest
  stay epoch-millis `Long`. **No schema change, no migration, no churn in built and tested code.**
- `docs/UI_interface.md`'s state classes use `java.time.Instant` and `java.time.Duration` — one
  vocabulary, matching every other module.
- The conversion lives in exactly one place, in `:core:model` (which every feature module already
  depends on, and which stays pure JVM):

```kotlin
/**
 * The single conversion between the Long epoch numbers every stored type uses and the java.time
 * values the UI renders.
 *
 * It exists to name the unit. This project stores epoch MILLIS everywhere except
 * SyncState.lastEpisodeActionSyncTs, which is Unix SECONDS verbatim from the server and must never
 * be computed locally (CLAUDE.md §11). Two named functions make that impossible to confuse; one
 * `Long` parameter would not.
 */
object EpochTime {
    fun ofMillis(millis: Long): Instant = Instant.ofEpochMilli(millis)
    fun ofMillisOrNull(millis: Long?): Instant? = millis?.let(::ofMillis)
    fun ofServerSeconds(seconds: Long): Instant = Instant.ofEpochSecond(seconds)
    fun toMillis(instant: Instant): Long = instant.toEpochMilli()
    fun durationOfMillis(millis: Long?): Duration? = millis?.let(Duration::ofMillis)
}
```

## Why a seam at all, rather than `Instant` everywhere or `Long` everywhere

`Instant` in the domain types would mean a Room type converter, a migration-free but repo-wide edit
to code that is already tested, and a `SyncState` field that is *still* seconds and *still* special.
`Long` in the UI state would push `Instant.ofEpochMilli` to every call site that formats a date, and
the millis-vs-seconds distinction — which CLAUDE.md §11 names as a top gotcha and which has already
caused confusion once in this project — would live nowhere at all.

`EpochTime` is not an abstraction layer over a library, which CLAUDE.md §3 forbids; it is five
one-line functions whose value is entirely in their **names**. `ofServerSeconds` cannot be fed a
millis value without someone noticing they typed the wrong function.

## Consequences

- No entry in `gradle/libs.versions.toml`, no row in `docs/third-party.md`. The cheapest possible
  outcome, which is the point.
- **`EpochTime` is the only permitted converter at the storage↔UI boundary** — the place a millis
  value becomes an `Instant` for a screen to render, or an `Instant` becomes a millis value to
  store. That is the confusion this ADR exists to prevent.

  **Amended 2026-08-02.** This rule was first written as "the only `Instant.ofEpochMilli`/
  `toEpochMilli` call site outside `:core:naming` and `:core:sync` — worth one grep in review", and
  the grep promptly failed against code that is entirely correct. Three call sites are neither
  boundary conversions nor mistakes:

  | Call site | What it is |
  |---|---|
  | `RssMapping.parseRfc822Date` | **Parsing** a wire date string into storage millis. `EpochTime` has no parse function and should not grow one — parsing an RSS date is `:core:feed`'s job |
  | `EpisodeDownloader` | Formatting `pubDate` for an audio tag, i.e. naming work, next to the engine that already owns `ZoneId` |
  | `OlderThan.cutoffMillis` | Calendar arithmetic that *produces* a stored cutoff, inside the type that owns the periods |

  The narrower rule is the one that was always meant, and it is what a reviewer should check: a
  **screen** must never construct an `Instant` from a stored `Long` itself. A grep for
  `ofEpochMilli` in `:feature:*` and `:app` is the useful one; a repo-wide grep is not.
- Injecting a `Clock` for testability (CLAUDE.md §7) is unaffected — `EpochTime` converts, it never
  reads the current time, and has no `now()` for exactly that reason.
- If this project ever did go multiplatform, this is the one file that would change. That is a
  better position than the dependency would have bought.
