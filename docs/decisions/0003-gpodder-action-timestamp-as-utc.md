# 0003 — `EpisodeAction.timestamp` is emitted as bare UTC and parsed leniently

## Status

Accepted. **Amended during Tier 3** — the original version of this ADR assumed the per-action
timestamp is always offset-less, which turned out to be wrong about what real servers emit. The
amendment is folded in below; see `docs/decisions/0009` for the full verified wire contract.

## Context

CLAUDE.md §11 flags that the GPodder API uses two different timestamp formats: the `since` query
parameter and the top-level response `timestamp` are Unix seconds, while the per-action
`timestamp` field (e.g. `2009-12-12T09:00:00`) is ISO-8601 **without a timezone offset**. The API
does not specify which clock a naive timestamp like that represents — it's convention, not
specification, same as the skip-as-`PLAY` encoding in ADR 0002.

`:core:sync`'s reconciliation logic (`reconcile()`) needs to compare per-action timestamps across
devices to resolve duplicates within one batch (last-write-wins), and needs to render its own
outbound actions' timestamps somehow.

**What Tier 3 found:** neither reference server actually emits the bare form any more.
`nextcloud-gpodder` formats with PHP `format("c")` → `2021-10-06T11:49:23+00:00` (CHANGELOG:
*"Always respond with timezone in timestamps"*), and `opodsync` emits a trailing `Z`. CLAUDE.md §11
and the gpodder README are both describing an older reality. Both servers still *accept* all three
forms on input.

## Decision

**Emit** the bare UTC form CLAUDE.md §11 specifies — `actionedAt` (epoch millis, UTC-based by
construction) formatted via `Instant.ofEpochMilli(...).atZone(ZoneOffset.UTC).toLocalDateTime()`.
Both servers parse an offset-less value as UTC (`new DateTime($ts, new DateTimeZone("UTC"))`),
which is exactly what's meant.

**Parse** leniently: bare, `+HH:MM`, and `Z` are all accepted. A bare value — carrying no zone
information at all — is assumed UTC, which is the substance of the original decision below and is
unchanged.

Implemented in `net.drehtuer.podsilo.core.sync.GpodderTimestamps` (`:core:sync`).

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
- **Parse to `OffsetDateTime`, never `LocalDateTime`.** A `LocalDateTime` silently discards any
  offset the server sent, so `…T11:49:23+02:00` reads as 11:49 UTC instead of 09:49 UTC — a
  two-hour error invisible to any test that only uses UTC-equivalent timestamps.
  `GpodderTimestampsTest` has a dedicated regression case guarding this.
- The original version of this ADR led to a test asserting that offset-bearing timestamps parse to
  `null` ("the wrong format for this field"). That test encoded the mistaken assumption and was
  replaced, not merely relaxed — worth noting as a case where a passing test was confirming a wrong
  belief rather than correct behaviour.
- Still verified only by reading server source, not by running against a live Nextcloud/`opodsync`
  instance — CLAUDE.md §4's compose profile doesn't exist yet. Re-check when it does.
