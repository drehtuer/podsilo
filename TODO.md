# TODO — Implementation order

Implementation order for Podsilo, sorted by testability tier (no dependencies → external
libraries → networking mocks → Android/emulator), rather than strictly by CLAUDE.md §10's module
order. Cross-references `docs/architecture.md`. See that document's [§13 build-order
checklist](docs/architecture.md#13-build-order-checklist) for the module-order view of the same
work.

Repo state as of writing: all modules are scaffolded but empty (CLAUDE.md §10 step 1 — dev
container + Gradle skeleton — is done; nothing else is built).

**Update (2026-07-30): Tier 1 is complete.** `:core:model`, `:core:naming`, and `:core:sync` are
implemented and tested — 114 tests total, `./gradlew ktlintCheck detekt test` green across the
whole repo. Two schema/behaviour decisions surfaced during implementation and were resolved with
the user before finalizing `:core:sync` (see `docs/decisions/`):
`0001-episode-ledger-row-denormalized-fields.md` (added `feedUrl`/`enclosureUrl`/`durationSeconds`
to `EpisodeLedgerRow`) and `0002-skip-as-play-encoding.md` (AntennaPod's convention, researched
live). Two further implementation-detail decisions were resolved without needing to ask:
`0003-gpodder-action-timestamp-as-utc.md` and
`0004-naming-date-timezone-and-missing-date-fallback.md`. `docs/architecture.md` §4/§5/§6/§12 were
updated to match what was actually built.

## Tier 1 — No dependencies (pure Kotlin/JDK stdlib, plain JUnit, milliseconds)

- [x] **`:core:model`** — domain data classes (`Feed`, `Episode`, `EpisodeLedgerRow`,
  `SyncState`), `LedgerState` enum, `SyncOutcome` sealed type, DTOs (`SubscriptionDelta`,
  `EpisodeAction`, `EpisodeActionPage`, `ResolvedName`), and the port interfaces
  (`FeedRepository`, `EpisodeRepository`, `EpisodeLedgerRepository`, `SyncStateRepository`,
  `GpodderClient`, `NamingTemplateEngine`) per architecture.md §5. Zero deps beyond
  `kotlinx-coroutines-core`. Everything downstream depends on this — build first regardless of
  tier.
- [x] **`:core:naming`** — template resolution, sanitisation, UTF-8-byte-safe truncation,
  collision suffixing, optional transliteration/regex title-cleanup rules (architecture.md §11,
  CLAUDE.md §6). Only JDK stdlib (`java.text.Normalizer`, string ops). Highest-value-per-effort
  test target per CLAUDE.md §7 — fully table-driven. **Resolve open decision #5** (fixed timezone
  for `pubDate`) here and write the ADR.
- [x] **`:core:sync`** — `SyncOrchestrator` reconciliation logic: guid-vs-enclosure
  identification, remote-action→ledger-state mapping, outbound ledger-row→`EpisodeAction`
  mapping (architecture.md §6 sequence, §9 state machine). Depends only on `:core:model`
  interfaces — test with hand-written in-memory fakes of the four repositories + `GpodderClient`,
  no MockWebServer, no Room, no Android. Covers CLAUDE.md §7 item 1 (sync reconciliation,
  table-driven) and the pure-logic half of item 8 (triage durability).
  - **Deviation from CLAUDE.md §10:** §10 builds `:core:sync` after `:core:gpodder`. Nothing
    about the module itself requires `:core:gpodder` to exist first — but **open decisions #1
    and #2** (skip-as-`PLAY` encoding, subscriptions response shape) require reading AntennaPod's
    source now, even though the live client doesn't exist yet. Flag as `TODO`/pending-ADR in the
    mapping functions rather than guessing.

## Tier 2 — External JVM libraries, no network/Android (fixture-driven unit tests)

- [ ] **Feed parsing** (inside `:core:feed`) — Stalla integration mapping RSS/Atom bytes →
  `Feed`/`Episode`. Test against static fixtures in `src/test/resources/feeds/` (missing GUIDs,
  duplicate GUIDs, missing enclosures, bad dates, CDATA HTML, wrong encoding, no
  `itunes:duration`) — no HTTP call in these tests at all (architecture.md §7).
