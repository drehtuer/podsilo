# TODO — Implementation order

Implementation order for Podsilo, sorted by testability tier (no dependencies → external
libraries → networking mocks → Android/emulator), rather than strictly by CLAUDE.md §10's module
order. Cross-references `docs/architecture.md`. See that document's [§13 build-order
checklist](docs/architecture.md#13-build-order-checklist) for the module-order view of the same
work.

**Repo state (2026-08-02): Tiers 1–4b complete; Tier 4c complete — all eight screens built,
navigable, and icon-complete.** 502 tests, 3 skipped, plus 6 instrumented.

**Device test set (2026-08-11, Pixel 10a / Android 17): 60 tests, 52 passed, 2 failed, 6 skipped.**
The first run since the 2026-08-10 row changes, and it caught what CI structurally cannot: two
conformance tests still assert the pre-change UI shape. **Neither is an app bug** — *Choose folder*
moved into the row overflow, and S1's now-scrolling chip row makes `hasScrollAction()` ambiguous.
Both are written up in `docs/backlog.md` and `docs/dev-environment.md` §6. The 6 skips are the
long-standing `SafDownloadTargetInstrumentedTest` opt-out on an install with no SAF grant.

**Update (2026-08-09): v0.3.0 is released and running on the author's phone, and four issues came
back from using it.** They are planned as **[Tier 5](#tier-5--reported-issues-from-using-v030)** at
the end of this file, in the order to solve them. One of them (#49, undo) contradicts a shipped
design rule and is **blocked on an author decision**; the other three are not.

**The app runs.** It was installed on the Tier 2 emulator, launched, and rendered S1 — the first time
any of this has executed as an application rather than as a test. Its first run found three bugs no
unit test could (`docs/journal.md`), one of them serious: `:core:naming`'s token regex used a bare
`}`, which the JVM accepts and Android's ICU engine rejects, so **every filename in the app** failed
to resolve on a device while 437 JVM tests stayed green. Fixed, and `docs/decisions/0017` is the rule
that came out of it.

Everything the UI binds to exists: schema v3 with the error log and its migrations,
`KEY_USER_REQUESTED` and the duplicate guard, Login Flow v2, per-feed refresh, the mark-old rule,
connectivity, the theme, and `sanitizeEpisodeHtml`.

**Nothing is unwritten.** `notBuiltYet` — the snackbar that named a missing screen — has no callers
left and was deleted. `:core:ui` now holds the icon allow-list (`docs/UI.md` §18, 27 icons, all
asserted to resolve) and the spacing invariants that were duplicated across the feature modules.

**S6 is worth looking at first.** Its live preview renders four cases through the real
`NamingTemplateEngine` — a recent episode, a missing date (`00000000`, ADR 0004), a UTF-8
byte-truncated over-long title, and FAT32 sanitisation — so the templates are verifiable at a
glance rather than on trust.

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

### 4c. Compose UI (Tier 2 emulator works; see `docs/dev-environment.md` §6)

**Update (2026-08-02, later): six of eight screens.** `:feature:settings` adds S4, S5 and S6, all
reachable from S1's gear. Only S7 (activity) and S8 (error log) remain.

**Update (2026-08-02): S1, S2 and S3 are built and navigable, and the app runs.** Each screen
renders its view model's state and emits events, deciding nothing locally. `:app` has a `NavHost`
(S1 → S2 → S3), the SAF picker, and link opening. Compose tests run under Robolectric as Tier 1;
six instrumented tests run on the emulator. The first launch found three bugs no unit test could —
see `docs/journal.md` and `docs/decisions/0017`.

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
- [x] **`:feature:settings`** — S4, S5 and S6, **built** (42 tests). Every control commits on change;
  the one exception is the bulk *mark as played*, which goes through the mandatory preview
  (`docs/decisions/0013`) because it reaches the shared action log and cannot be undone in bulk.
  S5 is Login Flow v2 only — no password field exists in the module, and a Compose test asserts it.
  Its step order is the feature: success is claimed only after the authenticated `GET /subscriptions`
  returns 200, and the app password is discarded on any earlier failure. S6 owns no naming logic at
  all; every preview line calls the same `resolve()` a download calls.
- [~] **`:feature:episodes`** — S1 (podcast list), S2 (episode list), S3 (detail sheet). **S1 belongs
  here, not in `:app`:** it shares the ledger query and the `EpisodeUi` projection with S2, and a
  badge that disagrees with the list it opens is exactly the bug co-location prevents.
  - **Built:** `sanitizeEpisodeHtml` (16 tests); the `EpisodeUi` projection with its `actions` set;
    `TriageWriter` (the one place a UI decision becomes a ledger row); **S2's state types, events,
    effects and `EpisodeListViewModel`** with 18 tests covering the traps — a tap never triages, a
    swipe obeys the configured mapping, bulk writes are one transaction, and only *Download again*
    carries `userRequested`; and **S2's screen** (`EpisodeListScreen` + `EpisodeRow`), stateless
    against that view model, with 14 Robolectric Compose tests and 2 instrumented ones.
  - **S1 and S3 are built too.** `PodcastListViewModel` freezes its sort on cold start and on each
    explicit refresh — a background sync updates rows in place and never reorders them
    (`docs/UI.md` §4) — and a feed subscribed since the last freeze is appended rather than sorted
    in. `EpisodeDetailViewModel` shares `TriageWriter` and `EpisodeScheduler` with S2, so the same
    decision taken in the sheet and in the row writes an identical ledger row. 35 tests between them.
  - **The ledger port was split**, mirroring the DAO split: `EpisodeListRepository` owns the four
    UI-facing joins, `EpisodeLedgerRepository` keeps the durable record and its outbox. detekt
    flagged both the interface and its implementation, which was the signal the seam was due.
  - **`EpisodeUi` now matches `docs/UI_interface.md` §1.** `FailureUi` carries a stored `ErrorCause`
    and `retryable` (schema v3), so ADR 0011's "that row offers *Choose folder*, never *Retry*" is
    enforceable and tested rather than aspirational. `QueueStatus` (paused banner) and
    `MonthSection` (sticky headers) are built too.
- [~] **`:app`** — Hilt wiring was **done in 4b**. Done now: `@AndroidEntryPoint` on `MainActivity`,
  `PodsiloTheme` (one seed, two schemes, **dynamic colour off**) applied at the root from the
  persisted preference, `AndroidConnectivityMonitor`, and `WorkScheduler`'s additions
  (`userRequested` downloads, per-feed refresh, bulk enqueue, work observation).
  **Navigation is done**: `PodsiloNavHost` (S1 → S2 → S3, S1 → S4 → S5/S6), `EpisodeViewModelFactory`, the five
  screen-facing adapters (`WorkEpisodeScheduler`, which suspends until a refresh finishes rather
  than returning at enqueue time; folder status, folder label, free space, naming preview), the SAF
  picker and link opening. **Still to do: S7 (activity) + S8 (error log)** — the events that would
  open them, and S4–S6, surface a snackbar naming the missing screen.
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

---

## Tier 5 — Reported issues from using v0.3.0

Four GitHub issues, filed after the release went on the author's phone. Ordered by *what unblocks
what*, not by severity: **I1 builds the app bar that I3 needs**, and **I4 is last because it was the
only one blocked on a decision** — all four are now settled, at the end of this section.

Each entry below records the root cause **found by reading the code**, not the issue's own guess.
Three of the four turned out to be something other than what the issue text assumed — worth knowing
before starting, because two of them are "specified, wired at one end, never connected in the
middle", which is now the **fourth and fifth** times this project has hit that shape (after
pull-to-refresh, the artwork slot and the swipe gesture — see `EpisodeSwipe.kt`'s header comment).

**Convention:** one branch and one PR per issue, Conventional Commits, `./gradlew ktlintCheck detekt
test` green before each is called done (CLAUDE.md §12).

### I1 — [#48] The filter row is clipped on a narrow screen (bug) — **done 2026-08-09**

**Built.** 7 JVM tests + 3 instrumented; 635 JVM tests total, 0 failures, 3 skipped;
`ktlintCheck detekt test` green across the repo. The chip-reachability test was verified to **fail**
against the unfixed row (`Semantic Node has no parent layout with a Scroll SemanticsAction`) before
the fix was restored. The three instrumented tests **compile but have not been run** — no device was
attached; they are written for the next time one is.

**Root cause — two faults, and the larger one is not in the chip row.**

1. **`EpisodeListScreen` has no `TopAppBar` at all.** It is the only one of the eight screens
   without one (S1, S3, S4, S6, S7 and S8 all have one). So `docs/UI.md` §5's app bar —
   `‹ Der Podcast [filter] [activity]` plus the `⋮` carrying *Download all (n)* — was never built,
   the screen's content begins at y = 0 **underneath the status bar** (visible in the issue's
   screenshot), and there is no back affordance, no feed title, and nowhere for I3's selection bar
   to go.
2. **`FilterChips` is a fixed-width `Row`** (`EpisodeListScreen.kt:154`) — four chips at their
   intrinsic widths with `spacedBy(8.dp)`, no scroll, no wrap, so the fourth chip (`All`) is simply
   clipped off the right edge and unreachable by any gesture.
   - **Correction (2026-08-09, on implementing it):** this entry originally also blamed
     `sizeIn(minHeight = MinTouchTarget)` for the "overlapping the action labels" half of the report.
     A layout test at 320 dp disproves that — the chip row and the first episode row do not overlap,
     with or without the fix. What the screenshot shows is a row *scrolled under* the fixed chip row,
     whose bottom edge (its action buttons) then sits hard against the chips with no gap to read as a
     boundary. That is a legibility fault, fixed by giving the row vertical padding, not a layout one.
     The clipping is the real layout bug.

**Work**

- [x] S2's app bar per `docs/UI.md` §5 — `EpisodeListAppBar.kt`: back, feed title (falling back to
      the URL), the activity action §3's map draws, and the `⋮` overflow with *Download all (n)*,
      which finally gives that event an emitter. `Scaffold(topBar = …)` applies the window insets.
      Two new events (`BackClicked`, `ActivityClicked`) resolve to *effects* — the screen owns no
      `NavController`.
      - **Its own file.** detekt's `TooManyFunctions` flagged `EpisodeListScreen.kt` at 11, and the
        split it asked for is a real seam rather than a threshold to suppress: the screen owns the
        list and its chrome, the app bar owns navigation out of the screen.
      - **No `[filter]` icon**, despite §5's diagram label: §18's allow-list contains none, and the
        filter is the chip row directly beneath. Recorded in `docs/UI_interface.md` §3.
- [x] The chip row is **one horizontally scrollable line** (D3), with explicit vertical padding so
      the 48 dp touch target §12.12 requires is not handed to its neighbours.

**Tests** — 7 Robolectric Compose tests (chip reachability at `w320dp`, no overlap, the app bar's
title/back/activity, the overflow's count, its absence at zero, and its disabled-with-a-reason state
while paused) + 1 view-model test that the two app-bar events navigate and write nothing. **The
reachability test was checked against the unfixed row and fails there** (CLAUDE.md §7's
regression-test rule), so it pins the bug rather than merely the fix.

Three **instrumented** tests were added for the same screen, because #48 was a measured-layout fault
that 627 JVM tests never saw: chip reachability at the *device's* width, density and font scale; the
app bar; and the overflow as a **real popup window** rather than Robolectric's shadow. They compile;
**they have not been run** — no device was attached at the time.

**Correction to the plan above:** the "overlap" half of the report is not reproduced by the layout.
A test at 320 dp shows the chip row and the first episode row do not overlap, with or without the
fix. See the corrected root-cause note below.

### I2 — [#47] Downloads appear delayed in Activity (bug) — **done 2026-08-09**

**Built.** 18 new tests (653 total, 0 failures, 3 skipped); `ktlintCheck detekt test assembleDebug`
green. Both faults below are fixed, and a **third** turned up while writing the queries — see the
adjacent-bug note at the end.

**Root cause — two independent faults, both real, neither one the event-bus problem the issue
guesses at.** S7 already observes Room `Flow`s, so the plumbing the issue proposes exists.

1. **No download progress is ever published, anywhere.** `DownloadWorker` reports bytes only to
   `DownloadNotifications` (`DownloadWorker.kt:145`); it never calls `setProgress`, so
   `WorkInfo.progress` is always empty. Nothing observes it either — `EpisodeUi.progress` is left at
   its `null` default by every caller of `toUi`, and `WorkScheduler.observeDownloadWork()`
   (`WorkScheduler.kt:70`) **has no caller in the repository**. The consequence: every `DOWNLOADING`
   row in S2, S3 **and S7** renders the indeterminate *resuming* bar for the entire download and
   never shows a percentage or a byte count. `docs/UI.md` §12.2 and `docs/UI_interface.md` §7 are
   specified in full and unimplemented.
2. **S7 re-projects the entire ledger, with an N+1 query, on every emission.**
   `ActivityViewModel.state` observes `LedgerFilter(state = ALL)` — *every* ledger row on the device,
   which after triage is thousands (the author's has ~9,500 episodes) — and then, in
   `toUiState`, calls `feedRepository.getAll()` plus one suspend `episodeRepository.get(episodeKey)`
   **per row** before filtering down to the handful that are downloading, queued or failed. Every
   ledger write anywhere re-runs the whole thing. That is the latency in the report, and it gets
   worse the more the app is used — which matches "empty or stale until you navigate away and come
   back".

**Work**

- [x] `DownloadWorker` publishes progress through `setProgressAsync` **inside the existing 1 Hz
      notification tick**, so one clock drives the notification, the row, S1 and S7 and they cannot
      disagree (`docs/UI_interface.md` §7). `setProgressAsync` rather than the suspending
      `setProgress` because the downloader's callback is an ordinary function.
- [x] Each request carries an **episode-key tag**. `WorkInfo` exposes its tags and *not* the unique
      work name it was enqueued under, so without one there is no way to map a queued download back
      to its episode — which is what S1's per-feed count and S7's rows both need.
- [x] `DownloadWorkMonitor` (port, `:feature:episodes`) + `WorkManagerDownloadMonitor` (`:app`) —
      `observeDownloadWork()` finally has a caller. §7's three cases are resolved in **one place**,
      `EpisodeListItem.toUi`, so S2, S3 and S7 cannot answer them differently: live update →
      determinate; live work, no update → *resuming*; **no live work → *queued*** and the view model
      re-enqueues it once.
- [x] S7's queries are narrow and resolved in SQL: `observeInFlight()` (the three states it renders,
      joined), `observeRecentlyDelivered(since, limit)` (`LIMIT` in SQL, not `take()` in Kotlin) and
      `observeUnsyncedCount()` (`COUNT(*)`, not `getUnsynced().size`). No schema change, no migration.
      `observeUnsyncedCount` sits on `EpisodeListRepository` rather than beside the outbox drain
      because it is a *screen read* — detekt's function ceiling on `EpisodeLedgerDao` forced the
      question and the seam gave the answer.
- [x] **`FeedUi.activeDownloads` is populated at last** — it had a default of `0` and no assignment
      anywhere, so S1's "n downloading" line and the app-bar activity badge were both permanently
      dead. It comes from the same bounded in-flight query, counting `QUEUED`/`DOWNLOADING` only
      (an `ERROR` row is in flight for S7 but is not a download in progress).

**Tests** — 18 new. The worker publishes progress and its tag round-trips; §7's three cases in both
S2 and S3; the re-enqueue happens **once** and never touches an undecided or `QUEUED` row, and never
carries `userRequested` (the no-auto-download invariant at the one path that enqueues without a tap);
the DAO queries select only what S7 renders, order and limit correctly, and treat the delivered
cursor as a display filter that deletes nothing; and **S7's view model, which had no test at all**
before this — including that a thousand decided ledger rows do not enlarge the in-flight result.

**Adjacent bug, found while writing the queries and fixed here.** Not one of the three list queries
projected `lastErrorCause` or `lastErrorRetryable`. The columns exist (schema v3), the entity has
them, and every `SELECT` simply left them out — so Room saw `NULL` and the fields fell back to their
defaults. The consequence was ADR 0011 and `docs/UI.md` §12.11 quietly not working: a
`FOLDER_UNAVAILABLE` failure could never render *Choose folder* instead of a *Retry* button that
cannot possibly succeed, because the screen had no way to tell one failure from another. Fixed in
all three queries, with a regression test **verified to fail against the unprojected `SELECT`s**.

### I3 — [#46] Multi-episode selection in the episode list (enhancement) — **done 2026-08-09**

**Built.** 13 new tests (666 total, 0 failures, 3 skipped) + 2 instrumented;
`ktlintCheck detekt test` green. The estimate below held: the view model needed **one** new event
pair, and everything else was the UI that had never been written.

**Already designed, and already three-quarters built.** `docs/UI.md` §5 *Batch triage* specifies it,
and `EpisodeListViewModel` implements the whole selection model — `SelectionStarted`,
`SelectionToggled`, `SelectionCleared`, `SelectAllInFilter`, `BulkConfirmed`, the "empty selection
leaves selection mode" rule, and the "a filter change drops the selection" rule — with unit tests
covering it (`EpisodeListViewModelTest`). `EpisodeRow` already renders a selected background and
already routes a tap to `SelectionToggled` while in selection mode.

**What is missing is only the way in and the way to act.**

- `EpisodeRow` uses `clickable`, not `combinedClickable` (`EpisodeRow.kt:60`), so **long-press fires
  nothing** and `SelectionStarted` has no emitter. Selection mode is currently unreachable.
- With no app bar (I1), there is nowhere to render `n selected`, *Download*, *Mark as played*,
  *Select all* and ✕.

**Work** — depends on I1.

- [x] `combinedClickable` with `onLongClick` → `SelectionStarted(episodeKey)`.
- [x] The selection app bar, **replacing** the normal one while a selection is live — the count as a
      **live region** so TalkBack announces `n selected` on every change (§12.12), Download, Mark as
      played, *Select all* (scoped to the filter, from `Selection.allInFilter`), and ✕.
      Replacing rather than augmenting: leaving *Back* beside "3 selected" invites leaving the
      screen when the user meant to leave the mode.
- [x] The confirmation naming the count, via a new `SelectionActionRequested`/`SelectionActionDismissed`
      pair and `pendingSelectionAction` — so the bar cannot reach `BulkConfirmed` directly and the
      "name the count before you write" rule is structural, as it already is for *Download all* and
      *Mark all as played*. A filter change drops a pending confirmation along with its selection.
- [x] Accessibility: a **custom accessibility action** on every row, so selection is reachable
      without the long-press §12.12 requires — the same event, not a parallel path — plus a leading
      checkbox in selection mode. That gives `square`/`square-check` their first call site; they had
      been on §18's allow-list for exactly this since it was written.
- [x] `docs/UI_interface.md` §3's `SelectionStarted` correction — done in I1.

**Out of scope, and worth saying explicitly in the issue:** the issue's action list asks for *add to
queue*, *add to playlist*, *remove/delete* and *mark unplayed*. The first three are CLAUDE.md §1
non-goals permanently (no player, no playlists, no file lifecycle management — Podsilo never deletes
a file). *Mark unplayed* does not exist as a state — an undecided episode is one with **no** ledger
row, so it would mean deleting the record that stops an episode being downloaded twice — and is
**declined** (D4): the ledger stays append-only. The issue's
implementation notes (RecyclerView, `ActionMode`, the AndroidX Selection library) describe a View-based
app; this one is Compose, and the selection model they recommend building already exists.

### I4 — [#49] Undo after a swipe (enhancement) — **done 2026-08-09**

**Built** as a deferred write (D1), with **ADR 0021** and the `docs/UI.md` §12.1/§12.3 amendments.
7 new tests (673 total, 0 failures, 3 skipped); `ktlintCheck detekt test assembleDebug` green. The
ledger gained **no delete**, which was the point of choosing this option.

**This contradicts a shipped, deliberate design rule.** `docs/UI.md` §12.3 is titled *"No undo —
re-download instead"*, and §12.1 says a swipe "commits immediately — there is no undo". The reasoning
is not stylistic: **a skip becomes a `PLAY` action in an append-only log on the server**, other
clients act on it, and the GPodder API has no retraction of any kind. Nothing Podsilo can do will
un-send it. That is why §12.3 chose "correct it by acting again" and why every bulk action got a
confirmation dialog instead.

The author has now asked for undo anyway, having hit the accidental swipe in practice. That is a
legitimate reversal of a design call — and **D1 below settles how**: the decision is *deferred*, not
written and reverted. Nothing is written to the ledger and no download is enqueued until the
snackbar window elapses, so an undone swipe leaves no trace anywhere and the shared log is never
touched. It still needs **ADR 0021** written at the point the code is.

**What the code allows today**

- `EpisodeLedgerRepository` has **no delete** — `observe`, `get`, `observeRow`, `upsert`,
  `upsertAll`, `getUnsynced`, `markSynced` and nothing else. That is by design: CLAUDE.md §11 calls
  the ledger row "the only dedup authority, and it must outlive the file". Deferring the write (D1)
  means it stays that way: **do not add one for this.**
- A `DOWNLOAD` action is only posted **after** a download completes, so a download would have been
  the easy half to undo either way — cancel the work, and nothing has reached the server.
- A `PLAY` action is posted by the next sync pass, which may be immediate (`requestSyncNow` fires
  after any download lands) or up to the sync interval away. **That is the whole argument for D1:**
  once a row exists, the moment it is pushed is not under the UI's control, so the only reliably
  reversible state is one where the row was never written.

**Work**

- [x] ADR `0021-undo-for-swipe-triage.md`, written **with** the change. It states the cost as well as
      the decision: a decision made and then immediately killed is silently lost.
- [x] `docs/UI.md` §12.1, §12.3 (retitled), principle 6 and the motion table's "a snackbar never
      carries an action" line — all four contradicted the app otherwise.
- [x] `docs/UI_interface.md` §3.
- [x] The deferred write lives in the **view model**, so the held decision survives recomposition and
      rotation. The row renders the state the decision *will* produce (`EpisodeUi.asPending`), so a
      swipe never looks ignored and the row does not change appearance twice.
- [x] Bulk actions untouched (D2). The row's own action buttons and S3 also commit immediately —
      **scope is swipes only**, since a button press is a deliberate press on a named affordance,
      not a gesture that can be started by trying to scroll.
- [x] Tests: the window expiring writes exactly one row; undo inside it writes none and enqueues
      nothing, *and stays undone after the timer would have fired*; the row renders the pending
      decision with nothing stored; a second swipe commits the first; leaving the screen commits; an
      undo arriving after the window is ignored rather than racing; and bulk still writes at once
      with no undo.
      - `onCleared` is reached through a real `ViewModelStore` rather than by adding a test-only
        method to the production class.
      - detekt flagged `EpisodeListViewModelTest` as too large, so the harness moved to
        `EpisodeListTestHarness` and the undo tests to their own class — the fourth time that
        ceiling has pointed at a real seam.

### Decisions — all four settled (2026-08-09)

Answered by the author in the same session the plan was written, so nothing in Tier 5 is blocked.

| # | Question | Settled as | Consequence |
|---|---|---|---|
| **D1** | How should undo work? | **Defer the write.** The decision is held for the snackbar window and the ledger row is written — and the download enqueued — only when it expires. | I4 needs **no ledger delete** and can never post a `PLAY` the user retracted. The row is optimistic for those seconds, and a decision made and immediately killed is lost; the ADR must state that plainly. |
| **D2** | Does undo replace the bulk confirmation dialogs? | **Both stay.** Undo is for single swipes; bulk actions keep naming their count first. | ADR 0013/0014 stand unamended. I3's selection-mode confirmation is still required. |
| **D3** | Filter chips on a narrow screen? | **Scroll horizontally**, single line. | I1's header height stays fixed; the first episode row does not move down on the narrowest screens. Discoverability of the off-screen chip is the accepted cost — the test asserts it is reachable, not that it is visible. |
| **D4** | *Mark unplayed* / a ledger delete? | **Ignore it.** Not built, and not carried as an open question. | The ledger stays append-only. I3 ships §5's designed set — Download, Mark as played, Select all — and the issue's *mark unplayed* is declined with the rest of its out-of-scope list. |
