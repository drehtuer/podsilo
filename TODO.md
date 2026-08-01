# TODO — Implementation order

Implementation order for Podsilo, sorted by testability tier (no dependencies → external
libraries → networking mocks → Android/emulator), rather than strictly by CLAUDE.md §10's module
order. Cross-references `docs/architecture.md`. See that document's [§13 build-order
checklist](docs/architecture.md#13-build-order-checklist) for the module-order view of the same
work.

**Repo state (2026-08-01): Tiers 1–4b complete; Tier 4c's foundations complete, its screens not
started. 339 tests, 3 skipped.**

Everything the UI binds to now exists: schema v2 with the error log and the project's first
migration, `KEY_USER_REQUESTED` and the duplicate guard, Login Flow v2, per-feed refresh, the
mark-old rule, connectivity, the theme, and `sanitizeEpisodeHtml`.

**What is not written: the screens themselves.** S1–S8, their `UiState`/`UiEvent` types, their
ViewModels, and the `NavHost` that joins them. `MainActivity` renders a placeholder inside the real
theme. That is the whole of what remains in 4c, and it is no longer blocked on anything — every port
it needs is declared *and* implemented. Nothing in this project has ever run on a device.

Each tier's completion note below is kept as written at the time, in build order.

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

**Update (2026-07-30): Tier 2 is complete.** 24 + 5 = 29 new tests, `./gradlew ktlintCheck detekt
test assembleDebug` green across the whole repo. Both library choices deviated from CLAUDE.md's
named picks — see below and `docs/decisions/0005`/`0006`.

- [x] **Feed parsing** (inside `:core:feed`) — ~~Stalla~~ **`com.prof18.rssparser`** integration
  mapping RSS/Atom bytes → `Feed`/`Episode`. Stalla (CLAUDE.md's primary pick) turned out to be
  unmaintained since 2021; switched to the fallback CLAUDE.md itself names, put to the author as a
  question first (`docs/decisions/0005`). Tested against static fixtures in
  `src/test/resources/feeds/` (missing GUIDs, duplicate GUIDs, missing enclosures, bad dates, CDATA
  HTML, a genuinely non-UTF-8-encoded fixture, no `itunes:duration`) — no HTTP call in these tests
  at all (architecture.md §7). **Needs Robolectric** in local unit tests, unexpectedly — rssparser's
  Android Kotlin-Multiplatform target resolves `org.xmlpull.v1.XmlPullParserFactory` at runtime,
  which only a real device or Robolectric provides. Still Tier 1/2 in spirit per CLAUDE.md §4
  (headless, no emulator) — see `docs/decisions/0005` for the full explanation.
- [x] **Tag rewriting** (inside `:core:download`) — jaudiotagger integration via
  **`com.github.Adonai:jaudiotagger`** (the Android-compatible fork, via JitPack — not the stale
  upstream Maven Central artifact, which also turned out unmaintained since 2021; see
  `docs/decisions/0006`). Writes title/artist/album/year/genre/track/comment to a real temp
  `java.io.File` (an ffmpeg-generated silent MP3 fixture); best-effort per-field fallback via
  `TagWriteOutcome.PartialSuccess`, and a container-level `Failure` for an unreadable/corrupt file
  — never an exception. No Android, no network, no Robolectric needed (jaudiotagger has no Android
  dependency at all).

Both sit logically inside Android library modules but their core logic has no Android/network
dependency — built and tested as isolated classes, not yet wired into the modules' Android
scaffolding (WorkManager/SAF, Tier 4b).

## Tier 3 — Require networking mocks (MockWebServer)

**Update (2026-07-30): Tier 3 is complete.** 58 new tests (20 + 13 fetch + 2 parse/compose + 23
sync-side, incl. the invariant + timestamp cases), `./gradlew ktlintCheck detekt test
assembleDebug` green — 185 tests total. Reading the reference servers' source turned up two things
neither CLAUDE.md nor the API docs record; see `docs/decisions/0008` and `0009`.

- [x] **`:core:gpodder`** — Retrofit/OkHttp client implementing `GpodderClient`. Asserts exact
  request shape (paths, query params, bare-JSON-array body, Basic auth header) and both timestamp
  formats, plus 401/500/timeout/malformed-body handling. **Open decision #2 resolved**
  (`docs/decisions/0009`): `add` without `since` is the full current set and is disjoint from
  `remove`, so CLAUDE.md §5's `set = add − remove` was correct as specified. **Also resolved open
  decision #3** — this module is now `kotlin("jvm")`, not an Android library
  (`docs/decisions/0007`).
  - ⚠️ **`nextcloud-gpodder` silently discards `DOWNLOAD`/`DELETE` actions on POST and returns
    200 anyway** (`filterOnlyPlays`, verified at source). Cross-client download dedup is therefore
    impossible against real Nextcloud; skip-as-`PLAY` is unaffected, and Podsilo's own local ledger
    still prevents re-downloads. Decision (author-approved): keep emitting `DOWNLOAD` and document
    it — `docs/decisions/0008`. Note `opodsync` *does* store `DOWNLOAD`, so it will not reproduce
    this.
  - ⚠️ CLAUDE.md §11's "ISO-8601 **without** offset" for the per-action timestamp is **stale** —
    both servers now emit an offset (`+00:00`) or `Z`. Parsing is lenient across all three forms;
    ADR 0003 amended.
  - ✅ **Verified live (2026-07-31)** against `opodsync` 0.5.3 in
    `.devcontainer/docker-compose.yml`: `OpodsyncIntegrationTest` runs green (3 tests, 0 skipped),
    turning ADR 0009's source-read contract into a tested one. `nextcloud-gpodder` remains
    source-read only.
- [x] **Feed HTTP fetch layer** (inside `:core:feed`, on top of Tier 2's parsing) — `FeedFetcher`
  with conditional GET (`ETag`/`If-None-Match`, `Last-Modified`/`If-Modified-Since`), 304 →
  `NotModified`, redirects followed, and 4xx/5xx/timeout/unreachable-host all returned as
  `FeedFetchResult` values rather than thrown (CLAUDE.md §8).
- [x] **No-auto-download invariant** (CLAUDE.md §7 item 6, listed below as "worth doing early") —
  sync half in `:core:sync`'s `NoAutoDownloadInvariantTest` (large fresh subscription list, repeated
  passes, and 500 inbound remote actions all produce zero posted actions and zero self-created
  ledger rows); parse half in `:core:feed` (a 500-episode feed parses with no ledger/client to
  touch). The "downloads exactly zero files" half still needs `DownloadWorker` — Tier 4b.

Both are plain-JVM (OkHttp/Retrofit need no Android runtime) — CLAUDE.md's own Tier 1 definition
already includes MockWebServer; this is just the sub-bucket called out separately. `:core:feed`'s
fetch tests do run on the plain JVM runner; only its *parser* tests need Robolectric
(`docs/decisions/0005`), which is why the fetch→parse composition test lives in the parser's test
class rather than the fetcher's.

## Tier 4 — Require Android framework (Robolectric, then real device/emulator)

### 4a. Robolectric-testable, no real device

**Update (2026-07-31): Tier 4a is complete.** `:core:database` and `:core:datastore` are
implemented and tested — 27 new tests (20 Room + 7 DataStore), all four modules
(`:core:model` / `:core:sync` / `:core:database` / `:core:datastore`) green on `test` +
`ktlintCheck` + `detekt`. Room + KSP2 (2.3.10, the decoupled scheme — no `2.4.10-x` build exists) +
DataStore-Preferences added to the version catalog, all pre-approved by CLAUDE.md §3. Two model-port
additions surfaced and were made: a `SettingsRepository` port (+ `NamingSettings`/`NextcloudAccount`/
`NextcloudCredentials`/`TitleCleanupRuleSetting` types) that `:core:datastore` implements, and an
`observeEpisodes(filter): Flow<List<EpisodeListItem>>` method on `EpisodeLedgerRepository` — the
row-typed `observe(filter)` genuinely can't express "New" (a new episode has no ledger row), so the
UI list needs an `Episode`-plus-nullable-ledger type; the Room impl resolves it (incl. the
`firstSeenAt` backlog cutoff) in SQL. New ADR: `docs/decisions/0010` (app-password cipher behind an
interface so the Keystore stays out of the JVM test path).

- [x] **`:core:database`** — Room entities/DAOs/migrations, in-memory DB tests; implements the
  four repository ports, entity↔domain mapping at the boundary (architecture.md §4). Ledger table
  has **no** FK to episodes (verified in the exported `schemas/…/1.json`), so a feed removal
  cascade-deletes episodes but keeps the ledger — `SubscriptionMirroringTest` proves a re-subscribe
  doesn't re-download. Hilt `@Binds` wiring deferred to `:app` (Tier 4c); the repos are plain
  constructor-injectable classes.
- [x] **`:core:datastore`** — DataStore Preferences + Keystore-backed encryption for the
  Nextcloud app password (`KeystoreAppPasswordCipher`, behind `AppPasswordCipher` — see
  `docs/decisions/0010`). The DataStore serialisation is JVM-testable with a fake cipher (no
  Robolectric); the real Keystore round-trip needs an instrumented test (Tier 4b), stated plainly.

### 4b. Needs instrumented tests / real emulator (SAF, WorkManager, ContentResolver)

**Update (2026-07-31): Tier 4b is complete.** 54 new tests (269 total, 3 skipped);
`./gradlew ktlintCheck detekt test assembleDebug` green across the whole repo. It turned out to
need **less** emulator than this heading assumed: WorkManager's `TestListenableWorkerBuilder` runs
under Robolectric, and the SAF write went behind a `DownloadTarget` port
(`docs/decisions/0011`, author-approved) so the pipeline around it is Tier-1 testable. Hilt was
pulled forward from 4c — a `@HiltWorker` is how a worker gets its dependencies, and the alternative
was the service locator CLAUDE.md §3 forbids.

- [x] **`:core:download`'s `DownloadWorker`** — `EnclosureDownloader` (resumable `Range` fetch into
  the app cache: resume, cancel, disk-full, 404, redirect, server-ignores-Range, 416, truncated
  body), `EpisodeDownloader` (the cache→verify→name→tag→deliver→cleanup pipeline), and the worker
  itself (ledger transitions, foreground notification, `SyncTrigger`). 39 tests.
  - ⚠️ **`SafDownloadTarget` is not tested** — a `DocumentFile` write needs a real
    `DocumentsProvider`. Everything *around* it is; the seam moves the untested surface down to the
    smallest possible piece rather than eliminating it (ADR 0011).
- [x] **`FeedRefreshWorker`** (over a new plain `FeedRefresher`) and **`:app`'s `SyncWorker`** (thin
  `CoroutineWorker` over `SyncOrchestrator`, built per pass by `SyncOrchestratorFactory` because the
  GPodder client depends on the credentials in force right now).
