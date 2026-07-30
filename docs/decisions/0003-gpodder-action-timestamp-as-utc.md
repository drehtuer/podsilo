# 0003 — `EpisodeAction.timestamp` is rendered and parsed as UTC

## Status

Accepted.

## Context

CLAUDE.md §11 flags that the GPodder API uses two different timestamp formats: the `since` query
parameter and the top-level response `timestamp` are Unix seconds, while the per-action
`timestamp` field (e.g. `2009-12-12T09:00:00`) is ISO-8601 **without a timezone offset**. The API
does not specify which clock a naive timestamp like that represents — it's convention, not
specification, same as the skip-as-`PLAY` encoding in ADR 0002.

`:core:sync`'s reconciliation logic (`reconcile()`) needs to compare per-action timestamps across
devices to resolve duplicates within one batch (last-write-wins), and needs to render its own
outbound actions' timestamps somehow.

## Decision

Podsilo always renders and reads this field as **UTC** — i.e. `actionedAt` (epoch millis, already
UTC-based by construction) is formatted via
`Instant.ofEpochMilli(actionedAt).atZone(ZoneOffset.UTC).toLocalDateTime()`, and parsed back the
same way. This is implemented in `net.drehtuer.podsilo.core.sync.GpodderTimestamps` (`:core:sync`).

## Rationale

Using each device's own local timezone to render this field (a plausible alternative reading of
"naive" timestamp) would mean two devices in different timezones produce different-looking
timestamps for actions that happen at the same instant, and — more importantly — a naive
lexical/numeric comparison between two such timestamps would not agree with true chronological
order across zones. Fixing the interpretation to UTC removes that ambiguity entirely: every device
agrees on what the numbers mean, regardless of its own clock's zone setting.

## Consequences

- A malformed or non-ISO timestamp from another client parses to `null` rather than throwing;
  `reconcile()` falls back to the injected `Clock` for `actionedAt` in that case (see
  `ReconciliationTest`'s clock-skew case).
- This is Podsilo's own convention for its outbound actions and its interpretation of inbound ones.
  It has not been verified against a live Nextcloud/`opodsync` instance — do that once `:core:gpodder`
  (Tier 3) is built and integration-tested against the compose profile (CLAUDE.md §4).
