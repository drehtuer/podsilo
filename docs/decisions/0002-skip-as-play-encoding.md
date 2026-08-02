# 0002 — Skip-as-`PLAY` encoding

## Status

Accepted. Resolves `docs/architecture.md` §12 open decision #1.

## Context

CLAUDE.md §5 specifies that "skip" is encoded as a `PLAY` action with
`started = 0, position = total, total = <duration>`, and explicitly requires verifying this
against AntennaPod's implementation rather than guessing, since gpodder-sync conventions beyond
the four endpoints are convention, not specification. It also says: if no usable duration exists,
still send `PLAY`, document what goes in `position`/`total`, and never invent a plausible-looking
duration.

## What AntennaPod does

`SynchronizationQueueImpl.enqueueEpisodePlayed(media, completed)` builds:

```java
new EpisodeAction.Builder(media.getItem(), EpisodeAction.PLAY)
    .currentTimestamp()
    .started(media.getStartPosition() / 1000)
    .position((completed ? media.getDuration() : media.getPosition()) / 1000)
    .total(media.getDuration() / 1000)
    .build()
```

(`net/sync/service/src/main/java/de/danoeh/antennapod/net/sync/service/SynchronizationQueueImpl.java`,
AntennaPod, GPL-3.0, current `master` as of 2026-07-30.)

For a completed episode, `position` is set to the same value as `total` — i.e. `position == total`
encodes "fully played." AntennaPod does **not** special-case a missing/zero duration: it divides by
1000 unconditionally, so an unknown duration flows through as `0` rather than being detected and
substituted with something else.

## Decision

Podsilo's skip encodes:

- `started = 0` — Podsilo has no resume position (it never plays audio; CLAUDE.md §1's non-goal),
  unlike AntennaPod which has a real last-start-position to report.
- `position = total`
- `total = durationSeconds` if known (`EpisodeLedgerRow.durationSeconds`, see ADR 0001), else `0`.

Sending `0` for an unknown duration matches AntennaPod's own observed behaviour (no fabrication)
and keeps `position == total` trivially true (`0 == 0`), so the "fully played" signal still holds
for interoperability even when duration data was never available.

## Alternatives considered

- **Use a sentinel other than 0 for unknown duration** (e.g. omit `total`/`position` entirely).
  Would let a receiving client distinguish "skipped, duration unknown" from "skipped, zero-length
  episode" — but deviates from AntennaPod's observed convention and there is no evidence any
  gpodder client actually makes that distinction today. Rejected in favour of matching the
  reference implementation.

## Consequences

- `net.drehtuer.podsilo.core.sync.toOutboundAction()` (`:core:sync`) implements exactly this
  mapping; see `OutboundEpisodeActionTest` for the covered cases (known duration, unknown duration,
  `DOWNLOADED` vs `SKIPPED`, local-only states never emitting an action).

## Round-tripped against a real Nextcloud (2026-08-02)

The encoding this ADR chose — `started = 0, position = total, total = <duration>` — was taken from
AntennaPod's convention, which is convention rather than specification. It now survives a real
server unchanged.

Nextcloud 33.0.5 with gpoddersync, posted and read straight back:

```
PLAY  guid=probe-…-play  started=0  position=1800  total=1800
```

Byte-for-byte what was sent. Worth having, because the fields are exactly the sort of thing a server
is free to normalise, clamp or drop — and a silently altered `position` would make Podsilo's skips
look like partial listens to every other client.

Verified in the same run as `docs/decisions/0008`, whose `DOWNLOAD` was *not* kept — so this is a
positive result from a run where the negative control also behaved as predicted.