- [x] **SAF folder-grant flow** — `DownloadFolderAccess` takes the persistable permission and
  re-checks it on demand (`NotChosen`/`Granted`/`Revoked`), tested under Robolectric, which does
  implement persisted URI permissions. **The picker itself** (`ACTION_OPEN_DOCUMENT_TREE` via an
  `ActivityResultContract`) is a Compose concern and stays in 4c.
- [x] Foreground service notification for active downloads — `DownloadNotifications` +
  `setForeground(... FOREGROUND_SERVICE_TYPE_DATA_SYNC)`, progress throttled to 1 Hz. Never
  displayed on a real device (see the gap above).
- [x] **Ports extended** for the workers: `FeedRepository.getAll`/`get`/`updateRefreshMetadata`,
  `EpisodeRepository.get`, `EpisodeLedgerRepository.get`, and a `GpodderClientFactory` port so
  `SyncWorker` is testable with a fake client.

### 4c. Compose UI (emulator recommended, per CLAUDE.md's Tier 3 host-emulator path)

**Update (2026-08-01): designed, not yet built.** All eight screens and every state
`docs/UI.md` enumerates are drawn (light and dark), and the UI↔logic contract is written down in
**`docs/UI_interface.md`** — per-screen state classes, events, effects, the corner cases (state changes
arriving under the user, process death, data shapes that break naive rendering), notifications,
accessibility, motion, and the spacing invariants. Read it before writing a Composable; it also
carries the gap list the tasks below are derived from. Two design decisions were promoted into
`docs/architecture.md` (§4/§5/§7/§9 and §12).

