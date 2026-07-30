# 0004 — `:core:naming`'s `{date}` timezone and missing-`pubDate` fallback

## Status

Accepted. Resolves `docs/architecture.md` §12 open decision #5.

## Context

CLAUDE.md §6 requires normalising `pubDate` "to a single fixed timezone (the device's, chosen once
and documented) so the same episode never produces two different dates on two syncs," and that a
missing/malformed date must "degrade to something sortable, not to `_Title.mp3`." Architecture.md
§12 left open which exact zone-resolution strategy (device-at-first-parse vs. always re-resolved)
satisfies this without violating the "same episode, same date" guarantee if the device's timezone
setting changes between two attempts at naming the same episode (e.g. a failed download retried
after the user travels).

## Decision

1. **Zone injection, not implicit lookup.** `DefaultNamingTemplateEngine` takes a `ZoneId` as a
   constructor parameter (default `ZoneId.systemDefault()`), used consistently for every `{date}`
   resolution that instance performs. It never calls `TimeZone.getDefault()`/
   `ZoneId.systemDefault()` internally mid-calculation. This also satisfies CLAUDE.md §7's "inject
   a Clock rather than calling `System.currentTimeMillis()` in logic under test" in spirit: tests
   inject a fixed `ZoneId` (see `DefaultNamingTemplateEngineTest`, `DateVariableTest`) for
   deterministic output regardless of the test runner's own local timezone.
2. **The "same episode, two dates" risk is deliberately left to the caller (`:core:download`,
   Tier 4b) to close**, not solved inside `:core:naming`. `docs/architecture.md` §6 already
   mandates persisting `EpisodeLedgerRow.writtenFileName` and reusing it on retry rather than
   re-resolving the name — so in practice `resolve()` only runs once per episode's real download
   (before a filename exists), and a subsequent retry after a timezone change reuses the already-
   written name rather than calling `resolve()` again. `:core:naming` provides a pure, zone-
   parameterised function; the "resolve once, then reuse" discipline that makes it safe lives where
   the architecture doc already puts it.
3. **Missing `pubDate` fallback:** `formatDate(null, zoneId)` returns the sortable placeholder
   `"00000000"` — never an empty string, which CLAUDE.md explicitly calls out as producing a
   filename that looks like `_Title.mp3`. A malformed custom date pattern degrades to the same
   placeholder rather than throwing.
4. **The full three-step fallback chain** (`pubDate` → other date field the parser exposes → date
   first seen locally) described in CLAUDE.md §6 is **not** implemented in `:core:naming`. By
   design, `:core:feed` (Tier 2, not yet built) is expected to resolve that chain when constructing
   `Episode.pubDate` in the first place — `:core:naming`'s `"00000000"` fallback is a defensive
   backstop for a genuinely-null value reaching it (e.g. a direct unit test), not the primary
   mechanism.

## Consequences

- `:core:naming` stays pure-JVM and easily testable: `ZoneId` is just a constructor value, no
  `Context`/`TimeZone.getDefault()` Android dependency anywhere in the module.
- When `:core:feed` is built, confirm it actually populates `Episode.pubDate` via the full fallback
  chain rather than leaving it `null` for a valid-but-unusual date field — otherwise more episodes
  than expected will render with the `"00000000"` placeholder.
- When `:core:download` is built, confirm `resolve()` is called at most once per episode before a
  `writtenFileName` exists, per point 2 above — if that invariant is ever violated (e.g. a "rename
  on template change" feature is added later), this decision needs revisiting.
