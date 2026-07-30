# 0001 — `EpisodeLedgerRow` denormalises `feedUrl`, `enclosureUrl`, and `durationSeconds`

## Status

Accepted.

## Context

`docs/architecture.md` §4 originally specified `EpisodeLedgerRow` with only `episodeKey` plus
state/outbox bookkeeping fields. Building `:core:sync`'s outbound mapping
(`EpisodeLedgerRow` → `EpisodeAction`) surfaced a gap: the GPodder POST body needs `podcast`
(feed URL) and `episode` (enclosure URL), and the skip-as-`PLAY` encoding needs the episode's
duration — none of which live on the ledger row itself.

The obvious fix — look these up from the `Episode` cache via `episodeKey` at push time — breaks
under the documented sync order (CLAUDE.md §5): **pull subscriptions (full) → push unsynced ledger
rows → ...**. Pulling subscriptions first deletes the `Episode` rows of any feed that just got
unsubscribed. If a previous push attempt for that feed's episodes failed (network blip) and the
row is still unsynced, the very next sync pass would delete the `Episode` row *before* trying to
push it again — the outbound mapping would have no source for `podcast`/`episode`/duration.

This isn't a rare corner case; it's a direct, structural consequence of "keep ledger rows, delete
episode rows" (the subscription-mirroring rule) combined with the fixed sync order.

## Decision

Denormalise `feedUrl: String`, `enclosureUrl: String`, and `durationSeconds: Int?` directly onto
`EpisodeLedgerRow`, captured once at the moment the row is written (download or skip), when the
originating `Episode` is guaranteed to be in hand. `guid` is not a separate stored column — it's
derived (`episodeKey.takeIf { it != enclosureUrl }`), since `episodeKey` already equals `guid` when
one exists.

This makes the ledger row fully self-sufficient for building its own outbound `EpisodeAction`,
independent of whether the `Episode` cache still has a matching row by the time the outbox drains.

## Alternatives considered

- **Keep the schema as-is, accept the gap.** Would require a documented fallback for an unsynced
  row whose `Episode` was pruned (drop it silently, or block feed removal until the outbox drains).
  Simpler schema, but a durability hole in exactly the mechanism CLAUDE.md calls "the one table
  that must never be lost."
- **Reorder sync steps** (push before pulling subscriptions). Avoids the schema change, but only
  fixes the *current* pass — a push that fails on this pass still loses its `Episode` row on the
  *next* pass's subscription pull, so the bug just moves one pass later rather than disappearing.

## Consequences

- `EpisodeLedgerRow`'s Room entity (`:core:database`, not yet built) needs these three columns from
  the start — no migration required since no schema has shipped yet.
- `:core:sync`'s `SyncOrchestrator` depends only on `FeedRepository`, `EpisodeLedgerRepository`,
  `SyncStateRepository`, and `GpodderClient` — it never needs `EpisodeRepository` to build an
  outbound action, keeping the dependency graph exactly as `docs/architecture.md` §2's sequence
  diagram documents.
- A skip's `total`/`position` reflect the duration known *at skip time*. If the feed later corrects
  a bad duration, an already-skipped row won't retroactively pick it up — acceptable, since the
  action has likely already been pushed and superseding it isn't meaningful to gpodder sync anyway.