The order matters: the `:core:model` declarations come first so the four feature tasks can then be
built in parallel, and the one genuinely blocking item is an **ADR, not code**.

- [x] **Decisions settled (2026-08-01)** — ADRs 0012 (terminal states re-openable), 0013 (backlog
  cutoff is written `SKIPPED` rows), 0014 (bulk download allowed as a command), 0015 (Coil +
  Lucide), 0016 (`java.time` behind `EpochTime`, no new dependency). Nothing below is blocked on a
  decision any more. Three of those amended CLAUDE.md (§1, §5, §10 step 8) and one amended README.
- [x] **`gradle/libs.versions.toml`** — Coil 3.5.0 (`coil-compose` + `coil-network-okhttp`, so it
  reuses the pinned OkHttp rather than bringing a second HTTP stack) and
  `com.composables:icons-lucide-android` 2.2.1. Versions and licences checked against Maven Central
  metadata, not recalled; `docs/third-party.md` updated. Neither is *used* yet — that lands with the
  first screen.
- [x] **`:core:model` additions** — all of them, plus the implementations the widened ports forced.
  19 new tests (288 total, 3 skipped); `ktlintCheck detekt test` green.
  - `Episode.link` (nullable, defaulted — not yet mapped in `:core:feed` or stored; that needs
    schema v2), `EpochTime` (ADR 0016), `LogRepository` + `LogEntry`/`NewLogEntry`/`LogCategory`,
    `ConnectivityMonitor` + `Connectivity`, `NextcloudLoginFlowClient` + `LoginFlow`/`LoginResult`,
    `EpisodeLedgerRepository.upsertAll`/`previewUndecided`, `DownloadTarget.freeBytes`, and the four
    `SettingsRepository` values with `ThemePreference`/`SwipeMapping`/`SwipeAction`/`OlderThan`.
  - **Not purely declarations, in the end.** Three of these carry logic that has two callers each
    and would otherwise be duplicated: `EpochTime`'s unit-naming, `SwipeMapping.with`'s
    "the two directions never hold the same action" swap, and `OlderThan.cutoffMillis`'s calendar
    arithmetic. All three are table-tested.
  - Widening the ports also required implementing them: `:core:datastore` persists the four new
    settings (unknown enum names fall back to the default rather than throwing), `:core:database`
    implements `upsertAll`/`previewUndecided`, `SafDownloadTarget` implements `freeBytes` via
    `fstatvfs` on the tree URI, and five test fakes grew the new members.
  - **The DAO was split**: `EpisodeLedgerDao` (ledger + outbox) and `EpisodeListDao` (the joins and
    `countUndecidedByFeed`). detekt's `TooManyFunctions` flagged it, and the old KDoc had already
    confessed to two jobs in one sentence — worth splitting rather than suppressing.
