# 0007 — `:core:gpodder` is a JVM module, not an Android library

## Status

Accepted. Resolves `docs/architecture.md` §12 open decision #3.

## Context

`:core:gpodder` was scaffolded as `com.android.library` along with every other `:core:*` module
except `:core:model`/`:core:naming`/`:core:sync`. Architecture.md §12 #3 flagged this as an open
question: nothing in the module's actual job (Retrofit/OkHttp HTTP client, DTOs, DTO↔domain
mapping) needs an Android API, and the choice has "different Robolectric/Android-dependency
implications for `:core:gpodder`'s own tests."

Building it made the trade-off concrete. Retrofit, OkHttp, and kotlinx-serialization are all plain
JVM libraries. Nothing in the client touches `Context`, `Uri`, or any framework type — credentials
arrive as a plain [`GpodderCredentials`](../../core/gpodder/src/main/kotlin/net/drehtuer/podsilo/core/gpodder/GpodderCredentials.kt)
value object, and storing them securely is `:core:datastore`'s job (Tier 4a).

## Decision

Make `:core:gpodder` a `kotlin("jvm")` module.

Two concrete benefits over leaving it as an Android library:

1. **The "no Android" property is compiled in, not review-enforced.** A stray `import android.*`
   becomes a compile error rather than something a reviewer has to notice.
2. **Tests run on the plain `test` task.** No AGP unit-test variant, no `testDebugUnitTest`, no
   Robolectric. Compare `:core:feed`, which is genuinely Android (it will host `FeedRefreshWorker`)
   and consequently needs Robolectric for its parser tests (`docs/decisions/0005`) — a cost worth
   paying there and worth avoiding here.

## Consequences

- `:app` (an Android application module) depends on `:core:gpodder` — an Android module depending
  on a JVM module is fine; only the reverse is disallowed, which is the constraint that motivates
  the ports-and-adapters split in architecture.md §2 in the first place.
- Architecture.md §2's module table said adapters bind themselves via "Hilt `@Binds` in each
  adapter module's own Hilt module". That still works from a JVM module — Hilt's `@Module`,
  `@Binds`, and `@InstallIn`/`SingletonComponent` all come from `hilt-core`, which is a plain JVM
  artifact. **Unverified**, since Hilt isn't wired up yet (Tier 4c). If it turns out to be painful,
  the fallback is a `@Provides` in `:app`'s own Hilt module, or converting this module back — a
  build-file-only change, since no source in it would need to move.
- `:core:gpodder/src/main/AndroidManifest.xml` was deleted; sources moved to the standard
  `src/main/kotlin` / `src/test/kotlin` JVM layout.
