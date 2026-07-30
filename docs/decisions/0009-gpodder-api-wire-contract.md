# 0009 — GPodder API wire contract, verified against both server implementations

## Status

Accepted. Resolves `docs/architecture.md` §12 open decision #2 (subscriptions `add`/`remove`
response shape).

## Context

CLAUDE.md §5 and §11 warn that the Nextcloud GPodder API "is not formally specified anywhere
authoritative — infer from implementations, not assumptions", and §12 #2 specifically deferred the
subscriptions response shape until it could be checked against a real server. This ADR records what
the two reference implementations actually do, so `:core:gpodder`'s DTOs and mapping have a cited
basis rather than a guess.

Sources read: `thrillfall/nextcloud-gpodder` (the Nextcloud app) and `kd2org/opodsync` (the
independent PHP/SQLite reimplementation CLAUDE.md §4 specifies as the CI test server), both at
`master`.

## Open decision #2, resolved: the subscriptions response

`SubscriptionChangeController::list()` returns `{"add": [url…], "remove": [url…], "timestamp": N}`
— plain URL strings, `timestamp` in Unix seconds from PHP's `time()`.

The key ambiguity was what a no-`since` call returns. `gpodder_subscriptions` is a **state table,
not an append-only log**: one row per (url, user) carrying a `subscribed` boolean, upserted in
place. The query filters `subscribed = ? AND updated > :since`, and no `since` becomes
`DateTime(0)`, matching everything. Therefore:

- `add` without `since` = the **complete current subscription set**
- `remove` = every URL ever unsubscribed
- the two are **disjoint by construction** (one row, one flag), so `add − remove == add`

**CLAUDE.md §5's specified `set = add − remove` is correct and safe**, under either interpretation,
exactly as it predicted. `SyncOrchestrator.pullSubscriptions()` already does this; no change needed.

## Per-action field contract

Keys: `podcast`, `episode`, `guid`, `action`, `timestamp`, `started`, `position`, `total`.

| Aspect | `nextcloud-gpodder` | `opodsync` | How `:core:gpodder` handles it |
|---|---|---|---|
| `action` case | **UPPER** (`strtoupper` on write) | **lower** (`strtolower` on write) | Parsed case-insensitively; emitted upper-case |
| `started`/`position`/`total` when absent | written as **`-1`** | **omitted** entirely | `-1` and missing both normalise to `null` |
| `guid` | may be `null` | may be omitted | nullable either way |
| per-action `timestamp` | ISO-8601 **with offset** (PHP `format("c")` → `…+00:00`) | trailing **`Z`** | Both parsed, plus the bare form — see below |
| extra fields | — | sends `update_urls` | `ignoreUnknownKeys = true` |
| `since` boundary | exclusive (`>`) | **inclusive** (`>=`) | Reconciliation is idempotent, so a re-delivered boundary action is a no-op |

## The per-action timestamp format — CLAUDE.md §11 is stale here

CLAUDE.md §11 and the gpodder API README both document this field as ISO-8601 **without** a
timezone offset (`2009-12-12T09:00:00`). Neither server emits that form any more:
`nextcloud-gpodder`'s CHANGELOG has *"Always respond with timezone in timestamps"*, and its
repository formats with PHP `format("c")`.

`parseGpodderTimestamp` (`:core:sync`) therefore accepts **all three** forms — bare, `+HH:MM`, and
`Z` — and `docs/decisions/0003` is updated accordingly. Podsilo still *emits* the bare form, which
both servers parse as UTC (`new DateTime($ts, new DateTimeZone("UTC"))`).

One trap worth naming: an offset-bearing timestamp must be parsed as `OffsetDateTime`, not
`LocalDateTime`. A `LocalDateTime` silently discards the offset, so `…T11:49:23+02:00` would be
read as 11:49 UTC instead of 09:49 UTC — a two-hour error that no test using only UTC-equivalent
timestamps would catch. `GpodderTimestampsTest` has a dedicated regression case for it.

## Other findings worth keeping

- **`POST episode_action/create` takes a bare JSON array**, not an envelope object — Nextcloud
  merges the decoded body into request params and the controller keeps only numeric keys.
- **Auth is plain HTTP Basic.** These are ordinary AppFramework routes (`@NoAdminRequired`,
  `@NoCSRFRequired`), *not* `OCSController` routes, so **no `OCS-APIRequest` header is needed** and
  responses are plain JSON rather than OCS-wrapped. (Inferred: Basic auth comes from Nextcloud core
  middleware; no explicit header handling appears in the app's own source.)
- **`opodsync` inner-joins episode actions against subscriptions**, so actions for unsubscribed
  feeds disappear from its `GET`. `nextcloud-gpodder` does not. Podsilo tolerates either — its
  ledger is keyed by episode, not by subscription (CLAUDE.md §5).
- **`nextcloud-gpodder` silently discards `DOWNLOAD`/`DELETE` on POST.** Big enough for its own
  ADR: see `docs/decisions/0008`.

## Still unverified

Everything above is from reading source, not from running against a live server. CLAUDE.md §4's
disposable `opodsync` compose profile doesn't exist yet — when it does, re-verify, and remember
that opodsync is **not** proof of Nextcloud's behaviour (see 0008).