- [x] **`:core:database`** — `error_log` table + `LogDao` (collapse-on-identity and eviction both as
  queries), `episodes.link`, and the project's **first migration — schema v2** — with a
  `MigrationTest` that runs against the *exported v1 schema* and asserts the ledger row survives.
  `DatabaseModule` registers the migration and deliberately does not fall back destructively. The
  `firstSeenAt` cutoff is **removed** from `observeNewEpisodes` and `LedgerFilter.includeBacklog` is
  gone (ADR 0013), with a regression test pinning that a pre-`firstSeenAt` episode still appears.
- [x] **`:core:download`** — `KEY_USER_REQUESTED` on the work request, and the pre-flight
  duplicate-file guard behind it (reuses the existing `DownloadTarget.existingNames`, no new port
  method). Test that the flag is the *only* path past the terminal-row refusal, so the
  no-auto-download invariant stays provable. Per ADR 0012 a re-decision resets `attempts` and
  `lastError` and re-posts its action, but **keeps `writtenFileName`** — that field is what the
  guard checks, and a test should pin it.
- [x] **`:core:feed`** — apply the *mark old as played* rule to newly-parsed episodes after a
  refresh when the setting is on (ADR 0013). `FeedRefresher` becomes the first non-UI component that
  writes ledger rows: extend `NoAutoDownloadInvariantTest` to assert it writes `SKIPPED` only, never
  `QUEUED`, and still enqueues no download work.
- [x] **`:core:gpodder`** — `NextcloudLoginFlowClient` (`POST /index.php/login/v2` + poll). Stays a
  JVM module, MockWebServer-testable; success is only claimed after an authenticated
  `GET /subscriptions` returns 200, because a completed login flow is not proof gpoddersync is
  installed.