- [ ] **Tag rewriting** (inside `:core:download`) — jaudiotagger integration: write
  title/artist/album/date/genre/track/comment to a real temp `java.io.File`; best-effort fallback
  on unsupported/corrupt containers. No Android, no network — verify licence/Android-compatibility
  and record the choice in `docs/decisions/` before writing code (CLAUDE.md §6 flags this as
  unconfirmed).

Both sit logically inside Android library modules but their core logic has no Android/network
dependency — build and test as isolated classes first, wire into the module's Android scaffolding
later.

## Tier 3 — Require networking mocks (MockWebServer)

- [ ] **`:core:gpodder`** — Retrofit/OkHttp client implementing `GpodderClient`. Assert exact
  request shape (paths, query params, JSON body) and both timestamp formats (Unix-seconds
  `since`/response `timestamp` vs. ISO-8601-no-offset per-action `timestamp` — CLAUDE.md's
  hardest-flagged gotcha), plus 401/500/timeout/malformed-body handling. **Resolve open decision
  #2** against recorded fixtures here; re-verify later against the live `opodsync` compose
  profile.
- [ ] **Feed HTTP fetch layer** (inside `:core:feed`, on top of Tier 2's parsing) — conditional
  GET (`ETag`/`If-None-Match`, `Last-Modified`/`If-Modified-Since`), 304 handling, redirects,
  timeouts, via MockWebServer.

Both are plain-JVM (OkHttp/Retrofit need no Android runtime) — CLAUDE.md's own Tier 1 definition
already includes MockWebServer; this is just the sub-bucket called out separately.

## Tier 4 — Require Android framework (Robolectric, then real device/emulator)

### 4a. Robolectric-testable, no real device

- [ ] **`:core:database`** — Room entities/DAOs/migrations, in-memory DB tests; implements the
  four repository ports, entity↔domain mapping at the boundary (architecture.md §4).
- [ ] **`:core:datastore`** — DataStore Preferences + Keystore-backed encryption for the
  Nextcloud app password.

### 4b. Needs instrumented tests / real emulator (SAF, WorkManager, ContentResolver)

- [ ] **`:core:download`'s `DownloadWorker`** — wires together naming (1) + tags (2) + fetch (3)
  behind a real SAF `DocumentFile` write (architecture.md §8 pipeline): resume, cancel,
  disk-full, permission-revoked, 404, redirect, no-range-support.
- [ ] **`FeedRefreshWorker`** and **`:app`'s `SyncWorker`** (thin `CoroutineWorker` wrapping
  `SyncOrchestrator` — see architecture.md §2 for why it can't live in `:core:sync`).
- [ ] **SAF folder-grant flow** — `ACTION_OPEN_DOCUMENT_TREE`, `takePersistableUriPermission`,
  revoke/re-grant handling — can't be faked, needs a real system picker.
- [ ] Foreground service notification for active downloads.

### 4c. Compose UI (emulator recommended, per CLAUDE.md's Tier 3 host-emulator path)

- [ ] **`:feature:settings`** — folder picker, credentials, naming template editor with live
  preview (calls the already-tested naming module's `resolve()`).
- [ ] **`:feature:episodes`** — filterable list, per-row triage, feed-filter chips.
- [ ] **`:app`** — Hilt `@Binds` wiring every port to its 4a/4b adapter, navigation.

### Worth doing early despite appearing last

- [ ] CLAUDE.md §7 item 6, the no-auto-download invariant test (500-episode fixture → assert
  zero downloads, zero actions, fail if `subscription_change/create` is ever hit) only needs
  Tier 1's fakes plus Tier 3's `MockWebServer` — write it as soon as Tier 3 lands, then re-run it
  end-to-end once Tier 4b exists.

## Open question

Decide whether to create `docs/decisions/` ADR stubs for all five open decisions in
architecture.md §12 now (as placeholders to fill in as each tier resolves them), or write each
ADR only when that decision is actually resolved.