- [x] **`FeedRefreshWorker`** — a `KEY_FEED_URL` input so S2's pull-to-refresh can scope to one feed.
  Same worker, not a second one.
- [ ] **`:feature:settings`** — S4 (settings), S5 (Nextcloud connection dialog), S6 (naming editor
  with live preview over the already-tested `resolve()`). **Not started.** Every port it needs now
  exists, including `NextcloudLoginFlowClient` and the four persisted settings values.
- [~] **`:feature:episodes`** — S1 (podcast list), S2 (episode list), S3 (detail sheet). **S1 belongs
  here, not in `:app`:** it shares the ledger query and the `EpisodeUi` projection with S2, and a
  badge that disagrees with the list it opens is exactly the bug co-location prevents.
  **Only `sanitizeEpisodeHtml` is built** (16 tests) — the module is otherwise still scaffolding.
  The three screens, their `UiState`/`UiEvent` types and their ViewModels are **not written**.
- [~] **`:app`** — Hilt wiring was **done in 4b**. Done now: `@AndroidEntryPoint` on `MainActivity`,
  `PodsiloTheme` (one seed, two schemes, **dynamic colour off**) applied at the root from the
  persisted preference, `AndroidConnectivityMonitor`, and `WorkScheduler`'s additions
  (`userRequested` downloads, per-feed refresh, bulk enqueue, work observation).
  **Still to do: navigation and S7 (activity) + S8 (error log).**
- [~] **Error-log write points** — `FeedRefresher` writes them (feed HTTP failures, unreachable
  hosts, unparseable XML), with a test asserting the plain sentence comes first and the technical
  half is separate. **Still to do:** `SyncOrchestrator`/`SyncWorker`, `EpisodeDownloader`/
  `DownloadWorker`, and the S5 auth flow — plus the test that no entry ever contains the app
  password, the Basic-auth header, or a URL with credentials.

### Worth doing early despite appearing last

- [x] **`sanitizeEpisodeHtml` table test** (`:feature:episodes`). `Episode.description` is stored raw
  and sanitised at render time (architecture §4), so this pure function is the only place hostile feed
  HTML meets a renderer — scripts, styles, iframes, remote images and tracking pixels out; paragraphs,
  emphasis, lists and links in. Tier 1 testable and cheap; no reason it waits for the screen it serves.
- [x] CLAUDE.md §7 item 6, the no-auto-download invariant test — **done in Tier 3**, in two halves
  (`:core:sync`'s `NoAutoDownloadInvariantTest` + `:core:feed`'s 500-episode parse test), plus the
  `subscription_change/create` assertion in `:core:gpodder`'s MockWebServer test. **Tier 4b closes
  the "zero *files*" half**: `FeedRefreshWorkerTest`'s 500-episode refresh writes episode rows and
  nothing else (the refresher has no ledger/download/GPodder dependency at all), and
  `DownloadWorkerTest` proves the only path to a file is an explicit per-episode enqueue that also
  refuses to act on an already-terminal ledger row.

## Decisions — all settled

Tier 4c was blocked on four author decisions between 2026-08-01's design pass and now. All four are
resolved and recorded; `docs/architecture.md` §12 is the index:

| Was blocking | Settled as |
|---|---|
| ADR 0012 draft — the four open points | Accepted: a re-decision behaves exactly like a first one; *Mark as played* follows the same rules; `writtenFileName` survives; the "already in folder" outcome is counted nowhere |
| Backlog cutoff: filter or written rows | **ADR 0013** — written `SKIPPED` rows; the read-time cutoff is removed; CLAUDE.md §5 amended |
| *Download all* vs. "no download all" | **ADR 0014** — allowed as a *command*, never as a *rule*; CLAUDE.md §1 and README amended |
| Three unapproved dependencies | **ADR 0015** — Coil and Lucide Compose accepted; **ADR 0016** — no time dependency at all, `java.time` behind an `EpochTime` seam |

A pattern worth keeping: ADRs 0001–0011 were each written when the decision was actually made, and
all are "Accepted". 0012 was written *ahead* of its decision and spent a week as a draft blocking
code. Write the ADR when the decision happens — not before, to reserve a number, and not after, to
document what shipped.
