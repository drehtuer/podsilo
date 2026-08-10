# Journal

Running log of agent-driven work sessions: what was attempted, what worked, what didn't, and what
needed human correction. Newest entries at the bottom.

---

## 2026-07-30 — Dev container skeleton

**Attempted:** `.devcontainer/` with `devcontainer.json`, `Dockerfile`, and `post-create.sh`, per
CLAUDE.md §4. Explicit constraint from the author: minimal `ubuntu:24.04` base, generic, no reliance
on special images or pinned exotic versions.

**What was built**

- `Dockerfile` — `ubuntu:24.04` + distro packages only, plus Google's Android command-line tools
  (no distro package exists). No devcontainer *features*, no third-party apt repos.
- The Android SDK is **not** baked into the image. `post-create.sh` installs components into
  `$ANDROID_HOME` (`/opt/android-sdk`), which `devcontainer.json` backs with a named volume, so an
  image rebuild does not re-download gigabytes. `sdkmanager` itself lives in `/opt/android-cmdline-tools`
  so the volume mount can never shadow it.
- Three named volumes: SDK, `~/.gradle`, `~/.android`.
- Non-root `dev` user at UID/GID 1000, in a `kvm` group whose GID matches the host's (993 here).

**Verified on this host** (Windows/WSL2, AMD, Docker Engine inside the distro, `/dev/kvm` present)

- Image builds clean.
- `post-create.sh` accepts licences and installs `platform-tools` (r37.0.0, adb 1.0.41),
  `platforms;android-35`, `build-tools;35.0.0`, `emulator` (36.6.11) into the volume; re-running is
  idempotent.
- `aapt2 version` runs; `emulator -version` runs; `emulator -accel-check` reports
  *"KVM (version 12) is installed and usable"* from inside the container as the non-root user.
- Bind-mounted workspace files come out owned by uid 1000, not root.
- `java.nio.charset.Charset.defaultCharset()` is UTF-8 and `ImageIO` has a PNG writer — both matter
  later (§6 naming tests need UTF-8; AGP crunches PNGs through `java.awt`).

**What didn't work first time**

- `sdkmanager --version` failed with *"Could not determine SDK root"*. Because `sdkmanager` sits
  outside `$ANDROID_HOME` by design, **every** invocation needs `--sdk_root`, not just the install
  ones. Wrapped it in a shell function.
- The emulator needs a longer list of X11/xcb libraries than the usual copy-pasted set:
  `libxkbfile1` (needed by `qemu-system-x86_64` itself, not by the Qt UI), `libxcb-cursor0`,
  `libxcb-icccm4`, `libxcb-image0`, `libxcb-keysyms1`, `libxcb-render-util0`, `libxcb-shape0`,
  `libxcb-xkb1`, `libxkbcommon0`, `libxkbcommon-x11-0`, `libice6`, `libsm6`. Found them by running
  `ldd` over the emulator and qemu binaries rather than guessing. Note that a bare `ldd` also reports
  the emulator's *own* bundled libraries (`libQt6*AndroidEmu`, `libandroid-emu-*`, `libc++`,
  `libtcmalloc_minimal`, `libglib2_linux-x86_64`, `libprotobuf`) as "not found" — those are resolved
  by the launcher's `LD_LIBRARY_PATH` and are **not** missing packages. Don't chase them.
- Ubuntu 24.04 ships a stock `ubuntu` user at UID 1000, which collides with the host UID. The
  Dockerfile deletes it before creating `dev`.
- Package renames in noble: `libasound2` → `libasound2t64`.

**Deviations from CLAUDE.md, flagged for the author**

- §4 asks for **Temurin** JDK 17; this uses Ubuntu's `openjdk-17-jdk` (17.0.19) instead, because
  Temurin needs a third-party apt repository and the author asked for a generic container. Easy to
  switch if a Temurin-specific behaviour is ever needed.
- Used the full JDK rather than `-headless`: AGP's PNG crunching goes through `java.awt`/`ImageIO`.

**Not done / not verified**

- `docker-compose.yml` (emulator service + `opodsync` test sync server) and `docs/dev-environment.md`
  are still missing from the §4 deliverable list.
- No Gradle project exists yet, so `./gradlew test` — the actual acceptance test for Tier 1 — has not
  been run. `post-create.sh` skips the warm-up and says so.
- Tier 2 is only proven as far as "`emulator` starts and KVM is usable". No AVD created, no system
  image downloaded (~1.5 GB, gated behind `INSTALL_SYSTEM_IMAGE`), no boot, no
  `connectedAndroidTest`. Tier 3 (adb over TCP to a Windows-hosted emulator) is entirely untested and
  has no helper script yet.
- `runArgs: ["--device=/dev/kvm"]` makes the container fail to start on a host without KVM. It works
  here, but it is a hard dependency for a tier the author may not need — the comment says to delete
  the line.

**Process notes**

- Probing the container (`apt-cache policy`, `ldd`, `curl -I` against Google's download URLs) before
  writing the Dockerfile caught the package renames and the missing-library set cheaply. Worth
  repeating: verify package names against the actual base image instead of trusting recalled ones.

---

## 2026-07-30 (later) — Claude Code inside the dev container

**Attempted:** run Claude Code in the container rather than on the WSL2 host, so the agent sees the
container's JDK, Android SDK, and adb.

**What was built**

- `Dockerfile`: Anthropic's native installer (`https://claude.ai/install.sh`), run **as the `dev`
  user** after the `USER` switch. Chosen over `npm i -g @anthropic-ai/claude-code` because it needs no
  Node at all — Ubuntu 24.04 only ships Node 18, so npm would have meant adding the NodeSource apt
  repo and breaking the "generic container" constraint. Pinnable via `CLAUDE_CODE_VERSION`
  (`stable` default, accepts `latest` or an exact version).
- `CLAUDE_CONFIG_DIR=/home/dev/.claude`, backed by a named volume, so `/login` survives a rebuild.
- `devcontainer.json`: the `anthropic.claude-code` extension is installed **into** the container, and
  the host's `~/.gitconfig` is bind-mounted read-only so commits made in the container carry the right
  identity.

**What the installer actually does** (read the script before piping it to bash; then verified)

- Binary: `~/.local/share/claude/versions/<v>` with a `~/.local/bin/claude` symlink. State and cache:
  `~/.local/state/claude`, `~/.cache/claude`. Config and credentials: `~/.claude` + `~/.claude.json`.
- It does **not** touch `.bashrc`, and warns that `~/.local/bin` is not on `PATH` — the Dockerfile sets
  `PATH` itself.
- That split is what makes the volume design work: the binary is under `~/.local`, so a mount at
  `~/.claude` cannot shadow it. Had the binary lived in `~/.claude`, mounting a volume there would have
  silently broken `claude` after the first rebuild.
- **Verified, not assumed:** with `CLAUDE_CONFIG_DIR` set, `.claude.json` is created *inside* that
  directory (`/home/dev/.claude/.claude.json`) instead of at `$HOME`. That is the whole reason a single
  volume is enough to persist credentials as well as history.

**Verified on this host**

- Image builds; `claude --version` → 2.1.212 inside the container (`stable`; `latest` was 2.1.220).
- `claude -p "hi"` on a fresh container returns *"Not logged in · Please run /login"* — the binary runs,
  it just has no credentials yet.
- A file written to `/home/dev/.claude` in one container is visible in the next: the volume persists.
- `git config --get user.name`/`user.email` resolve through the read-only `.gitconfig` mount, and git
  operations on the bind-mounted workspace work with no `safe.directory` complaint (container UID
  matches the host owner).
- `post-create.sh` now reports the Claude version, the config dir, and whether a login is needed.

**Not verified**

- The interactive `/login` OAuth flow. Headless containers get the paste-a-URL variant; not exercised.
- Whether the VS Code extension attaches cleanly in-container — the extension list is declarative and
  untested from here.

**Deliberately not done**

- No bind mount of the host's `~/.claude`. It would skip the one-time login, but host and container
  Claude Code would then share one credentials/history file and both write to it. One `/login` is the
  cheaper trade.
- No Node.js and no `gh` CLI in the image. Neither is needed to run Claude Code; `gh` will be worth
  adding when there is actually a GitHub remote and CI (CLAUDE.md §7).
- No network sandboxing / firewall init script, and `--dangerously-skip-permissions` is not wired in.
  Both are the author's call, not something to enable silently.

**Observed later:** the author had already built and run the real dev container in VS Code, which
left `podsilo-android-sdk` partially populated (~819 MB: `emulator` + `system-images`, but no
`platform-tools`, `build-tools`, or `platforms`), so that run did not finish. Left the volume alone —
re-running `post-create.sh` is idempotent and completes it. Note that image predates Claude Code, so a
**Rebuild Container** is required to pick it up.

---

## 2026-07-30 (later still) — Gradle/Android skeleton, git/GitHub setup, hello-world proof

**Attempted:** CLAUDE.md §10 build order step 1 — Gradle skeleton with all §5 modules, version
catalog, ktlint/detekt, CI workflow — plus `LICENSE`/`.gitignore`/`.gitattributes`, requested
directly by the author rather than inferred. Namespace confirmed with the author up front:
`net.drehtuer.podsilo`.

**What was built**

- `LICENSE` (GPLv3 full text, fetched verbatim from gnu.org rather than reproduced from memory),
  `.gitignore`, `.gitattributes`, `.editorconfig` (incl. `ktlint_code_style`, and a
  `ktlint_function_naming_ignore_when_annotated_with = Composable` override — see below).
- `.github/workflows/ci.yml`: checkout → JDK 17 (Temurin) → `gradle/actions/setup-gradle` →
  `ktlintCheck` → `detekt` → `test` → `assembleDebug`. No emulator job (Tier 2 is not the supported
  path per §4).
- Gradle wrapper bootstrapped at **9.6.1**, AGP **9.3.1**, Kotlin **2.4.10**, Compose BOM
  **2026.06.01**, ktlint-gradle **14.2.0**, detekt **1.23.8** — all verified live against Google's
  Maven / Maven Central / Gradle Plugin Portal at plan time, not recalled from training data (the
  container's clock is 2026-07-30; my knowledge cutoff is Jan 2026, so anything Android-tooling
  related this recent had to be checked, not guessed).
- All eleven modules from §5's architecture diagram now exist as Gradle modules. Per §5,
  `:core:model`, `:core:naming`, `:core:sync` are plain `kotlin("jvm")` (no Android dependency,
  exactly as mandated); everything else under `:core:*`/`:feature:*` is `com.android.library`; `:app`
  is `com.android.application` + Compose. Only `:core:model` (a `greeting()` function + JUnit4 test)
  and `:app` (a one-screen Compose hello world calling into `:core:model`) have real content — the
  rest are empty stubs with a correct plugin/namespace, per the build order's own "empty modules"
  wording. No Room/Hilt/Retrofit/Stalla/jaudiotagger/WorkManager/Paging wired in yet; the version
  catalog only pins what this skeleton actually uses.

**What didn't work first time (all found by actually running the build, not by inspection)**

- **AGP 9.0 removed the need for — and now hard-errors on — `org.jetbrains.kotlin.android`.** AGP has
  built-in Kotlin support since 9.0; applying the old plugin throws
  `InvalidUserCodeException: 'org.jetbrains.kotlin.android' plugin is no longer required`. This
  postdates my training data entirely — first seen as a live build failure. Fix: drop the plugin
  (and its catalog entry) from every Android module; `org.jetbrains.kotlin.plugin.compose` alone is
  enough for Compose modules. The `kotlin { compilerOptions { jvmTarget = ... } }` block that plugin
  used to provide is gone too — dropped it and rely on `compileOptions` `sourceCompatibility`/
  `targetCompatibility` instead, which is enough at this skeleton stage.
- **compileSdk 35 is no longer enough for current `androidx.core`/`androidx.activity`.** Fresh
  `androidx.core:core-ktx:1.17.0` and `androidx.activity:activity-compose:1.11.0` both fail AAR
  metadata checks below compileSdk 36. Bumped the project to **compileSdk/targetSdk 37**
  (`platforms;android-37.0` + `build-tools;37.0.0`, both installed into the existing SDK volume) and
  updated `post-create.sh`'s default `SDK_PACKAGES` to match, so a fresh container provisions the
  right platform without a manual step. Left the Tier 2 emulator system image on `android-35` —
  changing that is out of scope here and Tier 2 is already flagged unverified.
- **`@Composable` functions collide with both linters' default naming rules** (ktlint
  `standard:function-naming`, detekt `FunctionNaming` both expect a lowercase first letter).
  Fixed once per tool rather than renaming the function to something un-idiomatic:
  `.editorconfig`'s `ktlint_function_naming_ignore_when_annotated_with = Composable`, and a
  `config/detekt/detekt.yml` with `naming: FunctionNaming: ignoreAnnotated: [Composable]`
  (`buildUponDefaultConfig = true`, so this is the only override on top of detekt's defaults).
- Root `build.gradle.kts`'s subprojects block references `rootProject.libs.detekt.formatting` (a
  version-catalog typesafe accessor) from inside `subprojects { }` — worked on the first real build,
  worth noting since it wasn't obvious it would resolve correctly from that scope without an explicit
  `rootProject.` qualifier trip-up.

**Verified on this host**

- `./gradlew clean ktlintCheck detekt test assembleDebug` — **all green**, from a clean build, exactly
  the task sequence CI runs. `:core:model`'s `GreetingTest` (2 cases) is the actual Tier 1 proof;
  `app-debug.apk` (29 MB) is the actual Tier 3-adjacent proof that AGP + Compose + the
  `:app → :core:model` module wiring compile together.
- SDK state on this host: `platform-tools`, `build-tools;35.0.0` + `;37.0.0`, `platforms;android-35`
  + `;android-37.0` all present in the existing `podsilo-android-sdk` volume; no manual step needed
  beyond what `post-create.sh` now installs by default.

**Flagged, not silently decided**

- AGP/Kotlin/Compose versions above are *current stable as of today*, not conservative/LTS picks —
  reasonable given CLAUDE.md's "prefer the boring, widely-used option" now means "current" this deep
  into AGP 9's life, but it is a live judgement call, not something CLAUDE.md pins explicitly.
- ktlint-gradle (jlleitschuh) and detekt-gradle are new build-tooling dependencies not in §3's
  table. Treated as implementation detail of the already-mandated "ktlint + detekt configured and
  passing" (§8) rather than a new app-facing dependency needing sign-off, but noted here per §9's
  "ask rather than assume" spirit.
- A harmless-for-now deprecation warning surfaces on every build:
  `ReportingExtension.file(String)` deprecated, "scheduled to be removed in Gradle 10", originating
  from detekt-gradle-plugin 1.23.8's own registration code (not our script). Build succeeds; this is
  an upstream compatibility gap to watch, not something to work around here.

**Not done / explicitly out of scope for this step**

- No `docker-compose.yml` / opodsync test server / `docs/dev-environment.md` — separate, already-
  flagged gaps from the previous session, not part of this task.
- No Hilt/Room/Retrofit/Stalla/jaudiotagger/WorkManager/Paging — steps 2–8 of the build order, each
  its own dependency conversation per §3.
- No GitHub issue/PR templates, no `docs/decisions/` ADRs — nothing architecturally contentious was
  decided here beyond tool versions (recorded above).
- Nothing committed to git. All of the above is currently unstaged/untracked working-tree state.

---

## 2026-07-30 — Tier 1: `:core:model`, `:core:naming`, `:core:sync`

**Attempted:** implement all three Tier 1 modules from `TODO.md` (the testability-tier build order
agreed with the author, which reorders CLAUDE.md §10 so `:core:sync` lands before `:core:gpodder`
since it never actually depends on it). Goal was full CLAUDE.md §12 definition-of-done: tests
alongside the code, `ktlintCheck`/`detekt`/`test` all green, decisions documented rather than
silently made.

**What was built**

- `:core:model` — `Feed`, `Episode`, `LedgerState`, `EpisodeLedgerRow`, `SyncState`,
  `SyncOutcome`, the `episodeKey()` identification-rule helper, and all six port interfaces
  (`FeedRepository`, `EpisodeRepository`, `EpisodeLedgerRepository`, `SyncStateRepository`,
  `GpodderClient`, `NamingTemplateEngine`) plus their DTOs, matching `docs/architecture.md` §5.
  Added `kotlinx-coroutines-core` to the version catalog (mandated by CLAUDE.md's dependency
  table, no ask needed). Removed the `Greeting.kt` skeleton placeholder and `:app`'s
  `MainActivity.kt` reference to it.
- `:core:naming` — sanitisation, UTF-8-byte-safe truncation (via `java.text.BreakIterator` for
  grapheme-cluster safety), Windows reserved-name escaping, title-cleanup rules, collision
  suffixing, `{guid_short}` hashing, date formatting with injected `ZoneId`, URL-based extension
  resolution, and a template tokenizer/engine tying it all together. Zero new dependencies —
  everything is JDK stdlib. 75 tests, table-driven per CLAUDE.md §7 item 5's checklist (illegal
  chars, trailing dots/spaces, reserved names, umlauts/CJK/RTL/emoji, NFD→NFC, 400-char truncation,
  empty-after-sanitising, missing/malformed dates, collision suffixing).
- `:core:sync` — `SyncOrchestrator` (full sync pass in CLAUDE.md §5's exact order), `reconcile()`
  (inbound remote-action reconciliation), `toOutboundAction()` (outbound ledger→action mapping),
  and GPodder timestamp round-tripping. Tested entirely with hand-written in-memory fakes of the
  four ports — no MockWebServer, no Room, no Android. 35 tests, including the CLAUDE.md §7 item 8
  "triage durability" case (failed push, simulated app restart via a second orchestrator instance
  sharing the same fake repositories, confirms exactly one push ever happens) and the item 1
  canned reconciliation cases (clock skew, duplicate actions, episode not in any subscribed feed,
  downloaded-remotely-while-queued-locally, guid-less CDN migration).

**Decisions surfaced mid-implementation, not pre-planned**

Two real gaps showed up only once the outbound-mapping code was actually being written, both
flagged to the author before proceeding rather than silently decided (CLAUDE.md §9 "ask rather
than assume... schema changes... sync conflict rules"):

1. `EpisodeLedgerRow` as originally specified (episodeKey + bookkeeping only) can't always build
   its own outbound `EpisodeAction` — the documented sync order (pull subscriptions, which prunes
   `Episode` rows for unsubscribed feeds, *then* push unsynced ledger rows) can delete the very
   `Episode` data an outbox push needs, if a previous push attempt failed. Fixed by denormalising
   `feedUrl`/`enclosureUrl`/`durationSeconds` onto the ledger row at write time. Recorded as
   `docs/decisions/0001-episode-ledger-row-denormalized-fields.md`.
2. Skip-as-`PLAY` duration encoding (architecture.md's open decision #1) needed AntennaPod's actual
   source, not a guess. Found `SynchronizationQueueImpl.enqueueEpisodePlayed()` via the GitHub API's
   tree endpoint (code search needs auth; the tree/raw-file endpoints don't) — confirmed
   `position == total` on completion and no special-casing of a missing duration. Recorded as
   `docs/decisions/0002-skip-as-play-encoding.md`.

Both were put to the author as a single `AskUserQuestion` with a recommended default before any
code was written against them; both recommendations were accepted as-is.

Two smaller decisions were made without asking, since they were implementation-detail latitude the
architecture doc already explicitly granted: which clock a naive GPodder action timestamp
represents (`docs/decisions/0003-gpodder-action-timestamp-as-utc.md` — UTC, for
cross-device-comparable ordering) and how `:core:naming` handles the `{date}` timezone
(`docs/decisions/0004-naming-date-timezone-and-missing-date-fallback.md` — injected `ZoneId`, never
re-resolved mid-call).

**What needed correction during implementation (not user correction — self-caught via tests)**

- First sanitisation pass treated tab/newline as "illegal characters" (replaced with a visible
  `_`) rather than whitespace to collapse — caught by a table-driven test
  (`whitespace runs collapse to a single space` with an embedded tab), fixed by reordering the
  pipeline so whitespace collapse runs before illegal-character replacement.
- Detekt (via the `detekt-formatting` plugin) and the `ktlint-gradle` plugin's own CLI disagreed on
  wrapping for some multi-argument calls — `ktlintFormat` would produce output that still failed
  `detekt`'s formatting ruleset. Worked around by shortening call sites (extracting repeated
  constructor calls into small test-local factory functions) rather than fighting the two tools'
  differing line-wrap heuristics. Not a code problem, but worth knowing about for future modules:
  don't assume `ktlintFormat` alone guarantees `detekt` passes when both are configured.
- `Episode.of()` factory (added preemptively "to enforce the identification rule") tripped
  detekt's `LongParameterList` and had no caller yet (`:core:feed` doesn't exist). Deleted rather
  than suppressed — matches CLAUDE.md's "don't build framework code with no second caller."

**Verified on this host**

`./gradlew ktlintCheck detekt test` — all green across the whole repo (114 tests across the three
new modules; the still-empty modules report `NO-SOURCE`, as expected at this point in the build
order). `:app` still compiles after removing the `greeting()` placeholder it referenced.

**Not done / explicitly out of scope for this step**

- Tier 2 (`:core:feed` parsing, jaudiotagger tag rewriting), Tier 3 (`:core:gpodder`,
  `:core:feed`'s HTTP layer), and Tier 4 (Room, DataStore, WorkManager, SAF, Compose UI) per
  `TODO.md` — next up.
- The subscriptions `add`/`remove` response-shape open decision (#2) and the
  `com.android.library` vs. `kotlin("jvm")` question for `:core:gpodder` (#3) are both still open,
  by design — they need `:core:gpodder` to exist to verify against `opodsync`.
- Nothing committed to git.

---

## 2026-07-30 — Tier 2: `:core:feed` parsing, `:core:download` tag rewriting

**Attempted:** the two Tier 2 items from `TODO.md` — RSS/Atom feed parsing and audio tag rewriting —
both explicitly flagged in `TODO.md` as needing a library-choice verification step before writing
any code.

**What was found before writing any code**

Both of CLAUDE.md's named library picks turned out to be stale, discovered by actually checking
Maven Central's repository metadata (not just a search-index snapshot, which can lag):

- **Stalla** (feed parsing): no release since May 2021, 39 open issues — effectively abandoned.
- **jaudiotagger** (tag writing, canonical `net.jthink` artifact): also no release since 2021, and
  Android compatibility was never confirmed for it. Also discovered while checking: AntennaPod
  (CLAUDE.md's suggested reference implementation) doesn't actually use jaudiotagger at all — it
  hand-rolls its own ID3 reader and never writes tags, so there was no reference to check tag
  details against for this one, unlike the gpodder-sync conventions in `docs/decisions/0002`.

Put both to the author as a single `AskUserQuestion` with researched, recommended alternatives
before writing anything against either choice (CLAUDE.md §3/§9: flag a doubtful fit rather than
silently substituting or silently proceeding). Both recommendations accepted:

- **`com.prof18.rssparser:rssparser`** (CLAUDE.md's own named fallback) — actively maintained, a
  release published the same day this decision was made. Full write-up: `docs/decisions/0005`.
- **`com.github.Adonai:jaudiotagger` via JitPack** — a fork specifically targeting Android
  compatibility, last touched 2023. Adding JitPack as a dependency source (not just a library
  choice) was flagged separately since it's a different trust model than Maven Central. Full
  write-up: `docs/decisions/0006`.

**What needed correction during implementation (self-caught via tests, not user correction)**

- rssparser is a genuine Kotlin Multiplatform library. `:core:feed` is an Android library module, so
  Gradle resolves rssparser's *android* target, whose `XmlPullParserFactory.newInstance()` call
  needs Robolectric to resolve in local unit tests (confirmed by reading rssparser's own test
  suite, which depends on Robolectric for exactly this reason). Added
  `testImplementation(libs.robolectric)` to `:core:feed` rather than assuming "Tier 2 means no
  Android tooling at all" — CLAUDE.md §4 explicitly allows Robolectric in its headless/no-emulator
  Tier 1 definition, so this isn't a tier violation, just a correction to `TODO.md`'s own
  simplified phrasing.
- A subtler bug: after decoding a genuinely non-UTF-8-encoded fixture (ISO-8859-1, generated with
  `iconv` so the bytes were real, not just a mislabeled UTF-8 file) to a correct Kotlin `String`,
  rssparser's `parse(String)` re-serialises that string back to bytes as UTF-8 before re-parsing
  it. The prolog's declared encoding was still textually `ISO-8859-1` (unchanged by the decode
  step), so the library decoded its own fresh UTF-8 bytes as ISO-8859-1 and mangled every umlaut a
  second time. Caught by the integration test, not by the isolated decoding unit test (which
  didn't exercise the round-trip through rssparser). Fixed by having `decodeFeedXml` rewrite the
  prolog's declared encoding to `UTF-8` after decoding, not just decode the characters.

**What was built**

- `:core:feed` — `ItunesDuration.kt` (parses `itunes:duration`'s three accepted formats),
  `FeedXmlDecoding.kt` (encoding-declaration-aware byte→String decoding), `RssMapping.kt` (maps
  rssparser's `RssChannel`/`RssItem` to `ParsedFeed`/`Episode`, excluding enclosure-less items and
  deduplicating reused GUIDs by keeping the first/newest occurrence), `FeedXmlParser.kt` (ties it
  together). 24 tests, fixture-driven (`src/test/resources/feeds/`), including a `wrong_encoding`
  fixture with real ISO-8859-1 bytes generated via `iconv`, not just a mislabeled file.
- `:core:download` — `AudioTagWriter`/`AudioTagData`/`TagWriteOutcome` (best-effort per-field tag
  writes: title/artist/album/year/genre/track/comment). 5 tests against an ffmpeg-generated silent
  MP3 fixture (`src/test/resources/audio/silence.mp3`), including unreadable-file and
  nonexistent-file cases, asserting `Failure` rather than a thrown exception either way.
- `docs/decisions/0005` and `0006` for the library-choice ADRs; `docs/architecture.md` §2/§7/§11
  updated to reference rssparser/the jaudiotagger fork instead of Stalla/upstream jaudiotagger and
  to note what's actually built vs. still pending (the HTTP-fetch layer and SAF/WorkManager pieces,
  both Tier 3/4b).

**Verified on this host**

`./gradlew ktlintCheck detekt test assembleDebug` — all green across the whole repo. 143 tests
total across the five modules built so far (Tier 1: 114, Tier 2: 29).

**Not done / explicitly out of scope for this step**

- Tier 3 (`:core:gpodder`, `:core:feed`'s HTTP-fetch layer with MockWebServer) and Tier 4 (Room,
  DataStore, WorkManager, SAF, Compose UI) — next up per `TODO.md`.
- Did not independently verify the exact `javax.sound`/AWT incompatibility the jaudiotagger fork
  works around versus upstream (flagged in `docs/decisions/0006` as worth checking if a specific
  container format fails tagging on-device later).
- The RFC-822 `pubDate` parser only tries the one standard format
  (`DateTimeFormatter.RFC_1123_DATE_TIME`) — real feeds are known to produce other date-string
  variants CLAUDE.md's fallback chain anticipates; not exhaustive, documented as a known gap in
  `RssMapping.kt`'s KDoc rather than silently claimed complete.

---

## 2026-07-30 — Tier 3: `:core:gpodder` client, feed HTTP layer, no-auto-download invariant

**Attempted:** the two Tier 3 items (`:core:gpodder` Retrofit client, `:core:feed`'s HTTP fetch
layer), plus CLAUDE.md §7 item 6's no-auto-download invariant test that `TODO.md` flagged as worth
doing as soon as Tier 3 landed.

**The headline finding: a stated core requirement is only half-achievable**

CLAUDE.md §5 says to infer the GPodder contract "from implementations, not assumptions", so before
writing DTOs I read `thrillfall/nextcloud-gpodder`'s and `kd2org/opodsync`'s source. That turned up
something no documentation mentions, which I then verified first-hand rather than trusting the
research pass:

`EpisodeActionController::create()` calls `filterOnlyPlays()`, which drops every posted action whose
type isn't `play` — and still returns HTTP 200. CHANGELOG: *"3.13.3 — #168 ignore actions DELETE and
DOWNLOAD"*. So on a real Nextcloud, Podsilo's `DOWNLOAD` actions are accepted, acknowledged, and
discarded. CLAUDE.md §1 requirement 9 ("Mark-on-download", "a core requirement, not a
nice-to-have") is achievable *locally* — the `EpisodeLedger` still prevents re-downloads on this
device, and that invariant was always local (§11) — but its **cross-client** half is impossible
against this server. Skip-as-`PLAY` is unaffected.

This went to the author as an explicit question rather than being absorbed, because the workaround
that *would* fix it (emit `PLAY` on download) is something CLAUDE.md §5 forbids by name. Decision:
keep emitting `DOWNLOAD`, document the gap (`docs/decisions/0008`). A trap worth remembering:
`opodsync` — the container CLAUDE.md §4 specifies for CI — *does* store `DOWNLOAD`, so integration
tests there will happily "prove" behaviour that real Nextcloud silently drops.

**A second correction: CLAUDE.md §11's timestamp format is stale**

§11 documents the per-action `timestamp` as ISO-8601 *without* offset. Neither server emits that any
more — `nextcloud-gpodder` uses PHP `format("c")` (`…+00:00`, CHANGELOG: "Always respond with
timezone in timestamps"), `opodsync` emits a trailing `Z`. Parsing is now lenient across all three
forms; ADR 0003 amended.

Worth recording how this played out in the tests: Tier 1 had a test asserting an offset-bearing
timestamp parses to `null`, named "the wrong format for this field". It passed, and it was
confirming a mistaken belief rather than correct behaviour — a reminder that a green test only
proves the code matches the assumption baked into it. Replaced, not relaxed.

Also nearly shipped a real bug while fixing this: parsing to `LocalDateTime` silently discards the
offset, so `…T11:49:23+02:00` reads as 11:49 UTC instead of 09:49. Caught it while writing the
parser rather than after, switched to `OffsetDateTime`, and added a dedicated regression test —
none of the UTC-equivalent test cases would ever have failed on it.

**What was built**

- `:core:gpodder` — `RetrofitGpodderClient` implementing all three endpoints, with DTOs that absorb
  every difference between the two servers (action-name casing, the `-1` absent-playback sentinel
  vs. omission, `opodsync`'s extra `update_urls`). Basic auth via a pre-emptive interceptor rather
  than OkHttp's `authenticator` (which only fires after a 401 — one wasted round-trip per request
  against a server that always requires auth). 20 MockWebServer tests: exact paths, query params,
  bare-JSON-array body, auth header, and 401/500/timeout/malformed-body.
- `:core:feed` — `FeedFetcher` with conditional GET, 304 → `NotModified`, redirect following, and
  all failures as `FeedFetchResult` values rather than exceptions (CLAUDE.md §8). 13 tests.
- **No-auto-download invariant** in two halves: `:core:sync`'s `NoAutoDownloadInvariantTest` (large
  fresh subscription list, repeated passes, 500 inbound remote actions → zero posted actions, zero
  self-created ledger rows) and a 500-episode parse test in `:core:feed`. The
  `subscription_change/create` half is structural (no such method exists on the client) and also
  asserted over the wire.
- ADRs `0007` (`:core:gpodder` → JVM module, resolving architecture.md §12 #3), `0008` (the
  `DOWNLOAD` limitation), `0009` (the full verified wire contract, resolving §12 #2 — CLAUDE.md's
  `set = add − remove` turned out to be correct exactly as specified).

**Decisions made without asking**

- Converted `:core:gpodder` from `com.android.library` to `kotlin("jvm")`. Architecture.md flagged
  this as an open question and explicitly low-priority/reversible; nothing in the module touches an
  Android API, and as a JVM module that property is compiled in rather than review-enforced, with
  tests on the plain `test` task instead of AGP's unit-test variant. Flagged in ADR 0007 that Hilt
  wiring from a JVM module is *unverified* (Tier 4c) with a documented fallback.
- Pinned OkHttp 5.4.0 explicitly even though Retrofit 3.0.0 declares 4.12.0 transitively — Gradle
  resolves to the higher version anyway, so pinning makes it deliberate rather than accidental.
  Verified the combination works with a throwaway compile probe before writing code against it.

**Verified on this host**

`./gradlew ktlintCheck detekt test assembleDebug` — all green. **185 tests** total (Tier 1: 114,
Tier 2: 29, Tier 3: +42 net, including the amended/expanded timestamp cases).

**Not done / known gaps**

- Everything about the server contract is verified by *reading source*, not by running against a
  live server. CLAUDE.md §4's disposable `opodsync` compose profile still doesn't exist — and per
  ADR 0008, when it does, it will *not* reproduce the `DOWNLOAD`-dropping behaviour.
- The invariant test's "downloads exactly zero **files**" half needs a real `DownloadWorker`
  (Tier 4b) to observe. What's asserted today is zero actions and zero ledger rows.
- ktlint and detekt continue to disagree about wrapping/indentation in a few spots (same friction
  as Tier 1). Worked around by restructuring the code rather than suppressing either — but
  `ktlintFormat` still does not guarantee `detekt` passes, and each round-trip costs a cycle.

---

## 2026-07-31 — Dev container build failure: an IPv6-only access point

**Symptom:** "Building the devcontainer failed." Reproduced immediately: the first `RUN apt-get
update` could not reach `archive.ubuntu.com`, so no package installed.

**First diagnosis was wrong — recorded here because that is the point of this journal.** The evidence
at the time (`networkingMode=Mirrored` in `.wslconfig`, distro rebooted an hour earlier, bridged
container reaching its gateway but nothing beyond, host networking fixing the build) pointed at the
known WSL2 mirrored-networking-versus-Docker-bridge problem, and it was reported that way. It was
wrong. Timestamps disproved it: bridged builds had succeeded **under mirrored mode** earlier the same
day. What actually changed was the network.

**Real root cause:** the access point provides **IPv6 only**. `ip -4 route show default` was empty,
`curl -4 https://dl.google.com` failed while `curl -6` returned 200, and `PING.EXE` **from Windows
itself** could not reach the LAN gateway or 1.1.1.1 over IPv4. Not WSL, not Docker, not the
Dockerfile. Docker's default bridge is IPv4-only, so bridged containers had no path out at all;
`--network=host` "fixed" the build only because it hands the container the working IPv6 route.

Later the author switched AP again and an IPv4 default route reappeared — but still with no IPv4
connectivity. That distinction cost a 20-minute hang and produced the most useful lesson here:
**probe reachability, never infer it from a routing table.**

**What each dependency needs**

| Host | IPv6 | Consequence on an IPv6-only network |
|---|---|---|
| `archive.ubuntu.com`, `dl.google.com`, `repo.maven.apache.org`, `services.gradle.org` | yes | fine |
| `downloads.claude.ai` | **no** (`::ffff:…` is a synthesised mapping, not a AAAA) | Claude Code cannot install |
| `downloads.gradle-dn.com` (redirect target of `services.gradle.org`) | **no** | `./gradlew` cannot bootstrap |

**Fixes made**

- `devcontainer.json`: `--network=host` in both `build.options` and `runArgs`, with the *correct*
  cause in the comment. The author chose this over reverting to `networkingMode=NAT`.
- `post-create.sh`: the JVM tries IPv4 first and does not fall back the way curl's happy-eyeballs
  does, so `sdkmanager` stalls on dead IPv4 routes for tens of minutes and then blames the mirror.
  A `curl -4` / `curl -6` probe now sets `_JAVA_OPTIONS=-Djava.net.preferIPv6Addresses=true` when
  IPv4 is unreachable but IPv6 works. With that in place the Android SDK installs normally.
- `post-create.sh`: the Gradle warm-up is no longer fatal. Under `set -euo pipefail` a failed
  `./gradlew --version` aborted the whole `postCreateCommand`, so VS Code reported a broken container
  when everything else had succeeded. It now warns and explains.
- `Dockerfile`: `CLAUDE_CODE_VERSION=skip` escape hatch, and a failure message naming the IPv4-only
  host instead of leaving a bare `curl: (28)`.

**Also fixed, from the author's in-progress docker-outside-of-docker work**

- `DOCKER_GID` was `999`; the host's docker group is **109** (`/var/run/docker.sock` is
  `root:docker` gid 109). With 999 the socket would have been unreadable.
- The Dockerfile comment referred to a bind-mounted `/var/run/docker.sock` that `devcontainer.json`
  did not actually mount. Added it. `docker ps` from inside the container now reaches the host daemon.
- `SDK_PACKAGES` was changed to `platforms;android-37.0` / `build-tools;37.0.0` — checked against
  `sdkmanager --list`: both are real package names. No change needed.

**Verified**

- Image builds with `--network=host` and `CLAUDE_CODE_VERSION=skip`; `docker ps` works from inside
  (uid 1000, groups `109(docker)`, `993(kvm)`); `post-create.sh` exits **0**, installs
  `platform-tools` (37.0.1) over IPv6, and degrades gracefully on both blocked downloads.

**Not verified, because this network cannot:** the Claude Code install step, and the Gradle wrapper
bootstrap. Both need IPv4. Nothing in the repo can work around that — rebuild from a dual-stack
network, then drop `--build-arg CLAUDE_CODE_VERSION=skip`.

**Process lesson:** the first root-cause claim was confident, coherent, and wrong, and it drove a
config decision (host networking) before being checked against timestamps. A cheap "when did it last
work?" question would have killed the mirrored-mode theory immediately. Host networking happens to be
a reasonable choice anyway, which is luck, not method.

---

## 2026-07-31 — Tier 4a: `:core:database` + `:core:datastore`

Implemented Tier 4a: the Room database module and the DataStore settings module. All four modules I
touched (`:core:model`, `:core:sync`, `:core:database`, `:core:datastore`) are green on `test` +
`ktlintCheck` + `detekt`; 27 new tests (20 Room, 7 DataStore).

**Built**

- `:core:database` — four Room entities matching architecture.md §4, four DAOs, `PodsiloDatabase`
  (v1, schema exported to `core/database/schemas/`), and the four repository ports implemented with
  entity↔domain mapping at the boundary. Robolectric in-memory-DB tests, including the cross-cutting
  `SubscriptionMirroringTest` (CLAUDE.md §7 item 7).
- `:core:datastore` — `SettingsRepository` over DataStore Preferences, app password encrypted through
  `AppPasswordCipher` (Keystore-backed in production, faked in tests — ADR 0010).

**Two model-port additions (flagged, not silently done)**

- A `SettingsRepository` port + its value types (`NamingSettings`, `NextcloudAccount`,
  `NextcloudCredentials`, `TitleCleanupRuleSetting`). Architecture.md §2 already said `:core:datastore`
  "implements `SettingsRepository`" but the port didn't exist yet.
- `observeEpisodes(filter): Flow<List<EpisodeListItem>>` on `EpisodeLedgerRepository`. Writing the
  DAO made a latent contradiction concrete: the existing `observe(filter)` returns
  `List<EpisodeLedgerRow>`, but "New" is the *absence* of a ledger row, so it cannot be an
  `EpisodeLedgerRow`. The row-typed method stays (returns empty for `NEW`, which is honest); the UI
  list is a new `EpisodeListItem(episode, ledger?)`, resolved — backlog cutoff and all — in one SQL
  join. The pre-existing sync fake's own comment had already deferred this "to Room's job (Tier 4)",
  so the addition was anticipated rather than a surprise.

**Design notes worth keeping**

- `FeedDao.replaceAll` uses `@Upsert`, not `@Insert(REPLACE)`. REPLACE is delete-then-insert, which
  fires the episodes' `ON DELETE CASCADE` and would wipe an existing feed's cached episodes on every
  subscription refresh. This is a classic Room footgun; the schema export confirms the intended FK
  layout (episodes→feeds cascade; ledger has no FK).
- Keystore behind an interface (ADR 0010) keeps the settings serialisation JVM-testable. The real
  `KeystoreAppPasswordCipher` is **not** unit-tested — Robolectric has no AndroidKeyStore provider —
  and needs an instrumented test (Tier 4b). Said plainly rather than pretended.

**What fought back**

- **KSP for Kotlin 2.4.10.** The old `<kotlin>-<ksp>` version scheme has no 2.4.10 build; KSP2's
  decoupled 2.3.x line (2.3.10) does the job. Room's annotation processing compiled fine under it.
- **Robolectric can't fetch `android-all` in this sandbox.** `curl` reaches Maven Central, but the
  Gradle test worker's own `MavenArtifactFetcher` gets `ConnectException` — even with the sandbox
  disabled and `--no-daemon`, so it isn't daemon-reuse. Worked around it by pre-fetching
  `android-all-instrumented-14-robolectric-10818077-i7.jar` (SDK 34, pinned via `robolectric.properties`)
  with curl and running Robolectric in offline mode through a throwaway `--init-script` (never
  committed). The 151 MB jar got reclaimed from disk mid-session and had to be re-downloaded — worth
  noting the workaround is fragile here but a non-issue on a normal network, where Robolectric just
  downloads it. This is why `:core:database`'s tests are proven green but only via that local scaffold.
- **ktlint vs. detekt-formatting disagree**, because the ktlint Gradle plugin (14.x) and
  detekt-formatting 1.23.8 bundle different ktlint versions with different indentation/wrapping
  opinions. Several format iterations (chain-continuation, assignment-wrapping, `ReturnCount ≤ 2`,
  `LongParameterList`, an EOL-comment-after-KDoc rule) before both were satisfied. Lesson for next
  time: write to the stricter/older (detekt) ktlint from the start, and prefer extracting locals over
  clever multiline wrapping.

**Not verified**

- The full-repo `test` was **not** run to green. `:core:feed`'s Robolectric tests need a *different*
  `android-all` SDK jar (its default SDK) that I didn't pre-fetch, and `:core:gpodder` has an
  untracked, pre-existing `OpodsyncIntegrationTest.kt` (needs a live opodsync server, and trips
  ktlint) that isn't part of this task. Neither is affected by my changes — feed/gpodder don't touch
  the ledger port or the new settings port — but I'm not claiming they pass. Scope verified: the four
  modules I changed.
- Deferred to Tier 4b/4c and stated in architecture.md/TODO: `KeystoreAppPasswordCipher` on-device
  test, all Hilt `@Binds` wiring, the workers, and the SAF folder-grant flow.

---

## 2026-07-31 (later) — opodsync test server: from "never run" to green integration test

**Attempted:** get the disposable `opodsync` server (CLAUDE.md §4) actually running. The previous
session had written `.devcontainer/docker-compose.yml`, `opodsync.Dockerfile`, and
`opodsync-entrypoint.sh`, but every one of them carried an "⚠️ NOT YET RUN / UNVERIFIED" header —
the host had no IPv4 route at the time and the build died at `git clone`. IPv4 is back on this
network, so the whole thing could finally be executed.

**It did not work, and not for the reason the headers predicted.** The network was never the only
problem; it was just the first one, and it had hidden four separate bugs, each of which would have
failed on any host:

1. **`ARG OPODSYNC_REF=1.5.3` pins a tag that has never existed.** opodsync's version line is
   `0.x` — `git ls-remote --tags` tops out at **0.5.3**. A plausible-looking version number was
   written down without being checked against the remote. This is the cheapest possible mistake to
   avoid and it blocked everything behind it.
2. **The generated `config.local.php` was in the wrong place and in the wrong namespace.** The
   entrypoint wrote `/var/www/html/config.local.php` at global scope. `server/_inc.php` reads
   `(getenv('DATA_ROOT') ?: ROOT.'/data') . '/config.local.php'` and checks for constants under
   `OPodSync\…`. So the file was never read, and had it been, every constant in it would have been
   ignored. Both failures are **silent** — the server boots fine on defaults.
3. **`AllowOverride None`.** Debian's `apache2.conf` ships it for `/var/www/`, so opodsync's
   `.htaccess` is inert in `php:8.3-apache`. That file is load-bearing twice over:
   `FallbackResource /index.php` is what routes the virtual `/index.php/apps/gpoddersync/...`
   paths into the front controller, and `SetEnvIf Authorization` is what makes the Basic auth
   header reach PHP. Without an `AllowOverride All` conf, every API call 404s and auth never
   arrives. Nothing warns you.
4. **The volume was mounted at the wrong path** (`/var/www/data`), which is neither opodsync's
   `DATA_ROOT` nor where it looks for its config. The SQLite DB would have been written outside the
   volume and lost on every `down`, quietly defeating the point of a persistent volume.

Also removed: `docker-php-ext-install pdo pdo_sqlite` plus `libsqlite3-dev`. `php:8.3-apache`
already has `sqlite3`, `pdo_sqlite`, and `json` compiled in (`php -m`), which is opodsync's entire
stated requirement — and it uses the procedural `\SQLite3` class, not PDO, so the extension being
installed wasn't even the relevant one.

**User seeding now actually happens.** The old entrypoint printed "create the test user at
/register.php" and left it to a human, which makes the server non-hermetic. It now calls opodsync's
own `GPodder::subscribe()` from PHP-CLI as `www-data`, which does the `password_hash()` and the
username validation — using upstream's code rather than reimplementing its hashing (CLAUDE.md §3).
Idempotent: a restart hits "Username already exists" and carries on.

**Verified on this host — this is the first time any of it has run**

- `docker compose build opodsync && docker compose up -d opodsync` → container **healthy**, logs
  show `opodsync: created user 'podsilo'`.
- `GET /index.php/apps/gpoddersync/subscriptions` → `{add:[], remove:[], update_urls:[],
  timestamp:1785516110}`; unauthenticated → **401**.
- `POST /episode_action/create` with a bare JSON array of a `DOWNLOAD` and a `PLAY` → 200
  `{timestamp, update_urls}`; both come back from `GET /episode_action?since=0` with **lowercase**
  action names and a **trailing `Z`** timestamp.
- `PODSILO_OPODSYNC_URL=http://localhost:8080 ./gradlew :core:gpodder:test --tests
  '*OpodsyncIntegrationTest*'` → **BUILD SUCCESSFUL, 3 tests, 0 skipped, 0 failures** (checked the
  JUnit XML rather than trusting "BUILD SUCCESSFUL", since these tests self-skip via `assumeTrue`
  and a skipped run also reports success).

That last check is the whole point of the exercise: ADR 0009's wire contract was previously
*read* from two servers' source. It is now *tested* against one of them.

**One documented claim turned out to be too strong.** ADR 0009 said opodsync "inner-joins episode
actions against subscriptions, so actions for unsubscribed feeds disappear from its `GET`". On
0.5.3 they came back anyway. Podsilo tolerates either, so no code changes — but the ADR is
corrected rather than left as a confident statement that a live server contradicts.

**Unchanged and still true:** opodsync is not evidence about Nextcloud. It stores `DOWNLOAD`;
`nextcloud-gpodder` discards it (ADR 0008). A green run here says nothing about cross-client
download dedup on the author's real server, and `OpodsyncIntegrationTest` asserts that difference
explicitly so it stays visible.

**Process lesson.** Every one of these four bugs was written *behind* a real blocker (no IPv4) and
labelled as unverified-because-of-the-blocker. The label was accurate about the state and
misleading about the cause: it invited the reading "this will work once the network is back",
when in fact nothing had ever been exercised. Marking work "unverified" is honest; predicting that
it will pass once unblocked is not, and the two are easy to blur. Cheapest correction available
here was `git ls-remote` — one command, would have caught bug 1 before it was ever committed.

**Not done**

- The full-Nextcloud `--profile nextcloud` opt-in service is **deliberately not built** (author's
  call, confirmed 2026-07-31): opodsync alone, because a full Nextcloud is far more setup overhead
  for occasional verification. This matches CLAUDE.md §4's own preference order, which names
  opodsync the default and Nextcloud merely an opt-in. Consequence to keep in view: ADR 0008's
  finding (`nextcloud-gpodder` discards `DOWNLOAD` and returns 200) is now permanently
  source-read-only, and opodsync will keep "proving" the opposite behaviour. The mitigation is a
  test *name* rather than a test — `OpodsyncIntegrationTest`'s "opodsync accepts DOWNLOAD --
  unlike nextcloud-gpodder, which silently drops it". The invariant that actually matters (never
  download twice) is local to the ledger and never depended on the server (CLAUDE.md §11).
- The compose file is still standalone, not merged into `devcontainer.json` via
  `dockerComposeFile`. Left deliberately — see the file's own header comment.
- `docs/dev-environment.md` still doesn't exist (long-standing §4 gap, unrelated to this session).

---

## 2026-07-31 (later still) — `docs/dev-environment.md`, and the first fully-green full-repo build

**Attempted:** write the `docs/dev-environment.md` deliverable CLAUDE.md §4 has asked for since the
first session, now that the opodsync half is actually verified rather than aspirational.

**The document's central claim had to be earned first.** §4 says it "must let someone go from clean
checkout to green `./gradlew test`... Verify that yourself before claiming it works." The Tier 4a
entry above explicitly did *not* claim a full-repo green run (`:core:feed`'s Robolectric jar,
`:core:gpodder`'s untracked integration test). So the doc could not be written honestly without
first running it.

**A near-miss worth recording.** The first full run was launched as a background command piped to
`tail`. The harness reported **exit code 0** — and the build had actually **FAILED**. The pipe meant
the reported status was `tail`'s, not Gradle's. Only reading the output caught `BUILD FAILED`. Had
the tail happened to end on a benign line, this session would have "verified" a red build. Lesson:
when a command's exit code is the thing being claimed, do not pipe it; or check `${PIPESTATUS[0]}`.

The real failure was the one Tier 4a predicted: `OpodsyncIntegrationTest` tripping ktlint (15
violations). Fixing it reproduced the project's recurring ktlint-vs-detekt fight for the fourth
tier running — `ktlintFormat` produced deeply-indented named arguments that then failed detekt's
`Indentation` rule. Resolved by following the lesson Tier 4a had already written down (extract a
local rather than wrap cleverly), which worked first try. The prior entry's advice paid off; it just
had to be re-read.

**After that: `./gradlew ktlintCheck detekt test` green across the whole repo, exit 0** — 215 tests
discovered, 3 skipped, 212 executed. Per module: naming 75, sync 43, feed 38, gpodder 23, database
20, datastore 7, download 5, model 4. The 3 skips are the opodsync integration tests correctly
self-skipping without `PODSILO_OPODSYNC_URL`, which also re-proves that opt-in path. This is the
first time the full repo has been green in one command; Tier 4a could only claim four modules.
Robolectric's `android-all` download worked normally this time — that whole workaround was a
symptom of the IPv6-only network, not a lasting problem.

**A Tier 2 discovery, made while gathering facts for the doc.** An AVD `podsilo-test` exists in
`~/.android/avd` — no journal entry mentions creating it. `avdmanager list avd` refuses to load it:
*"Missing system image for Google APIs x86_64"*. But the image **is** installed
(`system-images;android-35;google_apis;x86_64`, confirmed via `--list_installed`), and the AVD's
`config.ini` carries placeholder values (`avd.name = <build>`). So it looks like a half-created AVD,
not a missing dependency. Not investigated — out of scope for a docs task — but documented in §6
with a clean-slate recreate command, because a silently broken AVD is exactly the kind of thing that
wastes an hour later.

**What the doc says that previous docs did not**

It opens with a status table separating *verified* from *never run*, because that distinction was
scattered across five journal entries and easy to lose. Blunt version: Tier 1 is supported; **Tier 2
has never booted an emulator and Tier 3 has never been attempted at all** (there is no `scripts/`
directory, so §4's requested `adb-connect-host.sh` doesn't exist). Also recorded honestly: the green
run happened in an already-warm container, not from a virgin clone plus from-scratch image build, so
"verified" is narrower than the §4 wording implies. Saying so beats letting the table imply more.

**Not done**

- The Tier 2 AVD is left broken. Recreating it is one command; *booting* it and running
  `connectedAndroidTest` is the actually-unproven part and would be its own task.
- `scripts/adb-connect-host.sh` still doesn't exist.
- No `--profile nextcloud`; decided out of scope earlier this session (see the entry above).

---

## 2026-07-31 (later still) — Moving to a second machine: three host-ID bugs and a missing `gh`

**Symptom:** on a new PC, `post-create.sh` scrolled a licence, printed *"Skipping following
packages as the license is not accepted"* for all five SDK packages, and died at
`adb: command not found`. Also: no `gh` binary in the container.

**The licence message was a lie, and that is the interesting part.** Nothing was wrong with the
licences. `$ANDROID_HOME` (a named volume, initialised from the image's ownership) was owned by
`1000:1000` while the container user was `1002:1002`, so `sdkmanager --licenses` could not create
`/opt/android-sdk/licenses`. It does not report that. It prints **"All SDK package licenses
accepted"** and exits **0** having written nothing. The install that follows then re-prompts,
reads EOF from a closed stdin, takes the `N` default, and skips every package. Three layers of
misdirection between cause and symptom, and the script's own `yes | sdkm --licenses >/dev/null ||
true` deleted the only remaining evidence.

**Why the UID differed at all:** the devcontainer CLI rewrites the container user's UID/GID at
container start (`updateRemoteUserUID`, default on) to match the host user, and chowns `$HOME` but
not the named volumes. Confirmed rather than assumed — the running image's name carries the CLI's
`-uid` suffix. The old machine's host uid was 1000, so the build arg matched *by luck*, not design.

Two more of the same class fell out once looked for: `/var/run/docker.sock` is group **108** on this
host against `DOCKER_GID=109` in the image (so `docker ps` → permission denied, and no opodsync),
and `/dev/kvm` is 993 (which happened to still match).

**Fix: repair at runtime, don't re-pin build args.** The author's steer mid-session — *"ideally the
container should be able to run on any PC which may have different uid/gid settings"* — was already
the direction, and it rules out the tempting one-line fix of editing `1000`→`1002`, which just
relocates the breakage to the next machine. `post-create.sh` now, before touching sdkmanager:

- re-chowns `$ANDROID_HOME`, `~/.android`, `~/.gradle`, `~/.claude`, `~/.config/gh` to the *current*
  uid:gid when they differ (it has passwordless sudo);
- aligns the `kvm`/`docker` groups to whatever GID owns those nodes — reusing an existing group if
  that GID is taken, otherwise `groupmod`-ing the image's placeholder. Deliberately **never**
  `chgrp`s the socket itself: it is a bind mount, so that would change it **on the host**;
- verifies `$ANDROID_HOME/licenses/*` actually exists after the accept step, instead of trusting an
  exit code that is known to lie;
- replaces the bare `adb version` (which under `set -e` aborted with a true but useless
  "command not found") with a check that names the install that failed.

The build args stay as documented best-effort defaults, with comments in all three files saying not
to edit them for a new host.

**`gh`:** added to the Dockerfile as the upstream **2.97.0** release tarball rather than
`apt-get install gh`. Ubuntu noble does package it, but at 2.45.0 (Feb 2024) — well over a year
stale for a tool that talks to a moving API. A pinned static binary over HTTPS is also the pattern
the Dockerfile already uses twice (Google's cmdline-tools, Claude Code), so it keeps the "no
third-party apt repositories" property the file's header promises. Added a `podsilo-gh-config`
volume for `~/.config/gh` so `gh auth login` survives a rebuild, same rationale as the Claude Code
volume.

**What bit me while writing the fix:** `grp="$(getent group "${node_gid}" | cut -d: -f1)"` aborted
the script under `set -euo pipefail`. `getent` exits **2** when the GID has no group, and `pipefail`
propagates that past `cut`'s 0 — so the guard died on precisely the case it existed to handle.
Caught by running it (`bash -x`), not by reading it.

**Verified on this host — all run, not inferred**

- `post-create.sh` exits **0** from the broken starting state; all five SDK packages install;
  `/opt/android-sdk/licenses` has 7 files; `adb version` → 37.0.1.
- `docker ps` works (via `sg docker` in the same shell; a new terminal gets it normally).
- `./gradlew ktlintCheck detekt test` → **BUILD SUCCESSFUL, exit 0**. Test counts read from the
  JUnit XML rather than the console: naming 75, sync 43, feed 38, gpodder 23 (3 skipped), database
  20, datastore 7, download 5, model 4 = **215 discovered, 3 skipped**. Identical to the previous
  machine's baseline.
- opodsync: built, came up healthy, served `GET /subscriptions` with auth and **401** without, and
  `OpodsyncIntegrationTest` ran **3 tests, 0 skipped, 0 failures**. Torn down afterwards
  (`docker compose down -v`), so the machine is left as found.
- `gh --version` → 2.97.0, installed with the exact command the Dockerfile now runs.

**Not verified**

- **The image itself has not been rebuilt.** `gh` was installed into the *running* container with
  the Dockerfile's own commands, which proves the URL, the archive layout, and the install path —
  but not the layer in context. The `podsilo-gh-config` volume and the `GH_CONFIG_DIR` mkdir/chown
  only take effect on a rebuild; until then `post-create.sh` correctly reports `gh` as present and
  the config dir is a plain directory rather than a volume.
- Tiers 2 and 3 remain untouched and unproven; the half-created `podsilo-test` AVD noted in the
  previous entry was not investigated.

**Process note.** The IPv6-only network from the earlier entry is gone, and the current machine is
the exact mirror image — `curl -4` returns 302, `curl -6` fails outright. Every comment that
confidently explained *why* `--network=host` was needed was now wrong in a way that would mislead
the next reader into thinking it is load-bearing for connectivity. Rewrote them to say what is
actually true: kept for opodsync's `localhost:8080` and Tier 3's adb, not for reachability. A stale
*rationale* is worse than no comment, because it survives the condition that produced it and still
reads as authoritative.

---

## 2026-07-31 (last) — CI: `D8: java.lang.OutOfMemoryError` on `assembleDebug`

**Symptom:** the `Assemble debug APK` step failed on GitHub Actions with
`ERROR: D8: java.lang.OutOfMemoryError: Java heap space`. Everything before it (ktlint, detekt,
215 unit tests) was green.

**Root cause:** the repo had **no `gradle.properties` at all** — checked properly rather than
assumed (not on disk, not gitignored, not in `$GRADLE_USER_HOME`). Gradle's daemon therefore ran on
its documented default of **512 MB**, confirmed by reading `Runtime.maxMemory()` from an init
script rather than inferring it. That is far too small for AGP + Kotlin + Compose dexing.

CI makes it worse in a way that is easy to miss: `ci.yml` runs **four separate `./gradlew`
invocations** that all reuse **one** daemon, so by the time D8 runs, that 512 MB already holds the
Kotlin compiler, ktlint, detekt and the test infrastructure.

The skeleton was hand-built (2026-07-30 entry) rather than generated by Android Studio, which is
exactly why the file was missing — and the reason every generated Android project ships one is
precisely this failure.

**I could not reproduce it locally, and that is worth recording.** The full cold CI sequence
(`clean`, daemon stopped, then all four invocations in order) passes here on the same 512 MB heap.
512 MB is *marginal*, not reliably fatal: this box has 30 GB and 16 cores, GitHub's `ubuntu-latest`
has 16 GB and 4. So the fix is justified by removing the margin, not by a red-to-green local
reproduction — resisting the temptation to claim otherwise, since a passing local build was the
thing that let this ship in the first place.

**Fix:** added `gradle.properties` with `org.gradle.jvmargs=-Xmx4g -XX:MaxMetaspaceSize=1g
-Dfile.encoding=UTF-8` and `kotlin.daemon.jvmargs=-Xmx2g`. Metaspace because Gradle's 384m default
is tight once two static-analysis tools have loaded; `file.encoding` pinned because CLAUDE.md §6's
naming tests assert on umlauts/CJK/NFC and a non-UTF-8 runner locale would fail them in a way that
reads as a logic bug rather than an environment one. The Kotlin daemon is capped separately because
left unset it *inherits* `org.gradle.jvmargs` — another 4 GB on top of the Gradle daemon's.

**Verified:** daemon heap now reports 4096 MB (was 512); the full cold four-invocation sequence is
green end to end, producing the 29 MB debug APK; 215 tests, 3 skipped, 0 failures — unchanged.

**Process note.** The first instinct was to reach for the standard Android Studio value and move on.
Measuring the daemon heap first (512 MB, from the JVM itself) turned a plausible guess into a
diagnosis, and turned up the four-invocations-one-daemon detail that explains why CI is harsher than
a local build. Cheap check, and it is the difference between "raised the memory and it went away"
and knowing what was actually wrong.

---

## 2026-07-31 (later) — Tier 4b: the three workers, the download pipeline, and the DI graph

**Attempted:** all of `TODO.md`'s Tier 4b — `DownloadWorker`, `FeedRefreshWorker`, `:app`'s
`SyncWorker`, the SAF folder-grant flow, and the foreground-service notification.

**Two decisions went to the author before any code**, because both cut against a CLAUDE.md rule:

1. **Hilt now, a tier early.** `TODO.md` schedules DI for 4c, but a worker has to get its
   dependencies from somewhere and the alternative — a hand-written `WorkerFactory` doing lookups —
   is the "own service locator" §3 forbids. Approved. Risk flagged up front: Hilt 2.60.1 against
   AGP 9.3.1 was unproven here. Smoke-tested it as the *first* thing built (empty
   `@HiltAndroidApp` + `assembleDebug`) rather than discovering it eight files in. It works.
2. **A `DownloadTarget` port in front of the SAF write.** §3 warns against wrapping a library "in
   case we swap it later"; this is not that, and saying so plainly mattered. A `DocumentFile`
   write needs a real `DocumentsProvider`, so without a seam the entire pipeline — collision
   suffixing, extension resolution, retry name reuse, tag-failure handling, cache cleanup, the
   retryable/not classification — would be testable only on Tier 2, which has never booted on this
   host. Approved; written up as `docs/decisions/0011` with the honest cost stated: the seam moves
   the untested surface down to `SafDownloadTarget`, it doesn't remove it.

**Tier 4b needed far less emulator than its own heading claims.** WorkManager's
`TestListenableWorkerBuilder` runs fine under Robolectric with a hand-rolled `WorkerFactory` that
constructs the worker from fakes, and Robolectric *does* implement persisted URI permissions — so
`DownloadFolderAccess`'s grant/revoke logic is genuinely tested, not just described. The heading in
`TODO.md` has been corrected rather than left to mislead the next reader.

**A real bug the tests caught, which review would not have.** jaudiotagger picks its reader from
the **file extension**. The download cache file is named `<hash>.partial` for resume stability, so
`AudioFileIO.read` reported "no reader associated with this extension:partial" and *every* download
would have arrived untagged — a silent, user-visible-in-the-player failure that still reports
success. Fix: rename the cache file to its resolved extension between download and tagging. The
test that caught it (`title cleanup rules reach both the file name and the tags`) was written for a
different reason entirely.

**A test expectation that was wrong, not the code.** Several pipeline tests asserted hyphenated
file names (`20260714_Warum-Hamburg-immer-regnet.mp3`) because CLAUDE.md §6's example shows one.
The sanitiser built in Tier 1 preserves spaces and always has, with its own passing tests. Fixed
the new expectations rather than "fixing" tested behaviour to match an example in prose.

**What was built**

- `:core:download` — `EnclosureDownloader` (resumable `Range` fetch into the app cache; handles
  servers that *ignore* `Range` by restarting rather than appending to a stale prefix, 416 by
  discarding and restarting once, and a body shorter than its own `Content-Length` as a retryable
  truncation), `EpisodeDownloader` (the §11 pipeline), `DownloadTarget`/`SafDownloadTarget`,
  `DownloadWorker`, `DownloadNotifications`, `DownloadFolderAccess`, `SyncTrigger`. 39 tests.
- `:core:feed` — `FeedRefresher` (the loop and the writes) + `FeedRefreshWorker` (retry policy
  only). One feed failing never aborts the pass; a 4xx is permanent and explicitly *not* an
  unsubscribe; unparseable XML keeps the cached episodes.
- `:app` — `PodsiloApplication` (`HiltWorkerFactory` + `Configuration.Provider`, with WorkManager's
  `androidx.startup` initializer removed in the manifest), `SyncWorker`, `SyncOrchestratorFactory`,
  `WorkScheduler` (every enqueue in the app goes through it, and it is the `SyncTrigger`), five
  Hilt modules, and the manifest permissions — including `INTERNET`, which the app had never
  declared because until now nothing in it made a request.
- Ports gained the read-side methods the workers need, plus a `GpodderClientFactory` port so
  `SyncWorker` is testable with a fake client instead of a live Retrofit instance.

**The ktlint-vs-detekt fight is over, by configuration.** Every previous tier restructured code to
satisfy both tools; this tier that stopped being possible. ktlint 14 formats an annotated
constructor — `class W @AssistedInject constructor`, i.e. every Hilt worker — with the class body
indented one level, and detekt-formatting's older bundled ktlint calls that wrong indentation: 358
findings on code `ktlintFormat` had just produced, with no shape satisfying both. Settled by making
`ktlintCheck` the sole formatting authority (detekt's duplicate `Indentation`/`Wrapping`/
`*ListWrapping`/`MaximumLineLength` rules off) and adding `max_line_length = 120` to
`.editorconfig`, which ktlint had never had — that omission is why `ktlintFormat` kept joining
lines into 130-character ones detekt then rejected. Both halves are documented where the next
person will look (`config/detekt/detekt.yml`, `docs/dev-environment.md` §8.6).

The genuine detekt findings underneath the noise were worth fixing rather than suppressing, and
most made the code better: `FeedRefresher` split out of its worker, `SyncOrchestratorFactory` out
of `SyncWorker`, `FeedRefreshMetadata` replacing a six-parameter port method, the copy loop split
out of the stream-closing scopes, `DataModule` split into four focused Hilt modules. Three
suppressions remain, each with its reason in the code: a worker's constructor *is* its dependency
list, Room binds query parameters flat, and one three-exit pipeline function.

**Verified on this host**

`./gradlew ktlintCheck detekt test assembleDebug` — **exit 0**, whole repo. **269 tests, 3
skipped** (the opodsync integration tests, correctly self-skipping without `PODSILO_OPODSYNC_URL`).
Per module: naming 81, feed 44, sync 43, download 39, database 25, gpodder 23, datastore 7, model 4,
app 3. Counts read from the JUnit XML, not the console.

**Not done / known gaps — stated rather than implied**

- **`SafDownloadTarget` has never run.** Nor has `KeystoreAppPasswordCipher` (ADR 0010). Nor has
  the foreground notification ever been displayed. **The app has never been installed or launched**
  — Tier 4b produces an APK and 269 green tests, and neither of those is the same as "it works on a
  phone". The first device run will find things; that is expected, not a surprise to be explained
  away later.
- The `DownloadWorker` → `SyncWorker` enqueue is asserted at the `SyncTrigger` boundary (fires once
  on delivery, never on failure). That the resulting `SyncWorker` actually runs is WorkManager's
  contract, untested here.
- Cancellation mid-download is tested at the `EnclosureDownloader` level (the partial file survives)
  but not through the worker, where the `NonCancellable` ledger write back to `QUEUED` lives.
- No `:feature:*` UI yet, so nothing enqueues a download except a test. That is Tier 4c.

---

## 2026-08-01 — UI/UX design pass (recorded retrospectively)

**No journal entry was written when this happened**, which CLAUDE.md §9/§12 require of every
session. Recorded here on the same day by the following session, from the commits and the documents
themselves — so it is second-hand, and thinner than it would have been written live. That gap is
itself the lesson: the entry is part of the work, not the write-up afterwards.

**Attempted:** design the whole Compose UI before writing any of it — screens, states, gestures,
motion, spacing, icons, orientation — and write down the seam between the screens and the code that
already exists.

**Produced:** `docs/UI.md` (eight screens S1–S8, every state each has, the cross-cutting rules),
`UI_interface.md` (per-screen `UiState`/`UiEvent`/`UiEffect`, the gap list, the corner cases),
`HANDOVER.md`, `assets/icons/` (27 Lucide SVGs) and `assets/art/` (placeholder cover art), plus
amendments to `docs/architecture.md` §4/§5/§7/§9/§12 and a draft ADR 0012.

**What the design surfaced that the architecture had not:** S8's error log has no data source at all
(nothing persists failures — they are returned as values and discarded once handled); `Episode.link`
does not exist, so *Open in browser* has nothing to open; `DownloadWorker`'s refusal of terminal
ledger rows — the thing that makes the no-auto-download invariant provable — also makes *Download
again* a silent no-op. That last one is the useful kind of finding: a UI requirement colliding with a
deliberately-built guard, where the answer is a mechanism (`KEY_USER_REQUESTED`) rather than removing
the guard.

**Left open, and still open:** ADR 0012 drafted but not accepted; the backlog cutoff contradiction
(§14.2); the *Download all* narrowing of a stated non-goal (§14.3).

## 2026-08-01 — Documentation consistency pass

**Attempted:** read CLAUDE.md, `docs/architecture.md`, all twelve ADRs, `docs/dev-environment.md`,
`docs/UI.md`, `HANDOVER.md` and `TODO.md` together, then fix what disagreed and condense what was
said three times.

**What was actually wrong** — the interesting half, since most of the corpus held up:

- **A name collision that would not have compiled.** `UI_interface.md` §1 declared
  `enum class EpisodeAction` in `:core:model` for the UI's affordance set. `:core:model` already has
  `port.EpisodeAction` — the GPodder wire type. Renamed to `EpisodeUiAction` in the design doc, with
  the reason in its KDoc so it is not "fixed" back.
- **S6 advertised template variables the engine does not have.** `docs/UI.md` §9's placeholder chips
  listed `{guid}` and `{episodeNumber}`; `DefaultNamingTemplateEngine` resolves `{podcast}`,
  `{title}`, `{description}`, `{date}` and `{guid_short}`. A chip for an unknown variable renders as
  literal text in a filename — a defect that would have shipped and looked like a naming bug.
- **`architecture.md` §7 contradicted itself in two adjacent paragraphs** — "Not yet built: the
  HTTP-fetch layer" immediately above "Built (Tier 3 + Tier 4b): the whole sequence."
- **Four things the UI needs that no gap list mentioned:** an image loader (nothing in the repo
  loads a remote image, and three screens render artwork), four `SettingsRepository` values that do
  not exist (theme, swipe mapping, mobile-data, mark-old cutoff), `kotlinx-datetime` implied by the
  state classes but absent from the version catalog, and the Lucide artifact.
- **`docs/backlog.md` and `docs/third-party.md` never existed** despite CLAUDE.md §1/§2/§9/§12
  referring to both throughout. Created; the licence table is the one that mattered, since GPLv3
  compatibility is a hard constraint and nothing had recorded it.
- Smaller: `UI_interface.md` sat at the repo root while every other document is in `docs/` (moved);
  it linked to two design files that are not in the repository; `HANDOVER.md` referenced a
  `github.md` that does not exist; `docs/UI.md` §12 skipped from 12.9 to 12.11 and its introduction
  pointed at §12/§13 for things in §13/§14; `dev-environment.md` §6 still said 212 tests where §1
  and §5 say 269; README described the project as a skeleton with no features and credited Stalla
  and upstream jaudiotagger, both of which ADRs 0005/0006 replaced.

**Condensed:** `architecture.md` §12 was 18 numbered items of which 15 read "Resolved" and restated
their ADR — replaced by an eleven-row ADR index, four short notes on decisions that follow from an
accepted ADR, and the four items that are genuinely open. `TODO.md`'s header claimed all modules
were empty scaffolding, and its trailing "Open question" had answered itself three tiers ago.
`HANDOVER.md` was demoted to what it actually is — a reading order and a trap list that holds no
decisions of its own.

**Deliberately not done:** the three product-level contradictions were *not* resolved. Two of them
(the backlog cutoff writing `SKIPPED` rows; *Download all*) contradict explicit CLAUDE.md §1/§5
rules, and one commits the project to a new dependency. Those are the author's to decide, so they
were consolidated into one list in `architecture.md` §12 rather than settled quietly — which is the
whole point of the section existing.

**Verified:** `./gradlew ktlintCheck detekt test` — documentation-only changes, no source touched,
but run rather than assumed.

## 2026-08-01 (later) — Five decisions settled, three rules amended

**Attempted:** put the four open items from the consistency pass to the author, and record whatever
came back — as ADRs, and as amendments to the documents holding the rules that changed.

**Settled:**

- **ADR 0012** accepted. The author's answer to all four of its open points was *consistency*: a
  re-decision behaves exactly like a first one — the action is re-posted, `attempts` resets,
  `lastError` clears — and *Mark as played* over a terminal row follows the identical rules. Writing
  it up surfaced one thing none of the four questions asked: `writtenFileName` must **survive** a
  re-decision, or a `SKIPPED` row written over a `DOWNLOADED` one quietly disarms the duplicate
  guard and a later *Download again* writes a second copy. "Reset everything for consistency" was
  right in four places and wrong in one, which is the kind of thing that only shows up when you try
  to write the rule down as a rule.
- **ADR 0013** — the backlog cutoff is written `SKIPPED` rows; the UI mechanism wins. This one
  **amends CLAUDE.md §5**, which forbade exactly this and gave a good reason: a bulk write to a
  shared action log cannot be taken back. That reason survives in the amendment as the justification
  for the preview dialog being mandatory rather than nice — the rule changed, the reasoning behind
  it did not. Also decided: the Tier 4a SQL cutoff gets *removed*, not left unused, because an
  unused capability is one flag away from becoming a second mechanism.
- **ADR 0014** — bulk download allowed as a *command*, never as a *rule*. Amends CLAUDE.md §1 and
  README. `NoAutoDownloadInvariantTest` is untouched and stays exactly as strict, which is the
  evidence that the narrowing is real rather than a loophole.
- **ADR 0015** — Coil and the Lucide Compose artifact approved.
- **ADR 0016** — and the one that added nothing. The draft assumed `kotlinx-datetime` for the UI's
  `Instant`; the author asked for the difference and for consistency over new dependencies. Two
  facts decided it: `minSdk = 33`, so `java.time` is free, and `java.time` is *already* in main
  source in four modules while `kotlin.time` is in none. So storage keeps its `Long`s, UI state uses
  `java.time`, and the author's suggested conversion class became `EpochTime` — five one-line
  functions whose entire value is their names, since `ofServerSeconds` cannot be handed a millis
  value by accident the way one overloaded `Long` parameter could.

**Also amended:** CLAUDE.md §10 step 8, which still said "two destinations is the target" against a
design with eight screens. The author's reasoning is worth keeping verbatim — the extra six aren't
decoration, they cover states the app can actually be in that had nowhere to live (raw-HTML
descriptions, download progress, the outbox, `ERROR`, failure diagnostics). "Minimal UI" survives as
a principle: every screen earns its place, and there is still no player, no queue editor, no feed
form.

**The lesson worth recording** is about ADR timing. 0001–0011 were each written when the decision
was actually made and are all "Accepted". 0012 was written *ahead* of its decision, to capture design
intent — and then sat as a draft blocking four pieces of code, with a "Still to settle" section that
no amount of agent work could close. Writing the record early does not accelerate the decision; it
just creates a document that looks authoritative and isn't. Write it when the decision happens.

**Verified:** `./gradlew ktlintCheck detekt test` — documentation-only, no source touched, run
rather than assumed. `docs/architecture.md` §12's "Still open" section now says "Nothing", which is
true for the first time since it was written.

## 2026-08-01 (later) — Tier 4c step 1: dependencies pinned, `:core:model` widened

**Attempted:** the first two unchecked items in `TODO.md` Tier 4c — pin the two approved
dependencies, then declare everything the UI needs in `:core:model`.

**Verified:** `./gradlew ktlintCheck detekt test` — exit 0. **288 tests, 3 skipped** (up from 269;
the skips are still `OpodsyncIntegrationTest` self-skipping without a server). `:core:model` went
from 4 tests to 23.

**"Pure declarations, no behaviour" did not survive contact.** Two things broke that framing, both
correctly:

1. **Three of the new types carry logic**, because each has two callers and would otherwise be
   duplicated: `EpochTime`'s millis-vs-seconds naming, `SwipeMapping.with`'s swap invariant, and
   `OlderThan.cutoffMillis`'s calendar arithmetic. The last is the interesting one — `Period`
   subtraction preserves the *wall clock*, so a month back from 31 March in Europe/Berlin lands on
   28 February at 10:00 UTC, not 09:00, because the DST boundary falls between them. My hand-written
   expectation said 09:00; the code was right and the test was wrong. Fixed the expectation and kept
   the case, since it documents the semantics better than the one I meant to write.
2. **Widening a port means implementing it.** Adding four `SettingsRepository` methods and two
   `EpisodeLedgerRepository` methods broke `:core:datastore`, `:core:database`, `SafDownloadTarget`
   and five hand-written test fakes. That is the honest cost of hand-written fakes over mocks, and
   still the right trade — but it means "declare the ports first so feature work can parallelise"
   understates the size of the step.

**detekt earned its keep twice.**

- `TooManyFunctions` on `EpisodeLedgerDao` (11, threshold 11). The fix was not a suppression: the
  DAO's own KDoc opened with "DAO for the ledger table **and** the UI-facing episode-list joins" —
  it had been confessing to two responsibilities in its first sentence. Split into
  `EpisodeLedgerDao` and `EpisodeListDao`. The list queries and `countUndecidedByFeed` stayed
  together deliberately: they must resolve the same "no ledger row" predicate or a badge can
  disagree with the list it opens.
- `SwallowedException` ×4 on `SafDownloadTarget.freeBytes`, which had four `catch` clauses each
  returning `null`. Restructured to reuse the file's existing `runCatchingSaf` helper, which carries
  every message into a `Result` instead of discarding it.

Two suppressions were added rather than designed away, each with its reason in the KDoc:
`TooManyFunctions` on `SettingsRepository` (the method count *is* the setting count — it grows
linearly with the settings screen and says nothing about complexity) and `MagicNumber` on
`OlderThan` (each constant's name states its own number).

**One thing I built and then deleted.** I added a `undecidedKeys` DAO query alongside
`countUndecidedByFeed`, anticipating the bulk-write path. It had no caller — the port only needs the
preview — and CLAUDE.md §3 names exactly that as an anti-pattern. Removing it also happened to be
what brought the DAO back under the function threshold, which is a neat coincidence and not the
reason.

**Deviations from what the design documents said**, both recorded in `docs/UI_interface.md`:
`previewUndecided` returns `List<FeedUndecidedCount>` rather than `List<Pair<String, Int>>`
(`first`/`second` says nothing about which is which), and `BulkScope` is a data class rather than an
enum, because "older than" has to carry its cutoff and both scopes need the optional per-feed
narrowing *Download all* uses.

**Not done, stated plainly:** Coil and Lucide are pinned but unused — nothing renders yet.
`Episode.link` exists on the domain type but is not mapped in `:core:feed` and not stored; that
needs schema v2, which is the next step along with the `error_log` table and removing the retired
`firstSeenAt` cutoff. `LogRepository`, `ConnectivityMonitor` and `NextcloudLoginFlowClient` are
declarations with no implementation behind them. `SafDownloadTarget.freeBytes` is as untested as the
rest of that class — it needs a real `DocumentsProvider`.

## 2026-08-01 (later still) — Tier 4c: every foundation the UI needs, and none of the UI

**Attempted:** all of Tier 4c — the eight screens plus everything under them.

**Delivered:** everything under them. **Not the screens.** 339 tests (up from 288),
`ktlintCheck detekt test assembleDebug` green. Being plain about the split matters more than the
total: `:feature:episodes` contains one pure function and `:feature:settings` is still empty
scaffolding, so the app installs and shows a placeholder.

I misjudged the size. Eight screens with per-screen state classes, ViewModels and smoke tests is
comparable in volume to everything below them put together, and the foundations turned out to be
much more than the "declare a few ports" the plan implied — widening a port means implementing it
in three modules and updating every hand-written fake.

**What got built, and what it cost:**

- **Schema v2**, the project's first migration. `MigrationTest` runs against the exported v1 schema
  rather than the current entities, which is the only way it can fail for the right reason. The
  point of the test is the ledger: a destructive fallback would drop `episode_ledger` and every
  handled episode would return as new, here and on every client after the next sync.
- **The error log.** Collapse-on-identity and eviction are queries. The identity folds digits,
  because the same failure never carries the same text twice — and a test records the deliberate
  over-reach that "failure 1" and "failure 2" therefore collapse too. That test also caught my
  eviction test, which had numbered its 220 entries and so was really asserting against a single
  collapsed row.
- **ADR 0013's cutoff, removed** rather than left behind a flag.
- **ADR 0012's flag and guard**, with the two-run test that only differs by the flag.
- **Login Flow v2.** Writing it surfaced that forcing HTTPS on the typed address would reject
  servers `RetrofitGpodderClient` then happily uses — so a bare host defaults to https and an
  explicit scheme is honoured rather than silently rewritten.
- **`sanitizeEpisodeHtml`**, which TODO flagged as worth doing early and was: writing the test found
  two real bugs in my own first version. `AnnotatedString.Builder.toString()` is not the accumulated
  text, so the line-break collapse silently never fired; and my link handling would have made
  `javascript:` hrefs clickable, which Compose hands straight to an `Intent`.

**detekt was the most useful reviewer again**, and I fixed rather than suppressed four times:
`MarkOldEpisodesRule` extracted from a nine-dependency `FeedRefresher`, `DownloadRequest` grouping
the pipeline entry point, named access replacing a five-way destructuring, and a speculative
`undecidedKeys` query deleted for having no caller. Three suppressions remain, each with its reason
in the KDoc.

**The lesson worth recording** is about estimating from a plan written by a previous session. TODO
listed the foundations as a handful of bullets and the screens as four; the ratio in reality was the
other way around. A plan's bullet count is not a size estimate, and "pure declarations, no
behaviour" hid three modules' worth of implementation behind it. Next session starts with the
screens and nothing else in front of them.

## 2026-08-01 (last) — Documentation reconciled after the foundations landed

**Attempted:** re-read every document against the two merged PRs and compact what the merges made
redundant.

**The big compaction:** `docs/UI_interface.md` §8 was a 178-line gap list — ten subsections each
describing, in code, a port the UI needed and the repository did not have. All ten now exist. It is
now a ten-row table saying what each became, plus the three places the built shape differs from the
sketch (`previewUndecided`'s named return type, `BulkScope` as a data class, `Instant` as
`java.time`) and the one thing still genuinely missing — the error-log write points outside
`FeedRefresher`. The file went 873 → 813 lines while gaining information about what exists.

**What was stale, and how it read:** four documents still said "269 tests"; `architecture.md` §5's
callout listed `LogRepository`, `ConnectivityMonitor` and `NextcloudLoginFlowClient` as "declared but
have no implementation yet" when all three had been implemented in the same PR that declared them;
`UI.md` §15 still called S8 "the one screen with no backend"; `HANDOVER.md` opened with a four-step
plan whose first three steps were done. `README.md`'s status paragraph said "no UI yet" where "no
screens yet" is now the accurate distinction — the difference matters, because the theme, the
sanitiser and every port *are* UI work.

**A pattern worth naming:** every one of those was a document describing a *gap*, and gaps are the
thing most likely to go stale, because closing one is exactly the work that makes the description
wrong. Statements about what exists age slowly; statements about what is missing age the moment
someone does the work. Worth writing gap lists as tables that can be flipped to "built" in one edit,
which is what §8 now is.

**Verified:** `./gradlew ktlintCheck detekt test` green — documentation-only, no source touched.
Also checked every internal anchor and relative file link across the four long documents; two
anchors were broken by heading renames in earlier sessions and are fixed.

## 2026-08-01 (continued) — S2's logic layer, and two coroutine-test dead ends

**Attempted:** the episode screens. **Delivered:** S2's state types, events, effects, the shared
`EpisodeUi` projection, `TriageWriter`, and `EpisodeListViewModel` — 18 tests. **No Composable.**
357 tests total, `ktlintCheck detekt test` green.

**Two dead ends worth recording, both about testing a view model, neither about the product:**

1. **An injected `CoroutineScope` looked like the clean seam and isn't.** I gave the view model a
   `scope: CoroutineScope? = null` parameter so a test could pass `TestScope`. Passing `this` made
   `runTest` hang forever — the `init`-launched collector never completes, and `runTest` waits for
   its children. Passing `backgroundScope` stopped the hang and then silently ran *nothing*:
   `advanceUntilIdle()` did not drive it, so every assertion saw the initial state. Two failed
   attempts before I stopped patching the test and changed the design.
2. **The fix was to delete the parameter.** `Dispatchers.setMain(UnconfinedTestDispatcher())` plus
   the real `viewModelScope` is the standard seam, and it made the production class *simpler* —
   no scope parameter at all, and an event is fully processed by the time `onEvent` returns.

**The design changes that fell out of it were improvements, not concessions:**

- `state` is now `stateIn(WhileSubscribed)` rather than a `MutableStateFlow` pushed from `init`. A
  500-episode list should not be re-projected on every ledger write while the user is in settings,
  and it removes the untestable init-launched job.
- `onSwipe` used to read `state.value.swipeMapping`. That is wrong whenever nothing is collecting
  `state` — it would silently use the *default* mapping. It now reads the setting directly. The bug
  was invisible until `WhileSubscribed` made "nobody is subscribed" a real state.

**A diagnostic worth reusing:** when the ViewModel tests all failed identically with empty state, I
wrote a throwaway `DiagTest` that printed the fake's output directly and then the view model's. The
fake was right and the view model's state never left `Loading`, which located the problem in the
scope rather than the query in one run. Deleted afterwards.

**Not done:** every Composable. S2 has a tested logic layer and no screen; S1 and S3 have neither.
That is the next session, and it is now genuinely only rendering work.

## 2026-08-02 — A consistency audit against the ADRs, and what it found

**Attempted:** check the implementation against the sixteen accepted ADRs, rather than against my
memory of them. All sixteen are Accepted; none open. The interesting part was the code.

**The audit method that worked:** read each ADR's *Consequences* section as a checklist and grep for
each item, instead of re-reading the code and asking "does this look right". Four of the ten findings
were consequences an ADR explicitly asked for and nobody did — invisible to any amount of reading the
code, obvious the moment you diff intent against reality.

**The two that were actual bugs:**

- **`DownloadAllRequested` emitted a "Queued (n)" snackbar and queued nothing.** A stub that lies is
  worse than no stub: the user would tap *Download all*, be told twelve episodes were queued, and get
  nothing. Replaced with the `BulkPreview` ADR 0014 actually requires — the count is named *before*
  anything is written, and only `DownloadAllConfirmed` writes.
- **`isRefreshing` was set and cleared on consecutive lines** around a synchronous enqueue, so it was
  never observably true and a pull-to-refresh indicator could never appear. Fixing it properly meant
  changing `EpisodeScheduler.requestFeedRefresh` to `suspend` and holding the flag for the duration —
  which is what `docs/UI.md` §4 asked for all along.

**Two stale KDocs, both in the exact place a reader would look for the truth:** `existingNames` still
said "explicitly **not** a de-duplication check" with no mention of the one licensed exception —
precisely the confusion ADR 0012 predicted and asked to be pre-empted. And `Feed.firstSeenAt` still
documented the `pubDate >= firstSeenAt` cutoff that ADR 0013 retired. I had updated
`architecture.md`'s field table and not the code comment, which is the wrong way round: the comment
is what someone editing the field will read.

**An ADR whose invariant was wrong, not the code.** ADR 0016 claimed `EpochTime` was "the only
`Instant.ofEpochMilli` call site outside `:core:naming` and `:core:sync` — worth one grep in review".
The grep found three, all correct: parsing an RSS date, formatting a tag, and calendar arithmetic
inside `OlderThan`. The rule I meant was about the *storage↔UI boundary*, and I wrote it as a
repo-wide textual rule because that was easier to state. Narrowed it to what it means, with a table
of why each existing call site is fine — and the narrow version now actually holds.

**Deleted:** `WorkScheduler.enqueueDownloads`, which had no caller. I deleted a speculative
`undecidedKeys` query two sessions ago for exactly this reason and then added this one myself.

**Left open deliberately:** `EpisodeUi` diverges from `docs/UI_interface.md` §1, and one divergence
has teeth — the doc's `FailureUi` carries a `retryable` flag that the built bare-`String` `lastError`
does not, so ADR 0011's "that row offers *Choose folder*, never *Retry*" cannot be enforced. That is
a design question for when the screen exists, not something to paper over now, so it is recorded in
`TODO.md` as a warning rather than silently fixed in one direction.

**Verified:** `./gradlew ktlintCheck detekt test assembleDebug` — 363 tests, green.

## 2026-08-02 (later) — Making the seam document true, and what that cost

**Attempted:** findings 7–9 from the audit — the types `docs/UI_interface.md` §1 declares that the
code did not have. The instruction was "fix them according to the documentation", so the doc was
treated as authoritative and the code moved to meet it.

**The interesting one was `FailureUi`, because it could not be built as a UI-only type.** The doc
declares `FailureUi(cause: ErrorCause, message, attempts, retryable)`, and `cause` is what
`docs/UI.md` §12.11 and ADR 0011 hang a real guarantee on: a row whose download failed because the
folder grant is gone must offer **Choose folder** and never a bare **Retry**, because retrying cannot
work until the user acts.

The ledger stored only `lastError: String`. So there were two options: classify by pattern-matching
the message in the UI, or store the classification where it is known. Matching prose would fail the
first time a message was reworded — and it would fail *silently, into the unsafe direction*, showing
a Retry button that cannot work. So: schema v3, two nullable columns
(`lastErrorCause`, `lastErrorRetryable`), classified by the download pipeline that already knew.

Both columns, not one derived from the other, because they genuinely differ: a 404 and a 503 are both
`SERVER` and only one is worth retrying.

**Historical rows get `null`, which reads as `UNKNOWN` and offers a plain Retry.** That is the safe
default and the migration test says so: offering a Retry that fails is recoverable, hiding the only
useful button is not. Guessing a cause from the stored sentence would have been the other way round.

**Two smaller judgement calls, both recorded in the doc rather than left as silent divergence:**

- `QueueStatus`'s `DISK_FULL` is inferred from a row that *actually failed for space*, not from
  probing free space. A volume can be nearly full and still fit the next episode; "a download already
  failed this way" is a fact, "space looks tight" is a guess.
- The doc puts these types in `:core:model`. Only `ErrorCause` went there — because the ledger stores
  it. The rest are UI vocabulary and stayed in `:feature:episodes`, which keeps `:core:model` the
  Android-free domain rather than a place screens keep their projections. The doc now says so.

**A test that was wrong in an instructive way:** four new failure tests asserted on the default *To
decide* filter, and an `ERROR` row is not "to decide" — it *has* a ledger row. The code was right and
the setup was wrong, which is the good version of a failing test.

**Verified:** `./gradlew ktlintCheck detekt test assembleDebug` — 372 tests, green.

---

## 2026-08-02 (evening) — The first screen, and an emulator that had never booted

**Attempted:** S2's Composable, its tests, and — because the instruction was that testing had to go
through the software emulator — actually making Tier 2 work, which no previous session had managed.

### The emulator: an error message pointing at the wrong thing

`docs/dev-environment.md` had recorded the failure honestly and left it uninvestigated:

```
Error: Missing system image for Google APIs x86_64 podsilo-test.
```

The system image was installed the whole time. `avdmanager` infers the SDK root from **its own
location**, not from `ANDROID_HOME` — and this container deliberately keeps the command-line tools
at `/opt/android-cmdline-tools`, outside `$ANDROID_HOME=/opt/android-sdk`, so the volume mount can't
shadow them (§8.5). So it decided the root was `/opt` and wrote

```ini
image.sysdir.1 = android-sdk/system-images/...   →  /opt/android-sdk/android-sdk/...
```

One `sed` on one line, and the AVD boots in 28 s. **The lesson is about how the failure was framed,
not the fix.** The doc's diagnosis — "looks like a partially created AVD" — was a reasonable guess
from the error text, and it sent nobody anywhere for two sessions. What broke it open was ignoring
the message entirely and diffing the *config file* against what the path should be. The clue had
been on screen all along, in warnings everyone reads past: `Observed package id 'emulator' in
inconsistent location '/opt/android-sdk/emulator' (Expected '/opt/emulator')`.

It is now `scripts/emulator-start.sh`, verified from a deleted AVD, because a fix that lives only in
a journal entry is a fix that gets rediscovered.

### The screen

`EpisodeListScreen` renders state and emits events and decides nothing. That is not a style
preference here: `EpisodeUi.actions` is computed once in the view model, so the row body, the
overflow menu and the accessibility actions **cannot disagree** about what an episode currently
offers. A screen that re-derived "should this show Retry?" from `ledgerState` would be a second
opinion, and ADR 0011 is precisely a case where the second opinion is wrong.

14 Compose tests run under Robolectric — Tier 1 by CLAUDE.md §4's own definition (headless, no
emulator), and they run in seconds. The two instrumented tests exist to prove the *tier*, not to
duplicate coverage. Worth keeping that ratio.

**Two tests that only a rendering test can catch**, both from `docs/UI_interface.md` §7:

- a `DOWNLOADING` row with no in-process progress must say *resuming*, not draw 0 %. After process
  death WorkManager's progress is gone; a progress bar at zero asserts something false.
- a missing duration has *no part* in the meta line — not "unknown", not a fabricated value.

### detekt as a design reviewer, again

Five findings, four fixed by splitting `EpisodeRow` out of the screen file. The split is right on its
own terms — chrome and row have different jobs — but it was detekt that noticed, which is the third
session running where a length rule found a real seam. The one genuine suppression was
`CyclomaticComplexMethod` on an exhaustive `when` over a sealed event hierarchy: the complexity is
the point, and collapsing it would hide which events exist.

**Verified:** `./gradlew ktlintCheck detekt test assembleDebug` green, 386 tests, 3 skipped; plus
`:feature:episodes:connectedDebugAndroidTest`, 2 tests green on `podsilo-ci(AVD) - 15`.

**Still true:** the app has never been launched by a human. S2 has no route into it — `MainActivity`
still renders a placeholder — so what ran on the emulator was the screen under test, not the app.

---

## 2026-08-02 (later) — S1, S3, a NavHost, and the app running for the first time

**Attempted:** the rest of `:feature:episodes` (S1 podcast list, S3 detail sheet) plus the `:app`
navigation that makes them reachable — and then actually launching the thing.

### The bug that mattered

The app started, S1 rendered, and the naming preview on the setup checklist showed `—` — its
failure fallback. `adb logcat`:

```
Caused by: java.util.regex.PatternSyntaxException: Syntax error in regexp pattern near index 21
\{(\w+)(?::([^}]*))?}
    at net.drehtuer.podsilo.core.naming.TemplateTokenKt.<clinit>
```

Android's regex engine is ICU. **ICU rejects an unescaped `}`; the JVM accepts it.** The pattern is
a top-level `val`, so on a device it threw inside a static initialiser and took every filename in
the app with it — not just the preview.

**437 JVM tests were green, and could not have caught it.** `:core:naming` is pure JVM by design;
the code is identical, only the engine differs. Its table tests are the ones CLAUDE.md §7 ranks
fifth-highest *because naming is the entire user experience of a download*, and they were compiling
that regex with the wrong implementation. One character, invisible to the whole suite.

That is now `docs/decisions/0017`: pure-JVM logic that ships inside the app gets **one** test that
runs on a real Android runtime — not a mirrored suite, a thin one whose job is to load the classes
and compile the patterns under the platform's own implementations. `NamingOnAndroidTest` is four
tests covering all four of the module's regexes.

**The uncomfortable part is the timing.** Tier 2 started working yesterday. This bug has been in the
tree since Tier 1 and would have shipped. The lesson is not "write more tests" — the suite was
thorough — it is that *a green suite proves things about the environment it ran in*, and a
pure-JVM module inside an Android app has two environments.

### Two smaller things the first run found

Both invisible to unit tests, both obvious in one screenshot:

- The paused banner's message took the whole row, wrapping *Choose folder* into `Choos / e /
  folder`. A `weight(1f)` and `softWrap = false`.
- On first launch the paused banner and the setup checklist both said "choose a download folder",
  stacked. The checklist says it better, attached to the step it belongs to, so the banner now
  yields to it. Regression tests for both.

Screenshots are cheap and I should have taken one earlier.

### detekt found the port split, again

Adding S1's two queries pushed `EpisodeLedgerRepository` to eleven methods and detekt flagged it. My
first move was to suppress and write a backlog entry — then it flagged the *interface* too, which
was the honest signal that the split was due now, not later.

`EpisodeListRepository` is the four UI-facing joins; `EpisodeLedgerRepository` keeps the durable
record and its outbox. **The seam already existed** — the DAOs were split along exactly that line
two sessions ago, with a KDoc explaining why. Four production call sites; the test fakes implement
both interfaces, which is honest for a double. This is the fourth session running where a detekt
length rule found a real seam rather than a style nit.

### Frozen ordering

S1's list is sorted once on cold start and once per explicit refresh, then held as a list of URLs
into which updated rows are re-projected (`docs/UI.md` §4). Deliberately an `init`-launched job and
a `MutableStateFlow` rather than something derived inside `state`: "computed once" cannot be
expressed as a derivation of the flows it must not react to. A feed subscribed since the last freeze
is **appended**, not sorted in — it has to appear, but inserting it mid-list would shift every row
below it, which is the movement the rule exists to prevent. Both have tests.

**Verified:** `ktlintCheck detekt test assembleDebug` green, 437 tests, 3 skipped; six instrumented
tests green on the emulator; the app installed, launched, and rendered S1 with no crash.

**Still true:** S4–S8 do not exist, so the buttons that would open them show a snackbar naming the
missing screen. Nothing has been tested against a real Nextcloud, and no episode has ever been
downloaded by the running app.

---

## 2026-08-02 (late) — `:feature:settings`: S4, S5, S6, and the app is usable end to end

**Attempted:** the whole settings module. S4 (settings), S5 (the Nextcloud connection dialog) and
S6 (the naming editor), wired into the NavHost, then run on the emulator.

### The screen that pays for itself

S6's live preview is the best thing in this change, and it cost almost nothing because
`:core:naming` was already built and exhaustively tested. On the device it renders:

```
A recent episode     Der Podcast/20260714_Warum Hamburg immer regnet.mp3
No publication date  Der Podcast/00000000_Folge ohne Datum.mp3
A very long title    Der Podcast/20260714_Über Über … Über Üb.mp3
Awkward characters   Der Podcast/20260714_Ep 3_4_ _Regen_ _live_ _ CON.mp3
```

Four lines that demonstrate four rules the author would otherwise have to take on trust: ADR 0004's
`00000000` for a missing date, UTF-8 **byte**-budgeted truncation (note it stops at `Üb` — mid-word,
not mid-character), and FAT32 sanitisation. The editor contains none of that logic; it calls the
same `resolve()` a download calls, which is exactly why the preview is worth believing.

**That is the argument for building the cheap screen next to the expensive module**, and it
generalises: a preview is only meaningful when it is the production code path.

### One rule, three places, one implementation

`SwipeMapping.with()` already encoded "the two directions can't hold the same action, so assigning
a taken one *swaps* rather than rejects". S4's dropdowns just delegate to it. The temptation was to
validate in the view model — and the reason not to is that the swipe *background* renders from the
same mapping, so a rule enforced anywhere but the type would need a defensive branch in the row too.

### What the first device run found this time

Only one, and a small one: the five *older than* chips overflowed a phone's width and the last
wrapped to one letter per line. `FlowRow` instead of `Row`. Two sessions ago I would have shipped
it; now the emulator run is part of finishing, which is the process change that stuck.

Worth recording that the ratio is improving: the first launch found three bugs, this one found one.

### detekt, five findings, all real

Two file splits (`SettingsRows`, the naming screen's preview and chip sections), one dead
`unusedFlow` left over from an earlier draft, and a rethrown `CancellationException` I had added out
of habit — the client's contract is that a cancelled poll simply stops asking, so catching it to
rethrow it was noise pretending to be care. The one suppression is `ReturnCount` on `connect()`,
where the three early returns *are* the rule: three ways to stop without storing anything.

### The order in S5 is the whole feature

```
start flow → open browser → poll → verify gpoddersync → only now store
```

Success is claimed **only** after the authenticated `GET /subscriptions` returns 200. A completed
login flow proves the server is a Nextcloud and the password works; it says nothing about
gpoddersync being installed, and connecting to a Nextcloud without it leaves an app that silently
syncs nothing. The test that asserts the app password is discarded in that case is the most
valuable one in the module — and there is a matching Compose test asserting the dialog has no
password or username field at all, which is the shape of guarantee that survives refactoring.

**Verified:** `ktlintCheck detekt test assembleDebug` green, 479 tests, 3 skipped; six instrumented
tests green; S1 → S4 → S5 and S4 → S6 all navigated on the emulator with no crash.

**Still true:** nothing has been tested against a real Nextcloud — S5 has never completed a real
login flow, only a scripted fake. S7 and S8 do not exist, so *Activity* and *Error log* still show
a snackbar saying so.

---

## 2026-08-02 (night) — S7, S8, and the icons that were never there

**Attempted:** the last two screens, plus the icons — the author noticed they were missing, which
they were: ADR 0015 accepted Lucide, the dependency was pinned in the catalog, and no call site had
ever used it. Every affordance was a text button.

### The dependency was not what the ADR thought it was

ADR 0015 was written during the design pass, before the artifact had ever been resolved, and assumed
`com.composables:icons-lucide-android` exposes `ImageVector`s the way `Icons.Filled.*` does. Pulling
it apart:

```
$ unzip -l icons-lucide-android-2.2.1.aar | tail -3
     1045  res/drawable/lucide_ic_zoom_in.xml
      844  res/drawable/lucide_ic_zoom_out.xml
  1897574  1671 files
$ ls -la classes.jar
-rw-r--r-- 22 classes.jar        # empty
```

It is a **`VectorDrawable` resource pack**, not a Kotlin API. Every reason for the decision survives
— one dependency against 27 files we would hand-convert and maintain, ISC/MIT, one weight, R8 strips
the rest — but the call site is `painterResource(R.drawable.lucide_ic_*)` and the constants are
`@DrawableRes Int`s. ADR 0015 now says so.

**The consequence is the interesting part: a wrong name is a runtime `0`, not a compile error.** An
invisible icon, silently. So `PodsiloIconsTest` asserts all 27 resolve non-zero — and asserts the
three pairs §18 calls non-interchangeable really are different glyphs, because
`HandledRemotely = PodsiloIcons.Check` would compile, pass every screen test, and quietly claim the
user made a decision they did not.

### `:core:ui` earned its place, but not on the first argument

I created it for the icon mapping — one object making §18's "an icon not listed here has no call
site" enforceable rather than aspirational. The better justification turned up while wiring it: the
spacing constants (`RowPadding`, `MinTouchTarget`, `MinRowHeight`, `MaxContentWidth`) were
**duplicated in both feature modules**, which is exactly the drift §17 exists to prevent. Two
screens that disagree about a row height read as two apps. They live in one place now.

### S7 reads the ledger, not WorkManager

Worth stating because the opposite is the obvious implementation: the queued and failed groups come
from the ledger, which is durable and survives process death, whereas `WorkInfo` does not. Live byte
progress is the *only* thing that comes from the worker — and its absence renders as *resuming*
rather than 0 %, the same rule as the row and the sheet.

The other rule S7 has to keep is a negative one: it shows what was written and offers no delete, no
open-file and no existence check. A test asserts the absence, because "not a file manager" is the
kind of thing that erodes one helpful button at a time.

### One device bug, again exactly one

The paused banner clipped *Choose folder* to `Cl`. I had put `weight(1f, fill = false)` on the
`Text` inside the icon-plus-message `Row` rather than on the `Row` itself, so the group still took
the full width. Three sessions, three banners, three variations of the same layout mistake — the
lesson is that a `Row` with a growing left side and a fixed right side needs the weight on the
*group*, and I should reach for that shape deliberately instead of rediscovering it.

**Verified:** `ktlintCheck detekt test assembleDebug` green, 502 tests, 3 skipped; six instrumented
tests green; S1 → S7 → S8 driven on the emulator, icons rendering, no crashes.

**All eight screens now exist.** `notBuiltYet` — the snackbar that named a missing screen — has no
callers left and was deleted.

**Still true, and now the only thing left:** nothing has ever run against a real Nextcloud. Every
sync path is exercised by `MockWebServer` and `opodsync`; S5 has never completed a real login flow;
no episode has been downloaded by the running app. That is the next thing worth doing, and it is
not a coding task.

---

## 2026-08-02 (late night) — the first real Nextcloud, and the bug that found itself

**Attempted:** connect to the author's own Nextcloud and read their subscriptions. Everything in
this project had, until now, only ever talked to `MockWebServer` and opodsync.

### The typo that exposed a real bug

The first address given was `cloud.drehtuer.de`; the instance is at `cloud.drehtuer.**net**`. Our
client reported it correctly —

```
✗ start failed: cloud.drehtuer.de: Name or service not known
```

— but **S5 would not have.** `ConnectViewModel` was doing this at all three steps:

```kotlin
loginFlowClient.start(baseUrl).getOrElse { return fail(ConnectError.NOT_NEXTCLOUD) }
```

The client types its failures (`UNREACHABLE`, `TLS`, `NOT_NEXTCLOUD`, …) and the view model threw
every one of them away. A mistyped host would have said *"This doesn't look like a Nextcloud
server"* — sending the author to check their server instead of their spelling, which is exactly the
confusion `docs/UI.md` §8's message table was written to prevent.

`LoginFlowFailure` lived next to the Retrofit implementation, so `:feature:settings` could not see
it. It belongs on the port: **the kind of failure is part of the contract the UI binds to**, and a
caller that cannot tell the cases apart can only ever show one message. Moved to `:core:model`,
mapped through in S5, three tests.

**Worth sitting with:** I wrote that `getOrElse` block, and I wrote the ADR that says these must be
distinguishable, and I wrote the Compose test asserting each `ConnectError` has its own sentence —
which passed, because it tested the mapping from error to string and never the mapping from failure
to error. The bug lived in the seam between two things I had each tested. It took *one wrong
character in a hostname* to surface it.

### What the real server settled

Two things the docs explicitly flagged as unverified:

- **`add − remove` is load-bearing.** CLAUDE.md §5 specified that formula because the no-`since`
  response was ambiguous between "current set" and "complete change log", and it is correct under
  either. The real response: **`add=8, remove=50`**. `remove` carries history. Reading `add` alone
  would have been right here only by accident.
- **The timestamp format is `+00:00`, and all 3,022 of them parse.** ADR 0009 predicted this from
  reading the `nextcloud-gpodder` source. CLAUDE.md §11 calls this the failure that *does not
  crash* — it silently breaks incremental sync. Now checked against reality rather than our own
  fixtures.

And one thing nobody had asked: **3,005 of the 3,022 actions are `PLAY`**. The author's backlog is
already triaged elsewhere, so the "5,000-row New tab" hazard that shaped ADR 0013 will not
materialise on their data. The design is still right; the pressure behind it was lower than assumed.

### The probe is a `main`, not a test

`./gradlew :core:gpodder:nextcloudProbe -Phost=…` runs the production classes, prints a URL for a
human to approve, and does two `GET`s. Read-only by construction: it cannot post an episode action
because it never calls the method. The app password stays in memory — never printed, never written.
JUnit never collects it, so §7's offline rule is untouched.

Building it before knowing whether the host was reachable was the right call: the DNS failure came
back through *our* error path, which is how the S5 bug appeared at all.

**Verified:** `ktlintCheck detekt test assembleDebug` green, 505 tests, 3 skipped; two live runs
against Nextcloud 33.0.5, both read-only, nothing written.

**Still true:** `SafDownloadTarget` and `KeystoreAppPasswordCipher` have never executed, no episode
has been downloaded by the running app, and ADR 0008 (Nextcloud discards `DOWNLOAD`) stays
source-read-only — confirming it needs a *write*, which this probe deliberately cannot do.

---

## 2026-08-02 (later still) — the login screen could never have worked

**Attempted:** drive the real S5 on the emulator against a live Nextcloud, using a test account.

**Tapping *Request authorization* killed the app.**

```
FATAL EXCEPTION: main
android.os.NetworkOnMainThreadException
    at okhttp3.Dns$Companion$DnsSystem.lookup(Dns.kt:50)
    at ...RetrofitNextcloudLoginFlowClient.start
```

`OkHttpClient.execute()` blocks. `ConnectViewModel` calls these `suspend` functions from
`viewModelScope.launch`, which is `Dispatchers.Main.immediate`. The client had no `withContext`. So
the DNS lookup ran on the main thread and StrictMode killed the process — **on any device, every
time**. The screen has existed for two sessions and could never have completed a login.

CLAUDE.md §8 says it in one line: *"No blocking calls on the main dispatcher. Inject dispatchers
(`@IoDispatcher`) for testability."* I wrote a `suspend fun` and assumed that made it safe. It does
not: `suspend` says "this can be paused", not "this is off the main thread". The function is only as
safe as the dispatcher its caller happens to be on, which is precisely why the rule is phrased as
*inject the dispatcher* rather than *use coroutines*.

### Why 505 tests missed it

A JVM has no main-thread policy. `Dispatchers.setMain(UnconfinedTestDispatcher())` runs the call on
the test thread and MockWebServer answers cheerfully. **This is the same shape as the ICU regex bug
two sessions ago** — a JVM-only truth that Android disagrees with — which is what ADR 0017 was
written about. Two instances now, from different directions: one a library difference, one a
platform policy.

`LoginFlowDispatcherTest` asserts the property StrictMode exists to enforce: an OkHttp interceptor
records the thread the call actually ran on, and the test asserts it is not the caller's. I checked
it fails without the fix and passes with it, rather than trusting that it would.

### The other fix, working

The dialog reported **"Can't reach that address. Check the spelling and your network."** — which is
this morning's error-mapping fix visibly doing its job on a device. Before it, the same failure said
*"This doesn't look like a Nextcloud server."*

### Still open

The poll then failed with an `IOException` after roughly two minutes (the limit is 200 × 3 s = 10
min, so not a timeout). The process survived — same PID — so it is not a crash. Unexplained;
emulator NAT flakiness is the first suspect, but I have not proven it. Testing resumes there.

**Verified:** `ktlintCheck detekt test assembleDebug` green, 507 tests, 3 skipped; the crash no
longer reproduces on the device.

**Note to self:** the temporary `Log.i` used to capture the login URL out of the app was reverted
before committing. Worth having a real answer for "how do I see what URL the app opened" that is not
a hand-edited log line.

---

## 2026-08-02 (night) — the two classes nothing had ever run

**Attempted:** with the live-Nextcloud login deferred, the emulator work that needs no server at
all — the two pieces `docs/backlog.md` called *"the highest-value item here"* and that
`docs/dev-environment.md` listed as **never run**.

Both are now verified, and neither needed Nextcloud.

### `KeystoreAppPasswordCipher` (ADR 0010)

Robolectric has no `AndroidKeyStore` provider, which is why this class was abstracted behind a seam
in the first place — and the consequence nobody had stated plainly is that **the guarantee "the app
password is never stored in plaintext" rested on code that had never executed.** Six tests, green.

The one worth calling out is *a second instance decrypts what the first wrote*. That is the real
usage — S5 encrypts during login, `SyncWorker` decrypts in a later process — and a per-instance key
would pass a round-trip test and then fail only after a restart, with no obvious cause.

### `SafDownloadTarget` (ADR 0011)

The file write had never happened. Now it has, and the evidence is on the emulator's filesystem
rather than in an assertion:

```
20260714_Wärme über Hamburg.mp3   ← umlauts survived SAF
Nested Feed/                       ← the {podcast} subfolder was created
delivered.mp3   28 bytes           ← identical to the source
retried.mp3      8 bytes           ← "complete", not the 7-byte "partial"
```

`retried.mp3` is the assertion I care about: a retry reuses the ledger's `writtenFileName`, so
delivering twice must overwrite rather than leave `… (2)` beside a half-written file. **The byte
count is what distinguishes the two outcomes** — a test that only checked "a file exists" would
have passed either way.

**Where the tests live turned out to be a real design question, not a filing decision.** A tree-URI
grant belongs to a *package*. `:core:download`'s own test APK is a different package and would have
no usable tree, so the tests sit in `:app`, whose instrumentation runs inside the app's process and
inherits the grant. They `assumeTrue` on a folder having been chosen: a missing grant is a setup
gap, not a regression, and a red suite for a setup gap teaches people to ignore red.

### The grant path itself

Driven through the real UI with `adb input`: S1's *Choose folder* → system picker → the storage root
is refused by Android ("Can't use this folder") → `Podcasts` → Allow. `dumpsys` then shows
`mode=0x3 persistable=0x3 persisted=0x3`, and **the ✓ survives `am force-stop`** — which is exactly
the failure CLAUDE.md §11 warns about and nothing had checked.

### One thing I could not test, and why

Downloading a real episode still needs a subscription, and subscriptions come only from Nextcloud. I
seeded a feed and episode straight into the SQLite file to get around that — and S1 correctly
refused to show them, because with no account configured the *not configured* empty state replaces
the list (`docs/UI.md` §4). The design is right; it just also blocks this particular shortcut. So
the download pipeline end-to-end — enclosure fetch → tag write → SAF copy → ledger → outbox —
remains unproven on a device.

**Verified:** `ktlintCheck detekt test assembleDebug` green, 507 tests, 3 skipped; 12 new
instrumented tests green on the emulator (28 total across the project).
## 2026-08-02 (late) — three bugs in one screen, all found by using it

**Reported by the author**, after actually typing in S6: the cursor jumps to the beginning of the
field, and the absence of any entry for the file extension is confusing.

### The caret

Classic Compose. The fields used the `String` overload of `OutlinedTextField`, and the view model
persists on every keystroke and echoes the template back — so the field kept being rebuilt from a
`String`, which carries **no selection information**. Compose has nowhere to put the caret and puts
it at 0.

The fix is to let the screen own a `TextFieldValue`, and to adopt the view model's text only when it
genuinely differs (which in practice means *Reset to default*). Two small pure functions,
`insertAtCursor` and `syncedFromState`, both table-tested — the logic is worth having outside a
Compose test because it is where the bug actually was.

### The third bug, which nobody reported

While fixing that I found the chips **always inserted into the file template**, whatever had focus.
Editing the folder template and tapping `{podcast}` silently appended to the other field. Nobody had
noticed because nobody had edited the folder template on a device.

Chips now insert into the focused field, at the caret. Verified by hand:

```
{date}_{title}     caret after {date}, type "ZZ"     → {date}ZZ_{title}
                   caret still there, tap {guid_short} → {date}ZZ{guid_short}_{title}
```

Before the fix, the first line would have produced `ZZ{date}_{title}` and the second would have
appended at the end.

### The extension

`{ext}` is deliberately not a chip: the engine does not resolve it, the extension is appended after
resolution, and offering a chip the engine does not know would put the literal text `{ext}` in a
filename (CLAUDE.md §6, `docs/UI.md` §18). All true — and **the absence still reads as an omission**,
because the preview grows a `.mp3` from nowhere.

So the screen now says where it comes from. That is the cheap fix and it removes the confusion
without touching the rule. Whether the extension should become a *placeable* variable is a real
design question — it would mean the engine stops appending when a template contains `{ext}` — and
that is the author's call, not one to make while fixing a caret.

**The lesson is about who finds what.** Twelve Compose tests cover this screen, including one
asserting the placeholder list is exactly what the engine resolves. None of them type a character in
the middle of a string, and none of them tap a chip while a different field has focus. Both bugs
needed a person using the thing.

**Verified:** `ktlintCheck detekt test assembleDebug` green, 514 tests, 3 skipped; both behaviours
confirmed on the emulator by driving the real screen.

---

## 2026-08-02 (very late) — ADR 0008, finally observed rather than inferred

**Attempted:** the one claim in this project that had never been anything but a reading of someone
else's source. `docs/decisions/0008` says `nextcloud-gpodder` filters posted episode actions down to
`play` and returns 200 regardless, so a `DOWNLOAD` looks accepted and vanishes. That came out of
`EpisodeActionController.php`. Nobody had watched it happen.

Confirming it needs a **write**, which is why it waited for a test account.

```
→ POST episode_action/create: 2xx          two posted: one DOWNLOAD, one PLAY
→ read back since=0: 1 actions total (was 0), 1 of them ours
   PLAY  guid=probe-…-play  started=0 position=1800 total=1800
```

Two in, 2xx back, one stored. **The 2xx is the dangerous part** — nothing distinguishes "saved" from
"silently dropped", so a client that trusts the status code believes mark-on-download works. That is
precisely CLAUDE.md §1 requirement 9, and it is now measured rather than inferred: it cannot work
through this server, and Podsilo emits `DOWNLOAD` anyway for the reasons ADR 0008 gives.

The same run round-tripped ADR 0002's skip encoding — `started=0 position=1800 total=1800` came back
byte-for-byte. That is a *positive* result from a run where the negative control also behaved as
predicted, which is worth more than either alone.

### The guard earned its keep immediately

A login flow is approved by whoever happens to be signed in to the browser, so a write-mode probe
could post to a real account by accident. `-Pwrite=<loginName>` names the account allowed to be
written to, and the run aborts otherwise.

I did not know the test account's name, so the first run went out with a deliberate placeholder:

```
✗ REFUSING TO WRITE: approved as 'podsilo', expected 'REPLACE_ME'
  Nothing was written. Re-run and approve as the intended account.
```

It cost one extra approval and demonstrated the safety property before exercising it — which is the
right order for anything that writes to someone else's server.

**Also worth recording:** gpoddersync exposes no way to delete an episode action, so the probe's one
`PLAY` row is permanent on the test account. Synthetic feed, harmless, but the asymmetry is real —
this API can be appended to and never pruned, which is the same reason `since=0` grows without bound
(CLAUDE.md §5).

**Verified:** `ktlintCheck detekt test` green, 514 tests, 3 skipped.

---

## 2026-08-02 (very late) — the sync pass, on real subscriptions

**Attempted:** the thing CLAUDE.md §7 ranks first for test priority — "sync reconciliation, the most
complex, most breakable logic here" — which until now had only ever run against `MockWebServer` and
`opodsync`. The production `SyncOrchestrator`, the production client, real subscriptions, a real
episode.

```
→ sync() #1 (nothing to push)          Success, 4 subscriptions mirrored, since=1785686711
→ real episode: guid=69af61b1b58ea3074ddfc173 from the Acast feed
   local ledger row: SKIPPED, syncedToServer=false
→ sync() #2 (one row in the outbox)    Success, syncedToServer=true, since=1785686715
   server: PLAY guid=69af61b1b58ea3074ddfc173 position=1800 total=1800
                at=2026-08-02T16:05:11+00:00
→ sync() #3 (must change nothing)      Success, still SKIPPED, still synced, since=1785686720
```

Three things this settles that no fixture could:

**The echo case.** CLAUDE.md §7 item 8 names "successful download + POST + remote echo of our own
action" as part of the highest-value test in the project. Pass #3 pulls back the `PLAY` this device
just wrote, and reconciliation leaves the row exactly as it was — it neither re-queues it nor flips
it to something else. That is the actual scenario, against the actual server, rather than a canned
response shaped the way we expected.

**`since` comes from the server's clock.** 1785686711 → 715 → 720, advancing on each pass from the
value the server returned. CLAUDE.md §11 warns that computing it locally silently drops or
duplicates actions; this is the first time the real values have been watched move.

**`guid` identification lines up.** The server stored our `guid` and returned it unchanged, so
`episodeKey = guid ?: enclosureUrl` matches what comes back. If it had normalised or dropped the
guid, every action from another client would fail to line up with our rows — the exact bug CLAUDE.md
§5 says to match "exactly, or actions from AntennaPod won't line up".

### On using real data

The synthetic-feed probe answered *does the server keep a DOWNLOAD*, which needed nothing real. This
one answers *does our reconciliation survive its own echo*, which needs a real feed, a real guid and
a real server timestamp — a synthetic episode would have exercised the same code with none of the
values that make it hard. Worth remembering which questions need which.

The `PLAY` for that episode is permanent on the test account: gpoddersync has no delete.

---

## 2026-08-02 (later) — cover art, and the question worth asking first

**Requested:** embed the episode's image when a downloaded file has none — the feed's per-item
`<itunes:image>` if present, otherwise the podcast's.

The feature spans four modules and needs a schema column, so CLAUDE.md §9 applies twice: *plan
multi-module changes first*, and *ask before a migration*. I asked two questions and the answers
shaped the work: **add the column** (rather than re-fetching the feed at download time), and
**no size cap**.

Asking about the cap was worth it for a reason I did not anticipate. I checked the actual covers on
the author's own feeds first — **~270–290 KB**, about 1% of a 30 MB episode — which turned "should
we cap this?" from a guess into a decision with a number attached. Without that, I would probably
have imposed a default cap and quietly dropped the art of some podcast that ships a 3 MB cover.
Measure before offering options.

### The rule that needed the most care

"If the episode has no image embedded in its id tag" is a *conditional*, and the interesting case is
when the condition cannot be evaluated: `getFirstArtwork()` throws on some containers rather than
returning null. Treating that as "no artwork" would overwrite a publisher's cover because we failed
to read it. It is treated as "artwork exists" — when in doubt, do nothing. That asymmetry is the
whole reason the feature is safe to ship.

The rest follows the module's existing habits: content type is trusted over the URL extension (the
same rule the enclosure follows), a listed-but-404 episode cover falls through to the podcast's
rather than leaving the episode bare, and every failure resolves to no artwork rather than to a lost
download.

### On testing a feature that is mostly plumbing

19 of the 22 new tests are at the layer boundaries — parsing, fetching, embedding, migrating — and
each is cheap. The three that matter more are in `EpisodeDownloaderTest`, because they are the only
ones that can catch the seam the others cannot see: that the downloader actually asks the fetcher
and hands the result to the writer. A feature assembled from four well-tested parts still fails if
nothing wires them together.

**Verified:** `ktlintCheck detekt test assembleDebug` green, 536 tests, 3 skipped.

**Not verified on a device:** no episode has been downloaded by the running app yet, so the artwork
has never reached a real file through the real pipeline. That is the same gap as before, and it is
still waiting on a download that needs a subscription.

---

## 2026-08-02 — Tag format support, database backup, and a bug SQLite handed me

Three requests in one message: does the tagger handle formats that only support a subset of
features; add a database import/export as zip; and delete a podcast's local data when Nextcloud
drops it.

### The third one was already done, and the literal reading of it is wrong

`FeedDao.replaceAll` deletes feeds not in the server's list, `EpisodeEntity` has an
`ON DELETE CASCADE` onto `feeds`, and `SubscriptionMirroringTest` covers it. But the wording — "the
database entries for that podcast should be deleted locally as well" — would, taken literally,
include the ledger rows, and CLAUDE.md §5 forbids exactly that: "keep its `EpisodeLedger` rows… if
the author re-subscribes later we must not re-download the back catalogue." So the answer is "yes,
already, except for the one table that must deliberately survive." Worth saying out loud rather than
quietly implementing the safe half.

### The tagger question turned up a gap that was mine, not the library's

jaudiotagger covers every container a podcast realistically arrives in, artwork included. The only
real hole is raw `.aac`, which CLAUDE.md §6 lists among the extensions to expect and which has no tag
container at all — the file is delivered untagged. Documented in ADR 0006 rather than fixed, because
it is the correct best-effort behaviour and `.aac` in feeds is nearly always AAC-in-MP4 as `.m4a`.

What was actually wrong: artwork failures were **silent** while every other field's failure was
reported through `PartialSuccess.skippedFields`. I shipped that a day ago. Now reported.

I also wrote a test asserting that a file which already had its own cover should count as
`artworkSkipped = true`, then had to correct it — the code was right and the test was wrong. Already
having artwork is the intended outcome of the feature, not a limitation, and conflating the two would
make the flag useless for spotting the container problems it exists to spot. A reminder that a
failing test is not automatically evidence about the code.

### The backup: SQLite fails open, and Robolectric caught it

`SQLiteOpenHelper`'s default corruption handler **deletes and recreates** a database it cannot open.
So handing Room a truncated archive does not throw — it returns a perfectly valid *empty* database.
My first implementation would have read that as "restore zero rows" and faithfully replaced the
user's ledger with nothing. The test named `a corrupt archive leaves the existing data exactly as it
was` failed on its first run by reporting `Imported` instead of `Failed`, which is the single most
valuable thing that happened today.

The fix is two gates: the SQLite header before Room touches the file, and the manifest's recorded row
counts against what was actually read, before the transaction opens. The second catches damage the
header cannot see.

The design decision worth recording (ADR 0018) is restoring **row by row into the live database**
rather than swapping the file. Swapping means closing and rebuilding the Room singleton that every
collected `Flow` is bound to, which in practice means an app restart. Reading the archive as a second
Room instance — which is also what runs the migrations on an old backup — and copying inside one
`withTransaction` gives all-or-nothing semantics and lets the invalidation tracker update the screens
by itself.

The restore warning is shown **before** the file picker, following `docs/decisions/0013`'s rule for
the bulk mark: nothing about the warning depends on which file is chosen, so showing it first means
no file is read until the user has agreed to what a restore does.

### Prompt/approach note

"Just dump the database as zip" was the right instruction to follow literally. Zipping the SQLite
file gets schema evolution free through Room's own migrations; a JSON dump would have needed a second
serialisation format, versioned and migrated in parallel with the real schema, to end up in the same
place. The temptation to build the "cleaner" thing would have cost real code and bought nothing.

**Verified:** `ktlintCheck detekt test assembleDebug` green, 549 tests.

**Not verified on a device:** the backup has never run against real SAF. Robolectric's
`ContentResolver` handles `file://` URIs, which is what the tests use; a real `content://` from
`CreateDocument` is a different code path in the resolver, though the same one the download pipeline
already uses successfully. The WAL checkpoint is likewise unexercised — Robolectric may not have the
database in WAL mode at all, so that line is reasoned-about rather than proven.

---

## 2026-08-02 (later) — The devcontainer already had the device

**Request:** "To attach a real ADB device, the devcontainer needs to support `linux-tools-virtual`
and `hwdata`. I am able to attach from WSL via adb, now the devcontainer needs to be able, too."

The premise was wrong, and checking took one command:

```
$ ls /dev/bus/usb          -> No such file or directory
$ pgrep -x adb             -> (nothing)
$ adb devices -l           -> 08241FDD40014S device usb:1-1 model:Pixel_5
```

No USB in the container, no adb server in the container, and a Pixel 5 attached anyway. **adb is a
client/server protocol over TCP.** Only the server opens the USB device; every `adb` command is a
client speaking to it on port 5037. `devcontainer.json` already runs with `--network=host`, so WSL's
`127.0.0.1:5037` *is* the container's — the client here has been talking to WSL's server all along.

So `linux-tools-virtual` and `hwdata` are needed in **WSL**, where usbipd-win delivers the device and
the author had already installed them. Putting them in the image would have added a *second*
claimant for one USB interface, and would have needed `--privileged` (usbip writes to sysfs, which is
read-only in containers), a `plugdev` group aligned to WSL's GID, and udev rules — to reach a state
that already worked. Not built, on purpose, and written down in `docs/dev-environment.md` §9.1 so the
same assumption does not get made twice.

### What was actually missing

`scripts/adb-connect-host.sh` — the helper CLAUDE.md §4 asks for by name and which has never
existed. Tier 3 was recorded as "❌ never run"; it turns out to have been working, undocumented.

The script is built around the one thing that genuinely breaks this setup, which is worth recording
because it is counter-intuitive: **an adb client in the container will silently start a server when
none is listening**, that server is blind to USB, and because the network namespace is shared it then
answers for WSL too. Both sides report no devices, which looks like a cable fault. The same trap has
a second door — a client/server version mismatch makes the client *kill the working server* and
start its own USB-blind replacement.

So the script never runs an adb command to test for a server; it probes port 5037 with a raw bash
`/dev/tcp` connect, so it cannot cause the problem it is looking for. And it detects an
already-running container-local server with `pgrep -x adb`, which is exact here precisely because the
container has no USB: any adb server visible in this PID namespace is by definition the broken one.

### Note to self

The instinct on reading the request was to start editing the Dockerfile. The check that made the work
unnecessary cost one command and thirty seconds. Verify the premise before implementing it —
especially when the premise is stated confidently and the implementation is plausible.

**Verified:** all three script branches exercised against the real Pixel 5 — attached (device
listed), no server listening (tested on port 5999, leaving 5037 alone), and container-local server
(started on port 5038, detected, cleaned up).

**Not verified:** the Windows-side `adb -a -P 5037 nodaemon server` variant with `ADB_SERVER_SOCKET`.
Still never run; still marked ❌ in §1.

---

## 2026-08-02 (later still) — The backup feature on a real phone

First real-device session: a Pixel 5 (`redfin`, Android 14 / API 34) over usbip, driving the app
with `adb input` and `screencap`. The target was the one thing the suite structurally cannot reach —
SAF `content://` URIs — which `docs/decisions/0018` listed as the feature's unverified half.

### What held up

- **Export through the real `CreateDocument` picker.** The picker opened on Downloads with the zip
  MIME and the suggested name `podsilo-backup-2026-08-02.zip`; the file landed at 1,705 bytes with
  exactly two entries, and a manifest reading `schemaVersion=4 archiveFormat=1 feeds=0 episodes=0
  ledgerRows=0`. Snackbar: *"Backup saved — 0 podcasts, 0 handled episodes."*
- **The WAL checkpoint is doing something.** The copied `podsilo.db` contains the full schema —
  all five tables plus `room_master_table`. A freshly created Room database writes its schema
  through the WAL, so without `wal_checkpoint(TRUNCATE)` the copy would have been missing it. That
  line was reasoned-about when written; it is now evidenced.
- **Restore with real rows.** Since there is no Nextcloud account on this phone, I built a seeded
  archive instead: took the device's own export, inserted two feeds, three episodes, two ledger rows
  and a `sync_state` with `sqlite3`, fixed the manifest counts, re-zipped and pushed it. Restoring it
  reported *"Restored 2 podcasts and 2 handled episodes"*, and pulling the live database back off the
  device showed every row intact — including `writtenFileName` and `syncedToServer = 0`, the two
  fields that exist nowhere else.
- **The live-refresh claim, which was the interesting one.** ADR 0018 argues for copying rows into
  the live database rather than swapping the file, on the grounds that Room's invalidation tracker
  then updates the screens by itself. Confirmed directly: a second archive carrying three
  `error_log` rows flipped Settings' *Error log* row from **"0 entries" to "3 entries" while it was
  on screen** — no navigation, no restart.
- **The failure path, on real SAF.** A zip containing one text file produced *"That file isn't a
  Podsilo backup."* and the error log still read 3 entries afterwards — the "nothing was changed"
  guarantee, visible rather than asserted.

### What the device found that the tests could not

After restoring onto an install with no Nextcloud account, **S1 still says "No subscriptions —
connect Nextcloud"** while the snackbar says two podcasts were restored. Both are true:
`PodcastListViewModel.contentFor` short-circuits on `!configured` before it looks at the feed list,
and credentials live in DataStore, which the archive deliberately does not carry. So the feature
behaved exactly as designed and the *combination* still reads as a failure.

I spent a while assuming this was the invalidation claim failing, and only settled it by pulling the
database off the device: the rows were all there. Worth noting as a diagnostic habit — "the UI does
not show it" and "it was not written" are different claims, and the second is the one you can check
directly. Filed in `docs/backlog.md` rather than fixed, along with a second on-device annoyance: the
restore picker's `*/*` fallback makes the zip filter a no-op, so it lists every PDF and photo in
Downloads.

### Housekeeping

Test artefacts removed from the phone's Downloads folder, and `pm clear` run to drop the seeded
rows — the install was fresh this session and had never been connected, so nothing real was lost.

**Verified:** export, restore-with-data, live refresh, and the not-a-backup failure path, all on a
physical Pixel 5 through the real SAF pickers.

**Still not verified:** a restore over an install that *is* connected to Nextcloud (needs a login on
the phone), and the download pipeline end to end — no episode has yet been fetched, tagged and
written to a SAF folder by the running app.

---

## 2026-08-02 (evening) — A real account, a real phone, and three bugs no test could see

The first session with a real Nextcloud account, a real download folder, and real episodes on a
Pixel 5. 559 green tests had said the app worked. It did not.

### Bug 1 — feeds could never be refreshed

`docs/UI.md` §4/§5 specify pull-to-refresh on S1 and S2. The events existed. Both view models
handled them. **No `PullToRefreshBox` existed anywhere in the repository**, and the only thing that
emitted `PodcastListEvent.PullToRefresh` was the *Refresh* button inside S1's *no subscriptions*
empty state — which by definition stops rendering the moment there are feeds.

So: with zero subscriptions you could refresh; with subscriptions you could not, from any screen.
S2 had no refresh affordance at all, meaning a feed whose fetch failed could never be retried from
the screen that displays the failure. `requestFeedRefresh` was reachable only from the periodic
worker.

The emulator run never caught it because the emulator never had a subscription — the one path that
worked was the only one it exercised. Four real feeds and it was obvious in ten seconds: every row
read *never refreshed*, permanently.

After the fix: 9,565 episodes across four feeds in 4.6 s, zero errors, titles resolving through CJK
and umlauts. The regression test asserts the *populated* state, deliberately.

### Bug 2 — every download crashed the process

Tapping Download killed the app instantly:

```
foregroundServiceType 0x00000001 is not a subset of
foregroundServiceType attribute 0x00000000 in service element of manifest file
```

`DownloadWorker` calls `setForeground(… FOREGROUND_SERVICE_TYPE_DATA_SYNC)`; WorkManager serves that
through its own `SystemForegroundService`, whose manifest entry declares no type at all; API 34
requires the runtime type to be a subset of the declared one. The `FOREGROUND_SERVICE_DATA_SYNC`
**permission** was already declared and correct — necessary, and not sufficient. The throw happens
inside the system's service dispatch, so the worker cannot catch it, and WorkManager's retry turned
it into a crash loop.

`HANDOVER.md` had flagged the foreground notification as never displayed and `architecture.md` §13
marked it "◐ partly". Both were right, and neither was actionable until something ran.

The fix is four lines of manifest merge. The test reads the **merged manifest** under Robolectric,
which turns a device-only defect into a Tier 1 one.

### Bug 3 — "Last sync: 20647 d ago"

`SyncState.lastEpisodeActionSyncTs` is Unix **seconds**, verbatim from the server — the one value in
the app that is not epoch millis. `SyncStatusAdapter` read it with `EpochTime.ofMillisOrNull`, so a
sync that had just succeeded rendered as 21 January 1970.

This is the exact mistake `docs/decisions/0016` created `EpochTime` to prevent, by giving the two
units differently-named functions. The single call site that needed `ofServerSeconds` used
`ofMillis` anyway. A naming convention is a prompt, not a guarantee; the unit is now pinned by a
test that fails with `1970-01-21T16:01:43.652Z`.

### What worked, first time, on real data

- **Login Flow v2** end to end from the phone — Nextcloud named the app, the app password never
  appeared in the UI, and four subscriptions arrived.
- **Reconciliation.** The badge read 56 where the feed had 57 episodes: one already carried a `PLAY`
  from another client and was correctly `HANDLED_REMOTELY`. The app's central job, against a real
  server. Two probe actions from the previous session — for episodes in no subscribed feed — were
  processed too, exactly as architecture §6 requires.
- **The whole download pipeline**: cache → verify → name → tag → SAF copy. Files landed as
  `Trash Talk... with Count Binface/20260224_BY-ELECTION SPECIAL featuring Hannah Spencer of the
  Green Party.mp3`, and the ID3 tag carried TIT2, TPE1, TALB, TCON=Podcast, TYER, COMM **and APIC** —
  the cover-art feature working through the real pipeline for the first time.
- **The duplicate guard** (ADR 0012): *Download again* left both files byte-identical with unchanged
  mtimes, kept `writtenFileName`, reset `attempts`, re-posted the action, and wrote **nothing** to
  the error log — informational, not a failure, exactly as §12.3 specifies.
- **Backup and restore with 9,565 episodes.** The archive carried 4 feeds / 9,565 episodes / 5 ledger
  rows; restoring it removed a `SKIPPED` row created after the export and brought both
  `writtenFileName`s back intact.
- **The connection survived the restore**, confirming empirically what ADR 0018 claims: credentials
  live in DataStore, not the database.

### The author's new rule

*"No backup should be loaded until the nextcloud login has succeeded."* Implemented on the row and
again in the view model. The reasoning is sequencing rather than secrecy, and it closes the backlog
item from the previous session where a restore onto an unconfigured install left the ledger behind a
*not configured* screen that showed none of it.

### Note to self

Three bugs, and all three were in the seams that no unit test owns: a Composable that never called
an event, a manifest attribute, and a unit conversion at an adapter. The tests covered the logic on
both sides of each seam. **The lesson is not "write more tests" — it is that a green suite says
nothing about whether the pieces are connected**, and the cheapest way to find that out is to run
the thing on the hardware it is for. Every one of these was visible within two minutes of real use.

**Verified:** `ktlintCheck detekt test` green, 561 tests, plus everything above on a physical
Pixel 5 against a real Nextcloud.

**Not verified:** that a *server-side* action created after a backup returns on the next sync
(ADR 0018's reassurance line). My test `PLAY` was still in the outbox when I restored, so the restore
correctly discarded it and there was nothing on the server to come back — the mechanism is untested,
not disproved. Also untested on device: the restore row's **disabled** state, which would need the
account disconnected and re-approved.

---

## 2026-08-03 — The artwork that was never drawn

**Reported:** "the podcast list is missing the podcast images, the episode list is missing the
episode picture."

Same shape as yesterday's missing pull-to-refresh, and that is the part worth recording. Coil was
proposed, argued for, approved as a dependency, given its own ADR (0015), and added to
`gradle/libs.versions.toml` — and **referenced by no module's `build.gradle.kts` at all.** There was
not one `AsyncImage` in the repository.

Everything around the hole was in place, which is why nobody noticed:

- `PodcastRow` reserved `heightIn(min = ArtworkSize + RowPadding)` — space for an image.
- `FeedUi.artworkUrl` and `EpisodeUi.artworkUrl` were plumbed from the DAO through the view models.
- `:core:feed` populated `Feed.imageUrl` and `Episode.imageUrl` from `<itunes:image>`.
- `docs/UI.md` §18 specified the monogram fallback in detail.

All four correct, and the pixels blank. On the author's account: 4 of 4 feeds and 9,558 of 9,565
episodes had image URLs sitting in the database, being rendered by nothing.

### A second bug was hiding behind the first

`EpisodeListItem.toUi` set `artworkUrl = feedArtworkUrl` unconditionally, ignoring
`Episode.imageUrl`. `docs/UI.md` §5 asks for "episode image if the feed supplies one, else the
feed's". It could not possibly have been noticed before, because nothing drew either one.

### And a third, found by looking at the screen

With three feeds showing their covers, `heute journal (VIDEO)` showed **neither an image nor a
monogram** — a blank square. Its cover is advertised over plain `http://`, which Android blocks as
cleartext, and my first implementation passed `error = null` to `AsyncImage`, so a failed load drew
nothing. The comment two lines above it claimed the monogram was "the fallback if it fails". The
comment was aspirational; the code was not.

Fixed by drawing the monogram *underneath* and the image on top, which gets the fallback for free,
costs nothing when the image loads, and avoids `SubcomposeAsyncImage` — worth avoiding in a list that
is 9,490 rows long in this author's own subscriptions. Cleartext enclosures are noted in
`docs/backlog.md`; none of the 9,565 episodes currently use one.

### The pattern, now three for three

Pull-to-refresh, the foreground-service type, and now artwork: **every bug this week has been a
connection that was never made, between two pieces that were each correct and each tested.** The
test suites covered both sides of every seam. A dependency in the catalog, an event with a handler,
a permission in the manifest — all present, none wired.

What would have caught them is not more unit tests. It is asking, once per feature, *"what draws
this?"* and following the answer to a call site. That question takes seconds and I did not ask it
three times.

**Verified:** `ktlintCheck detekt test` green, 571 tests, and the covers now render on the device.

---

## 2026-08-03 (later) — A device test set, and what building it taught

**Asked for:** a test set that never runs on CI, only against the real device; UI conformance to
`docs/UI.md` and `docs/UI_interface.md`, updating those if they are outdated; anything else only a
device can test; and the known Android-vs-JVM deviations.

### The docs were not outdated

I diffed every documented state class against the built one before writing a line of test code.
`PodcastListUiState`, `EpisodeUi`, `SettingsUiState` and the event hierarchies all match, including
`EpisodeUi.actions` being computed in an `init` rather than passed in, which the document already
called out. So the right move was not to rewrite the documents but to make the tests **enforce**
them: each conformance test names the clause it checks, and a failure means either the screen
drifted or the document did.

### The isolation is structural, not a tag

CI runs `ktlintCheck`, `detekt`, `test`, `assembleDebug`. A test in `src/test/` runs there; a test in
`src/androidTest/` cannot. No annotation, no filter, nothing to remember. `ci.yml` now carries a
comment saying why `connectedAndroidTest` must never be added: a hosted runner has no device, so the
job could only skip, fail, or boot an emulator — and *an emulator agreeing with Robolectric is
exactly how three of this week's bugs got through*.

### Two things the build taught me while I was writing it

**Dex rejects punctuation in method names.** Backticked sentence names — used freely in every
`src/test/` class here — fail the *build* under R8: `Method name '…, not a theoretical concern'
cannot be represented in dex format`. Commas did it; apostrophes are the other offender. The
existing instrumented tests already used camelCase for exactly this reason **and nowhere said so**,
so I rediscovered it at the cost of two builds. Now written down in the deviation test itself.

**Running the set wipes the app.** `connectedAndroidTest` reinstalls, and an install that cannot
replace the existing package uninstalls it first. I hit `DELETE_FAILED_INTERNAL_ERROR`, cleared it
with `adb uninstall`, and thereby destroyed the author's Nextcloud login, SAF grant and
9,565-episode database on their own phone. The downloaded files survived, being outside app storage.

That is a genuinely useful thing to have learned and a bad way to learn it. I should have exported a
backup first — the feature for doing so exists, I built it two days ago, and I did not think to use
it. The ⚠ now at the top of `scripts/device-test.sh` and in `docs/dev-environment.md` §6 is the
warning I should have written before running the thing rather than after.

### One test was wrong, and it was mine

`everyMigrationAppliesOnTheDevice` asserted `syncStateDao().get()` was non-null on a fresh database.
It is null until a sync pass writes a cursor — a fresh install legitimately has none. The assertion
was a guess about the schema rather than a statement about it, and the device said so. Corrected to
assert what the test actually means: every table is queryable after the migration chain runs.

10 of 11 passed first time; that one was the exception.

**Verified:** 11/11 in `:app`'s device package on a Pixel 5 (Android 14).

### Postscript: I diagnosed the install failure wrong twice

`:app`'s device tests failed with `Failed to install APK … ErrorCode: 2002` over a report reading
`tests="0" failures="0"`, which Gradle summarises as *"There were failing tests"* though none ran.

I guessed twice before testing anything. First a stale test package, because a `DELETE_FAILED`
appeared alongside it — wrong; clearing every package changed nothing. Then AGP's install timeout,
because the APK is ~58 MB on a slow usbip link and the run died at about the time a manual install
takes — also wrong; `installation { timeOutInMs }` at 30 minutes made no difference. I wrote the
first guess into the troubleshooting notes before disproving it, which is the worse of the two
mistakes.

Only then did I test alternatives one at a time: `adb install -r -g` of both APKs succeeds every
time, so it is not the flags; `useUnifiedTestPlatform=false` is deprecated and ignored in AGP 9, so
UTP cannot be sidestepped. What is left is **UTP's own installer failing on the large app APK over
usbip**.

The discriminating fact was in the very first run and I walked past it twice: **the library modules
passed and `:app` did not.** Library modules install only their own small test APK; `:app` installs
the 58 MB one. That single contrast rules out packages, flags and timeouts at once, and it was on
screen before I formed either hypothesis.

`scripts/device-test.sh` now runs the library modules through Gradle and `:app` through
`adb install` + `am instrument` — the same runner Gradle would have used. The four ruled-out
hypotheses are recorded in the script so the next person does not repeat them, and both failed fixes
were reverted rather than left in place.

**Verified:** 41 instrumented tests green end to end via the script on a Pixel 5 — 6 + 8 + 6 through
Gradle and 21 in `:app`.

---

## 2026-08-03 (later) — An audit, and a claim of mine that did not survive it

**Asked:** is all the code and every test still used and relevant; can anything be deleted,
simplified or joined.

### The most important finding is that I over-claimed yesterday

`SafDownloadTargetInstrumentedTest` opts out with `assumeTrue` when no SAF folder has been granted.
The device test set **uninstalls the app**, which removes the grant. So the six tests covering the
one component ADR 0011 calls out as untestable-by-any-other-means are the six most likely to skip —
and `am instrument` prints **`OK (6 tests)`** for six tests that all threw
`AssumptionViolatedException` in 0.135 s between them.

My script grepped for `FAILURES!!!`, saw none, and reported green. "41 instrumented tests green end
to end" was wrong: **35 ran, 6 skipped.**

I had flagged this exact hazard in an earlier session — *"a silent `assumeTrue` skip looks like a
pass"* — written it in the journal, and then built a test runner that fell for it. Knowing a trap
exists is not the same as checking whether your own tool walks into it.

Fixed: the run is parsed with `-r` (raw), skips are counted from `AssumptionViolatedException`, and a
run with any skip exits non-zero as `INCOMPLETE` rather than green.

### What the audit actually found in the code

Very little, which is worth recording as a result rather than a non-event:

- **Unused imports and unused private members: none.** Guaranteed by the passing build — ktlint's
  `no-unused-imports` and detekt's `UnusedPrivateMember` already cover them, so hand-searching would
  have been theatre.
- **Every port method has a production caller.** Scanned all of `:core:model/port`.
- **No unused top-level types.** The eleven the scan flagged were Hilt modules, manifest-referenced
  classes, and types used inside their own file.
- **Two genuinely dead enum values:** `ErrorCause.FEED_PARSE` and `ErrorCause.TAG_WRITE`, neither
  ever produced, both unreachable *by design*. Filed rather than deleted — `ErrorCause` is persisted.

### My scan was wrong once, and the correction matters

I first reported `EpisodeUiAction.CHOOSE_FOLDER` and `BlockedReason.SYNC_IN_FLIGHT` as dead code.
They are not code at all: they exist only in `docs/UI_interface.md`. I had fed the scan enum member
names taken from the *documentation* and then reported the misses as unused declarations — a method
error that manufactures findings rather than discovering them. Checking the code confirmed the enums
never had them.

That turned four "dead code" findings into two, plus four **doc-vs-code gaps**, which are now marked
in `UI_interface.md` rather than left to read as implemented: `UiEffect` (never built — each screen
has its own effect type), `showsSelectionAffordance` (never built — the documented
accessibility affordance does not exist), and the two phantom enum members.

### On joining tests

`SanitizationTest` is the most fragmented in the repo — 18 tests, 20 assertions — and should stay
that way. Each name states a distinct rule from CLAUDE.md §6 (*trailing dots and spaces are
stripped*, *leading dots are preserved*, *umlauts survive by default*). Collapsing them into a table
would trade eighteen self-documenting requirements for one name and a data block, in the area the
brief says to get exactly right. Fragmentation is not automatically duplication.

One assertion was genuinely worthless and was mine, written yesterday:
`assertEquals(8, "00000000".length)` — a tautology about a string literal, dressed as a check on the
missing-date fallback. Deleted.

**Verified:** `ktlintCheck detekt test` green, 571 tests; device set re-measured honestly at 21 in
`:app` with 6 skipped.

---

## 2026-08-03 (later still) — Swiping, and a bug my own test nearly waved through

**Reported:** "Swiping has not been implemented for episodes."

Correct, and it is the **third** affordance in this project specified, wired at both ends, and left
with no gesture in the middle — after pull-to-refresh and the artwork slot. `SwipeCommitted` was
declared, handled by `EpisodeListViewModel`, covered by view-model tests, backed by a persisted
`SwipeMapping` and two working dropdowns in S4. Nothing emitted it.

### This time I looked for the whole class, not the instance

Rather than fix swipe and wait to be told about the next one, I scanned every `*Event` sealed
interface for members that are declared and handled but emitted by no UI. My first scan was useless —
it attributed every `data object` in a file to the first sealed interface in it and returned 76
false positives. Scoped properly to each interface's own body, it returns five, and they are two
features:

- `SwipeCommitted` — the swipe gesture (now built).
- `SelectionStarted`, `SelectionCleared`, `SelectAllInFilter`, `BulkConfirmed` — **selection mode**,
  `docs/UI.md` §5's long-press batch triage. The `Selection` state type, `inSelectionMode`, the
  per-row `selected` parameter and the bulk write path all exist. There is no long-press.

Reported rather than built: the ask was swipe, and selection mode is a feature, not a fix.

### The bug that matters

The obvious `SwipeToDismissBox` implementation does the work in `confirmValueChange` and returns
`false` so the row springs back instead of leaving a hole. That callback is **a predicate consulted
repeatedly while the drag settles, not a commit hook**, and vetoing the change keeps it being
re-asked: one swipe fired `SwipeCommitted` **fourteen times**. Fourteen ledger writes and fourteen
posted episode actions, in an app whose triage decisions have no undo.

Two of my three first tests asserted `events.contains(SwipeCommitted(...))`. Both passed. The bug
was caught only by the third, which happened to compare the exact list — and I had written the loose
form first, twice, because "it committed" felt like the property under test. It is not; **"it
committed once" is.** Those two assertions are now exact, with a comment saying why.

The fix reacts to the settled `currentValue` and calls `reset()`, which fires once and returns the
row to place.

**Verified:** `ktlintCheck detekt test` green, 577 tests.

### Same session, five more findings from the author

Reported while the swipe work was still open, and all five were real:

1. **Tapping an episode in S7 opened its podcast, not the episode.** `RowClicked` navigated to S2
   with the feed URL, leaving the user to find the row again — which reads as being bounced back to
   the podcast. A row in Activity names one episode; it now opens that episode.
2. **The About group should link to the source.** GPL-3.0 says little without somewhere to get the
   code, so the licence line and the link now sit together.
3. **Pulling down in the detail view gave a white screen.** The best find of the five. S3 was a
   `ModalBottomSheet` rendered *inside a full-screen navigation destination* — the destination owned
   the window and held nothing, so the sheet floated over an empty page and a downward drag revealed
   it. Nothing had navigated, so `Dismissed` never popped the backstack either. It is now a real
   screen, which is what it always was: the sheet was `skipPartiallyExpanded`, i.e. permanently full
   height. `docs/UI.md` §6 amended, since the doc specified the sheet.
4. **Mark all as played on the Downloaded filter.** Behind a confirmation naming the count, saying
   the files stay and that the state reaches Nextcloud.
5. **Clear all on the delivered list.** The one that needed care: that list is projected straight
   from `DOWNLOADED` ledger rows, and those rows are what stop an episode being downloaded a second
   time (CLAUDE.md §11). A literal clear would have re-downloaded the user's entire history. It is a
   persisted *display cursor* instead — the rows stay, the list hides anything older — and the button
   says "Clear list" rather than "Clear", because the word has to promise only what it does.


---

## 2026-08-03 (evening) — Episode size, and a perl substitution that ate a field

Author approved the schema change, so `Episode.sizeBytes` is in: `<enclosure length>` from
rssparser's `RawEnclosure.length`, schema **v5**, additive and nullable and unbackfilled — the same
shape as `imageUrl` in v4, because `episodes` is a disposable cache the next refresh rebuilds.

Three judgement calls worth recording, all about *rendering a number for a decision rather than for
accounting*:

- **Zero is dropped at parse time.** Feeds write `length="0"` when they mean "no idea", and a row
  reading "0 MB" is worse than a row with no size.
- **MB throughout, never GB.** A list where most rows say "48 MB" and one says "1.2 GB" makes the
  outlier harder to compare at a glance, and comparing is the only job this number has.
- **Whole megabytes.** The source is a publisher's claim; decimals imply a precision it does not
  have. Same reason durations render in whole minutes.

### The mistake

My `perl -0pi` to add the field matched `val imageUrl: String? = null,\n)` and replaced it — so
`sizeBytes` did not get added *after* `imageUrl`, it got added *instead of* it. In both `Episode` and
`EpisodeEntity`. The compiler caught it immediately ("No parameter with name 'imageUrl' found"), so
the cost was two minutes rather than a lost column, but it is the second time this session a
regex-based edit has silently deleted the line it was supposed to anchor to — the earlier one
duplicated a table row in `dev-environment.md` three times over.

The pattern is the same each time: an anchor that includes the thing being kept, with a replacement
that forgets to reproduce it. Editing structured code by regex is fine for a one-line insertion and
a poor idea for anything that has to preserve its surroundings.

**Verified:** `ktlintCheck detekt test` green, 589 tests.

### Verified on the Pixel 5

All six changes driven on the device, with the author's real account and download folder:

| | Result |
|---|---|
| Episode size | `Jul 31, 2026 · 55 min · 69 MB` — 9,568 episodes carrying a size |
| Swipe | ledger went 11 → **12** rows for one swipe. The 14× bug is genuinely fixed on hardware |
| Detail screen | full screen with a back arrow; **the pull-down did nothing** |
| S7 row tap | opened *that episode*, not its podcast |
| Clear list | the section vanished and **all 4 `DOWNLOADED` ledger rows survived** — the display cursor holds |
| About | *Source code · https://github.com/drehtuer/podsilo* |
| Mark all | dialog named the count, said files stay and that state reaches Nextcloud; cancelled, ledger unchanged |

### The v5 migration needed a second statement, and the device found it

`sizeBytes` shipped, the migration applied, and **every row stayed null**. The cause is not the
parser — `<enclosure length="36678425">` is right there in the author's feed and the unit test parses
it correctly. It is `FeedFetcher`'s conditional GET: an unchanged feed answers **304**, the parse is
skipped entirely (`docs/architecture.md` §7), and a newly added column therefore stays empty until
the publisher next happens to post.

So `MIGRATION_4_5` now also runs `UPDATE feeds SET httpEtag = NULL, httpLastModified = NULL`. One
full re-fetch per feed, once. **Any migration that adds a column to `episodes` needs that line** —
otherwise the column fills in on the publisher's schedule rather than ours.

Confirmed by reproducing the same state on the device (clearing the validators by hand, since v5 had
already run there) and refreshing: 0 → **9,568** episodes with a size.

Worth noting how close this came to shipping unnoticed: the unit tests were green, the migration test
was green, the schema was v5, and the feature was invisible. Only running it against feeds that had
already been fetched showed it.

---

## 2026-08-03 — v0.1.0, the first release

Tagged `v0.1.0` at `9f2a337` and published it. CI's `release: published` trigger rebuilds and
attaches the debug APK, which is the point of publishing rather than drafting: workflow artifacts
expire after 7 days, release assets don't.

Two judgement calls worth recording, since both could reasonably have gone the other way:

**Not marked as a pre-release.** `0.1.0` already says early, and GitHub's pre-release flag suppresses
the *Latest release* badge on the repo page — a first release that the landing page doesn't show is
the worse outcome. The debug-signing caveat went at the top of the notes instead, where it is
actually read, rather than encoded in a flag.

**The README status block was stale and had to go first.** It claimed 502 tests and — worse —
"Nothing has yet been tested against a real Nextcloud", which stopped being true several sessions
ago. That block is the first thing a visitor reads, and a public release is the moment it stops being
an internal note. A landing page can be out of date privately; it can't be wrong publicly.

One rough edge left deliberately: the attached asset is named `app-debug.apk`, because that is the
path CI uploads. `podsilo-0.1.0.apk` would be better and is a two-line change to the workflow, but it
only takes effect on the *next* release, so it was not worth blocking this one on.

Nothing was built this session. The release is a packaging act, and the code it packages was verified
on hardware in the previous one.

---

## 2026-08-03 — "the login always sets the username podsilo"

The report was that the app assumes a username. It doesn't, and finding that out took three probes
that are worth recording because each one ruled out a different suspect.

1. **`grep '"podsilo"'` across the sources** — one hit, `settings.gradle.kts`'s project name. The
   stored username is `LoginPollDto.loginName`, straight from the server.
2. **`setNextcloudCredentials`** writes URL, username and password together and removes them
   together, so no stale value can outlive a reconnect either.
3. **The server, over curl with no cookies.** The flow URL redirects to
   `/login/v2/flow?user=&direct=0` — the `user` parameter comes back **empty**. The app sends no name.

Then the actual cause, on the device: Firefox held a Nextcloud session, and tapping *Log in* on the
flow page did not show a login form. It went straight to *"Account access — Currently logged in as
podsilo (podsilo)"* with one **Grant access** button. **Login Flow v2 has no account chooser.** The
account is whichever one the browser was signed into, and no query parameter overrides that.

So the bug was real but sat one layer up from where it was reported: the app was silently persisting
an account it had never shown the user. Connecting the wrong one is not cosmetic — every triage
decision afterwards writes `DOWNLOAD` and `PLAY` into *that* account's log, and the author has both a
`podsilo` test account and a personal account on this server with a standing rule that the personal
one must never have episodes marked played.

The fix is `Phase.ConfirmingAccount`: the flow now names the account and stops. ADR 0019 has the
reasoning, including why the credentials live in a private view-model field rather than in
`ConnectUiState` (a data class whose `toString` logs and inspectors print, carrying the app password)
and why *Use a different account* opens the **server root** rather than retrying — retrying against a
live session returns the same account forever.

### The device leg is incomplete, and the reason is that it should be

Granting on the phone hit Nextcloud's re-authentication step: *"This action needs authentication,
please confirm it by entering your password."* That is the account password, which the agent has no
business typing and does not have. So the new confirmation dialog is **verified by tests but not yet
seen on hardware** — the two Compose tests cover its content and both buttons, and the four view
model tests cover the store/reject/re-confirm paths, but nobody has watched it appear after a real
grant.

Left the phone clean: dialog cancelled, still connected as `podsilo`, nothing written.

Two smaller things the device run turned up:

- **Firefox is the default browser here, not Chrome.** An earlier manual probe had left a granted
  flow page in Chrome, and a stale tab swallowed the first grant attempt — ten minutes of "waiting
  for authorization" that looked like an app bug and wasn't. Worth checking which browser actually
  receives the `ACTION_VIEW` before diagnosing a stuck poll.
- The rejected app password stays live on the server. Filed in `docs/backlog.md` rather than fixed,
  with the reasoning: it is a new endpoint added to the one code path whose job is to store nothing.

---

## 2026-08-04 — a timeout wearing an unreachable's clothes, and a real release build

### "It says it cannot reach the URL"

It could reach it. `POST /index.php/login/v2` answered 200 in about a second from the container, and
the flow ran end to end on the phone minutes later. What the report caught is a *reporting* bug:

```kotlin
} catch (io: IOException) {
    // DNS failure, connection refused, timeout — all "can't reach that address" to the user.
```

A `SocketTimeoutException` is an `IOException`, so a slow server produced *"Can't reach that address.
Check the spelling and your network"* — advice to fix a host name that was never wrong. And slowness
here is not exotic: **Nextcloud's bruteforce protection deliberately delays repeated authorization
attempts from one address**, which is exactly the state the previous session's testing left the
server in. Against OkHttp's default 10 s read timeout, "I tried to log in a few times" becomes "your
address is wrong".

Two changes: `LoginFlowFailure.TIMED_OUT` as its own case with its own sentence, and deliberate
timeouts on the shared `OkHttpClient` (20 s connect, 30 s read/write) instead of the library
defaults, which were never chosen — they were simply never set. No `callTimeout`, because that would
bound whole calls including bodies, and this client is shared with the enclosure downloader.

The lesson is not about timeouts. It is that **a catch block that merges two causes has decided the
user will see one message**, and the comment above it said so in plain words the whole time.

### The grant wall is Nextcloud's, and stays there

Completing a grant on the phone hits *"This action needs authentication, please confirm it by
entering your password"* — Nextcloud requiring password confirmation before issuing an app password.
That is the account password, so the device leg of ADR 0019's confirmation dialog is still unverified;
it needs the author at the keyboard. Reproduced twice, so it is the flow's design and not a glitch.

### A release build that is actually a release build

`assembleRelease` produced an unminified, unsigned APK that differed from the debug one only in name.
Now: R8 with `isMinifyEnabled` and `isShrinkResources`, which takes **58 MB to 4.8 MB**.

The keep rules are the interesting part, because R8 breaks things the JVM tests can never catch —
they run before minification. `kotlinx.serialization` finds generated serializers reflectively via
`Companion.serializer()`, and jaudiotagger picks tag writers by name out of a registry. Both would
fail at runtime, one when the app tries to log in and the other when a download finishes. Checked
`usage.txt` rather than assuming: for `LoginPollDto`, R8 removed only `component1..3`, `copy` and the
synthetic annotation getters, keeping the class, its `Companion` and its `$$serializer`.

**Still unverified: that the minified APK runs.** Installing it means uninstalling the debug build —
different signing key — which erases the ledger, the login and the folder grant. Not a call to make
unprompted.

Also: APKs are now `podsilo-<version>.apk` and `podsilo-<version>-debug.apk` via
`androidComponents.onVariants`. This needs `VariantOutputImpl`, an AGP internal, because the public
`Variant.outputs` exposes version fields but not the file name and the old `applicationVariants` DSL
is gone. Noted at the call site so a future AGP that promotes it can drop the cast.

### Build identity

`versionCode` is now `git rev-list --count HEAD` — monotonic, needs no state outside the repo, and
identical on CI and laptop for the same commit, which a run number would not be. Shallow clones fall
back to 1, hence `fetch-depth: 0` in CI.

About now shows **Build 93 · 2026-08-04 00:17 UTC · 6b988a0**. Verified on the device. `versionName`
could never answer "is this the build I just installed?" — `0.1.0` stays `0.1.0` all day.

Small thing that cost two screenshots: the phone was left in landscape from the previous session, so
scripted taps by coordinate all landed in the wrong places. `settings put system user_rotation 0`
before driving the UI.

---

## 2026-08-04 (later) — signing reality, a saveable that saved too much, and a distribution audit

### Three signing bugs, each only findable by signing something

CI #79 failed with `Cannot convert '' to File`. An **unset GitHub secret arrives as an empty string**,
not an absent variable, so `System.getenv` returned `""` and `file("")` aborted the build — meaning
every *unsigned* run failed, which is the exact case the fallback existed to handle. `isNotBlank`,
not `!= null`.

Then the author's real keystore: **EdDSA**. Android's APK signing supports RSA, DSA and EC and
nothing else, so `packageRelease` died with `InvalidKeyException: Unsupported key algorithm`. Proved
it was only the algorithm by building against a throwaway RSA key, which signed fine.

Then the one that would have been silent: the release job gated on `META-INF/*.RSA`, and **a
correctly signed APK does not contain that file**. It is produced by v1 JAR signing, which is off at
`minSdk 33`. The check would have rejected exactly the artefacts it existed to pass. Now it asks
`apksigner verify`, and the signing schemes are declared rather than inherited.

The pattern across all three: *none* of them is visible until an artefact is actually produced and
inspected. Reading the config would not have found any of them.

### `rememberSaveable` remembered a half-finished gesture

The reported swipe bug — a row coming back from a filter switch still pushed aside, with the panel
flashing — is `rememberSwipeToDismissBoxState` being `rememberSaveable`. The drag offset was written
to saved state, and a filter switch detaches and reattaches these rows.

The half that was not reported is worse: `LaunchedEffect(state.currentValue)` keys on the restored
value, so a row restored mid-swipe **fires `SwipeCommitted` again** — a second `PLAY` or `DOWNLOAD`
for one gesture, silently, in an app whose triage has no undo. The visible symptom was the harmless
one.

**The test does not reproduce it, and that is written into the test.** Robolectric's Compose runtime
does not restore saveable state through a `LazyColumn` detach — the new test passes against the
broken code with the clock auto-advancing *and* driven frame by frame. Rather than dress it up as a
regression test, its KDoc says what it does and does not prove, and the fix was verified on the phone:
a partial swipe plus two tab switches leaves every row centred, and a committed swipe reports
"Marked 1 episode as played" — one, not two.

Worth remembering: **a test that passes before and after a fix is evidence of nothing**, and checking
which way round it fails costs one revert.

### Distribution audit

Asked whether the release follows Android's publish guidance and F-Droid's. Mostly yes, and the
findings are in `docs/backlog.md`. The one that surprised: the app has **no icon** —
`android:icon="@android:mipmap/sym_def_app_icon"`, the system default, with no `mipmap-*` resource in
the tree at all. Eight screens designed in detail and nothing to tap on the launcher.

The substantive F-Droid blocker is that jaudiotagger comes from **JitPack**, which their buildserver
treats as a third-party prebuilt. That traces straight back to ADR 0006 and is only worth reopening
if F-Droid is actually a goal.

Also added: the R8 mapping file is now kept with the APK it belongs to. It is regenerated every
build, so a mapping not stored alongside its APK is gone, and a minified stack trace without it is
unreadable.

---

## 2026-08-04 (later still) — the app gets a face

Signing works end to end now: the author regenerated the keystore with RSA, and `assembleRelease`
produces an APK that `apksigner verify` accepts under **v3**. The key has no password of its own, so
the `PODSILO_KEY_PASSWORD` secret could not be created — GitHub rejects empty secrets — and the
fallback added yesterday covers exactly that: blank means absent, absent means "the key shares the
store password". Local and CI behave identically for the same reason.

### The icon

The audit's most embarrassing finding, fixed: `android:icon` pointed at
`@android:mipmap/sym_def_app_icon`, the stock Android silhouette, with no `mipmap-*` resource in the
tree at all. Eight screens designed in detail and nothing to tap on the launcher.

Vector-only adaptive icon — no PNGs, no density buckets, nothing to keep in step by hand. `minSdk 33`
makes that safe: adaptive icons landed in API 26, so every device that can install this app renders
them. Three layers: the accent `#EC3013` as background so the launcher and the screens are visibly
the same app, the mark as foreground, and the same mark again as `<monochrome>` for Android 13 themed
icons.

The mark is the app's own sentence rather than a genre cliché: a **silo**, an **episode dropping into
it**, and the **pool collecting at the bottom**. No microphone — this app deliberately does not play
audio — and no RSS wave, which every feed reader already wears.

The constraint that shaped it: an adaptive icon is a 108×108 canvas of which launchers may mask
anything outside the **central 66dp circle**, and they crop to circles, squircles and rounded squares
depending on the device. Every point is inside it; the widest is the silo's bottom corner at (72,80),
31.6 from centre. Drawn to the edges the outline itself would be the first thing lost.

Verified on the launcher, and in the release build — where the check that mattered was not the one I
first ran. `unzip | grep ic_launcher` returns **zero**, because resource shrinking renames resources:
the icon is `res/BW.xml`, and `aapt2 dump badging` reporting `icon='res/BW.xml'` is what actually
proves it resolves. Second time this week a plausible-looking grep would have reported a false
failure about a release APK, after the `META-INF/*.RSA` signature check. **Minified artefacts do not
answer questions phrased in terms of source names.**

Still missing for store listings: a 512×512 raster. Play and F-Droid both want one and neither reads
it from the APK, and this container has no image tooling at all — no ImageMagick, no rsvg, not even
python3. Filed with the Fastlane metadata it belongs beside.

---

## 2026-08-04 — v0.2.0, and the release pipeline proving itself before the tag

Tagged and published `v0.2.0`: a signed, R8-minified **4.6 MB** APK, the 39 MB debug build beside it,
and the gzipped R8 mapping. v0.1.0 could only ship a debug APK, so this is the first release that is
one.

### The keystore secret was fine; `base64 -d` was fussy

The main build went red on `Decode release keystore` with `base64: invalid input` — which reads as
"your signing key is broken" and was nothing of the sort. Tested all three shapes against the real
keystore:

| secret form | old code | new code |
|---|---|---|
| `base64 -w0` (flat) | ok | ok |
| `base64` (76-column wrapped) | ok | ok |
| **the same bytes with CRLF** | **`invalid input`** | ok |

The author develops on Windows. Stripping whitespace before decoding fixes all three, and the fix
proved itself on the real secret immediately: `Decoded keystore: 4298 bytes`, matching the local file
exactly.

Two judgement calls in that step worth keeping:

- **A bad secret warns rather than failing the build.** The release job already refuses to attach an
  unsigned APK, so a malformed secret costs the release asset and nothing else. Failing every CI run
  on the repository because a signing secret is malformed punishes the wrong changes.
- **It logs the decoded byte count.** Enough to tell "decoded to something keystore-shaped" from
  "decoded to nothing", with no part of a signing key in a public log.

### Verify the artefact you published, not the one you built

The habit that keeps paying: after CI attached the assets, the release APK was **downloaded back from
GitHub** and checked — `Verifies`, v3 scheme, `CN=Podsilo, O=drehtuer, C=DE`, `versionCode=108`,
`icon='res/BW.xml'`. Building a signed APK locally proves the config; downloading the published one
proves the pipeline.

This week has produced three separate cases where the *obvious* check on a release artefact was
wrong: `META-INF/*.RSA` for the signature (v1 is off, so a correctly signed APK has none),
`grep ic_launcher` for the icon (resource shrinking renamed it to `res/BW.xml`), and `base64 -d` for
the keystore (valid content, hostile whitespace). **A minified, signed, shrunk artefact does not
answer questions phrased in terms of its sources.** Ask the tool that owns the format — `apksigner`,
`aapt2` — rather than `grep`.

### Release notes carried an upgrade warning, deliberately

v0.1.0's asset was debug-signed and v0.2.0's is release-signed, so Android cannot upgrade one to the
other in place: the install has to uninstall first, taking the ledger, the login and the folder grant
with it. That is exactly the loss CLAUDE.md §11 calls the most important thing to protect, so it is
in the notes as a warning with the backup step, not a footnote. Release-to-release upgrades from here
are in place.

---

## 2026-08-04 — "can't reach that address" against a server that was perfectly reachable

Reported against a *different* Nextcloud: the browser completes the grant and says so, and the app
then reports the address as unreachable. Not a timeout — the login was quick.

`start()` had clearly worked, because the browser opened. Everything after it uses URLs **the server
supplies**: the poll endpoint and the `server` field. Nextcloud derives both from `overwriteprotocol`
/ `overwrite.cli.url`, and behind a TLS-terminating reverse proxy those are very commonly left as
`http`. The app has no cleartext permission and none should be added, so Android refuses the
connection with `UnknownServiceException` — an `IOException`, which the client mapped to
`UNREACHABLE`, which reads "check the spelling and your network". The address was never the problem
and was never even the URL that failed.

Three changes, in increasing order of how much they matter:

1. **`CLEARTEXT_BLOCKED` is its own failure**, with a message naming `overwriteprotocol` — the fix is
   on the server, and no amount of retyping the address reaches it.
2. **Server-supplied URLs are upgraded to `https`**, never downgraded, when the flow started over
   `https`. The author's rule for this app is that the conversation is encrypted by default, and
   *following* the server's scheme verbatim is the one option that violates it permanently rather
   than once: `server` is persisted, so a single misconfigured field would mean the app password in
   cleartext on every later sync. Upgrading is safe in both directions — if the host truly has no TLS
   listener the request fails loudly, which is the correct outcome.
3. **The connect flow finally writes to the error log.** `docs/UI.md` §8 has claimed since the design
   pass that these errors are "each also written to S8". They never were. The dialog has room for one
   sentence, which is right for a dialog and useless for diagnosis — "can't reach that address" is
   the same six words for a DNS failure, an unroutable host, and a refused cleartext URL. The
   underlying message, which names the host and the actual refusal, now lands in S8 where it can be
   read and shared.

That third one is the real lesson. The bug was findable in minutes *because* the exception message
existed; it just had nowhere to go. A design document asserting that errors are logged is not the
same as errors being logged, and nothing failed when they were not.

One test removed rather than kept: an attempt to assert the upgrade *through* `poll` passed whichever
way the code behaved, because MockWebServer serves plain http and the branch is unreachable from this
source set. The rule is tested directly instead, and the file says why. Second time this week a test
that could not fail nearly got committed.

---

## 2026-08-04 — the release-vs-debug bug that was neither

Closing out the "release APK can't connect" report. It does not reproduce. Five runs on the Pixel 5,
each with the minified release installed under a throwaway `applicationIdSuffix` so the author's own
install was never touched:

| build | account | result |
|---|---|---|
| v0.2.0 release | podsilo | reached the confirmation dialog |
| #40 release | podsilo | reached the confirmation dialog |
| **main release** | **drehtuer** | **connected, subscriptions pulled, error log empty** |
| main debug | podsilo | connected, syncing |

Reaching `ConfirmingAccount` is the proof that matters: it is only entered after `poll()` returns
credentials *and* `verifyGpodderSync()` gets a 200. So under R8 the TLS stack, kotlinx.serialization,
the poll loop and the authenticated request all work — including on plain v0.2.0, the exact build
that was reported broken.

What was actually wrong: the app had **no Nextcloud credentials at all**, while the SAF folder grant
and the naming settings were intact. Only one code path clears them (`SettingsViewModel.disconnect`,
reached solely by tapping *Disconnect*), so the connection had been dropped by hand at some point.
An app in that state shows the empty "Connect Nextcloud" state, and every reconnect is a fresh flow
against a server that by then had been hammered — which v0.2.0 reports as "Can't reach that address"
whether the cause is DNS, an unroutable host, or Nextcloud's rate limiting. #40 is what splits those
apart.

### Three ways I made this harder than it was

1. **I confounded the experiment myself.** I installed the #40 *debug* build on the phone while the
   author was testing a v0.2.0 *release* APK, then spent a round reasoning about "debug vs release"
   — a comparison I had personally invalidated.
2. **I read `usage.txt` as evidence of breakage.** `PlatformRegistry` and `AndroidPlatform.trustManager`
   appear there as "removed", which looks alarming and means nothing on its own: R8 lists *inlined*
   classes the same way. A static listing should never have outranked a running build.
3. **I did not check the obvious state first.** "Is it still connected?" would have found this in one
   screenshot, before any APK was built.

The rule worth keeping: **when a bug is reported as A-vs-B, verify that A and B are the only
difference before reasoning about A and B.**

### Handling the personal account

The last run required the `drehtuer` account, which carries a standing rule that it must never have
episodes marked played. Two safeguards, both deliberate: a throwaway `applicationId` so the install
had an empty ledger, and the knowledge that the outbox only pushes rows with `syncedToServer = false`
— of which a fresh install has none. The sync therefore read the subscription list and the action log
and wrote nothing. The app password it minted is the one residue, and revoking it is a manual step
flagged to the author rather than something to leave implied.

---

## 2026-08-09 — "impossible to connect on the Pixel 10a"

A second phone — Pixel 10a, Android 17 (SDK 37) — could not connect to Nextcloud at all. Every
attempt ended with *"Can't reach that address"* while the browser said access had been granted.

**`docs/decisions/0019` paid for itself.** The error log, added because "can't reach that address" is
six words that fit a dozen causes, held the answer:

```
AUTH ×3 · Connecting to Nextcloud failed: UNREACHABLE
Unable to resolve host "cloud.drehtuer.net": No address associated with hostname
```

Without it this would have been another round of guessing. With it, the question narrowed to "why can
this process not resolve a name the phone resolves fine?"

### What it was

`start()` succeeds — the browser opens on the grant page, every time. Opening the browser
**backgrounds the app**, and `poll()` then runs from a backgrounded process, which on this device
cannot resolve the host. One `UnknownHostException` ended everything, because the whole
`repeat(maxPollAttempts)` loop sat inside a single `runCatchingRequest`: a failure on attempt 1
abandoned all 200. The user granted access and came back to an app that had already given up.

### The author asked the right question

> *"What if the app only polls when it comes back into the foreground? Is there really a need to poll
> in background?"*

No. Login Flow v2 watches for something the **user** does in a browser, and the result is only usable
once they return. Polling behind their back bought nothing and cost the bug. The fix removes the
failing condition instead of working around it — no retry policy, no foreground service, no
permission: the call that cannot succeed is simply not made. ADR 0020.

Verified on the phone that could not connect at all: with the fix installed, returning to the app
after granting reached *"Connect as drehtuer?"* on the first try — the dialog that only appears after
`poll()` returns credentials **and** `verifyGpodderSync()` returns 200.

### Two wrong turns, both mine

1. **I reproduced my own interference and called it the bug.** Toggling Wi-Fi mid-poll produced the
   reported error, and I concluded it was a network-transition transient. The author's "reproducible
   with Wi-Fi on *and* mobile-only" is what killed that. **Reproducing a symptom is not reproducing
   the bug** — I had introduced the very condition I then blamed.
2. **I flagged `ACCESS_LOCAL_NETWORK: ignore` as anomalous.** It sits in the standard uid-mode block
   beside `CAMERA` and `READ_SMS`, all `ignore` for permissions the app never requests, and the server
   is public anyway. Reading one line out of a dump without reading the block around it.

A lot of correct-but-irrelevant ruling-out happened before the real question got asked: permissions,
netpolicy, Data Saver, standby bucket, private DNS, IPv6-only mobile with 464XLAT, the poll
hostname. All genuinely eliminated, none of them it. The thing that cracked it was noticing that
`start()` and `poll()` differ in exactly one respect — *which app is in front* — rather than in
anything about the network.

### Two device-testing traps, now documented

- **A CI-built debug APK cannot be upgraded by anything built elsewhere.** AGP's debug keystore is
  per-machine, so the release asset's debug APK carries the *runner's* key
  (`CN=Android Debug`, digest `afa0ec16…`). Both a local debug build and a correctly release-signed
  build were refused with `INSTALL_FAILED_UPDATE_INCOMPATIBLE`, whose message names the symptom and
  not the cause. Only `adb uninstall` clears it — with the ledger, login and grant.
- **`adb install` hangs over usbip where `adb push` runs at 100+ MB/s.** Same failure family as the
  UTP installer problem from August 3rd; `push` + `pm install` is the way through.

Both are in `docs/dev-environment.md` §10 now, because both cost more time than the bug did.

---

## 2026-08-08 — the logo lands, and two of its own specs turn out to be unbuildable

New brand assets arrived in `assets/logos/` with `docs/logo.md` describing where they go, plus
`docs/UI_logos.md` — a copy of `docs/UI.md` with §18 adapted. Task: check for missing icons, convert
to a format Android can actually render, fold §18 back into `UI.md`, delete the copy, and wire the
mark into the screens.

### The copy was stale, and diffing it first is the only reason that was caught

`UI_logos.md` was 1200 lines against `UI.md`'s 1304. Folding it in wholesale — the obvious reading of
"adapt UI.md and delete UI_logos.md" — would have silently reverted a week of amendments: ADRs 0012–
0015 back from *accepted* to *needs an ADR*, S3 back from a full screen to a bottom sheet, the backup
group and the foreground-only login poll gone entirely, `{ext}` back in the naming chips. The actual
§18 change was **three lines**: a paragraph saying the brand mark is not in the icon allow-list.

Lesson worth keeping: when handed "file B is file A with a change", diff them and port the change.
Do not copy B over A, however plausibly B is described as the newer one.

### What was missing

Not much, and not what I expected. All seven exported files existed for both builds, and the icon
allow-list matched `PodsiloIcons` exactly (27 for 27). Two real gaps:

- **The notification small icon.** `logo.md` §3 specifies one, and nothing exported it —
  `DownloadNotifications` was still posting `android.R.drawable.stat_sys_download`, the platform's
  generic arrow. Every download notification the app has ever posted was unbranded.
- **The splash screen** needs `androidx.core:core-splashscreen`, which is not a dependency. Asked;
  the author declined it outright — the app reaches S1 inside the splash's own minimum, so it would
  be a delay dressed as a brand moment. `logo.md` §3 no longer describes one, and it is recorded
  under *Declined, with reasons* in `docs/backlog.md` rather than left as a to-do nobody will do.

### Two of logo.md's own instructions could not be followed as written

Both found by trying to do them, which is the useful kind of finding.

1. **`ic_podsilo_lockup.xml` cannot exist.** A `VectorDrawable` has no text primitive, and the lockup
   SVGs carry the wordmark as live `<text>` in Archivo, which is not in the repo — so there is nothing
   to outline it with. Asked; the author chose composing the lockups from the mark drawable plus a
   `Text`. That turns out better than the drawable would have been: it scales with the user's font
   setting and follows `onSurface`, and it is the same reasoning §4.1 already gave for the app bar.
   The honest cost is recorded — in-app the wordmark is the platform font, not Archivo.

2. **`contentDescription = "Podsilo"` on the empty-state lockup is now wrong.** §6 asks for it
   because the lockup was to be "the only text-free instance". Composed from type it is not
   text-free, and I only noticed because the first test run failed: `clearAndSetSemantics` had eaten
   the wordmark's own text node. The failing assertion was right and my implementation was wrong —
   the description would have produced exactly the doubled "Podsilo Podsilo" the rule exists to
   prevent. Dropped it; `logo.md` §6 amended with the reason.

A third, smaller one: §4.2 and §4.3 size the lockups by total width (96 dp, 120 dp). A live-type
lockup has no fixed total width — it depends on the font and the user's font scale. Sized by the mark
instead (56 dp, 36 dp) with the wordmark derived from it at the ratio the SVGs use, so the proportion
survives any scale.

### A retired glyph

S1's not-configured empty state led with `server`. §4.2 replaces it with the lockup, which left
`server` with no call site — so it came out of `PodsiloIcons` and out of §18's table, and the count
test went 27 → 26. The table is an allow-list, and an unused entry in an allow-list is an invitation.
The brand mark pointedly did **not** take its place in that object: it lives in `PodsiloLogo.kt`
beside it, so no call site can reach for the logo as a glyph.

### Light and dark, without a resource qualifier

The two-colour mark's vessel is ink `#201E1D` — invisible on the dark scheme's `#14110F` surface. The
reflex is `drawable-night/`, and it is wrong here: the theme is a DataStore preference (UI.md §12.7),
so a user on Light with the system in dark mode would get the white mark on a light surface.
`PodsiloMark` reads `MaterialTheme.colorScheme.surface.luminance()` instead. Not asserted in a test —
Robolectric cannot tell the two drawables apart without pixel-reading, and an assertion that restates
the `if` proves nothing.

**Unverified:** all of it is Tier 1 renders. Whether the 24 dp mark reads in a real app bar, and
whether the notification silhouette survives the system's alpha mask on a real shade, are Tier 3
questions and are listed as open in `logo.md` §7.

### Same day, after review: verifying the placements rather than asserting them

The author asked for the splash to come out of the document (declined outright, not deferred — it is
under *Declined, with reasons* in the backlog now) and for a check that every screen renders the
logos it should.

That check could not be written, which was the finding. The mark is `contentDescription = null`
everywhere by design, so **no semantics query can find it** — there was no way to ask "is the logo on
this screen?", let alone "is it absent from S2–S8". A test tag was the way through
(`PODSILO_MARK_TEST_TAG`), and it is the rare tag in production code that earns itself: `logo.md` §4
calls its four placements "the complete list" and §5 names where the mark must never appear, and
neither claim was checkable.

Three `LogoPlacementTest`s now count marks per screen — S1–S3 in `:feature:episodes`, S4–S6 in
`:feature:settings`, S7–S8 in `:app` — because the screens live in three modules and a test belongs
beside what it tests. All twelve screens/states came out as specified on the first run; the counting
found no misplaced logo. What it did find is a doc bug: §4.1 says the mark is dropped in selection
mode, and S1 has no selection mode — that is S2, which never carries the mark. Corrected in place
rather than deleted, since the rule would be right if selection mode ever reached S1.

One assertion deliberately avoided: "S5 has no wordmark" cannot be checked by searching for the text
`podsilo`, because the Nextcloud account name it displays can be anything — and the existing
confirmation test uses `podsilo` as exactly that. Counting tags is the only question that stays true.

---

## 2026-08-09 — the logo on a real phone, and the dead test tier nobody had noticed

The two questions `docs/logo.md` §7 had been carrying as "Tier 3, unverified" — does the 24 dp mark
read in an app bar, does the notification silhouette survive the alpha mask — plus a live look at
the app on the author's Pixel 10a.

### Both questions are answered, and neither is answered by eye

Looking at a screenshot would have been the obvious move and the weak one: "does it read?" judged
from a 36-pixel glyph in a screenshot is a guess with a confident tone. Both questions are really the
same question — *do the gaps survive?* — and a gap is a pixel fact.

So both are now instrumented tests that rasterise the drawable on the device's own canvas and count
opaque/transparent alternations down the mark's centre line. Separation **is** the figure: two bars,
the silo's open mouth, the stored band. A mark whose bars had fused into the vessel — exactly what
§1's 16 dp floor exists to prevent — collapses that count, and the assertion says so. It holds at
24 dp and at 16 dp, and the inverse and mono builds turn out to be the same figure as the two-colour
one rather than three drawings that drifted apart.

Deliberately runner-only, no Compose and no Espresso. That was luck at first and turned out to be the
reason those tests could run at all.

### The find: the entire Compose Tier 3 suite was dead

The first device run failed 13 for 13 in `:feature:episodes` with
`NoSuchMethodException: android.hardware.input.InputManager.getInstance`, thrown inside
`Espresso.onIdle()` before any test body ran. Crucially it took the **pre-existing** conformance
tests down with it, not just the new ones — which is what made it a finding rather than a mistake of
mine.

Cause: `espresso-core` arrives transitively from `compose-ui-test-junit4` and nothing ever pinned it,
so it resolved to **3.5.0** while `androidx.test:core` had been bumped to 1.7.0. `InputManager.
getInstance()` no longer exists on Android 17. One version-catalog pin to 3.7.0 revived 21 tests.

The uncomfortable part: that suite has presumably been failing since the phone updated, and nothing
said so, because Tier 3 is run by hand. A tier that only runs when someone remembers to run it is a
tier that can be dead for weeks.

### What the phone actually showed

The luminance switch is the piece worth having verified live rather than argued about. With the
**phone in dark mode** and the **app's own theme set to Light**, S1 and S4 correctly showed the
two-colour mark — the precise case a `drawable-night` qualifier gets wrong, and the reason `docs/
logo.md` §6 says not to use one. Seeing the red bars on the light surface while the system was dark
is the whole argument in one screenshot.

Also confirmed: the app-bar mark reads at 24 dp; the stacked lockup carries the first-run screen; the
About lockup sits properly with the version; the launcher icon renders white-on-accent inside the
circular mask with nothing clipped.

### Two things I did not do

- **I did not uninstall the app to get past the signature mismatch.** The build in the container is
  signed with its own debug key, so it could not upgrade the installed 0.2.1 — and clearing that
  costs the ledger, the login and the folder grant. `INSTALL_FAILED_UPDATE_INCOMPATIBLE` is a
  *non-destructive* failure, so attempting the install was safe and stopping there was the point.
  The author cleared the app themselves; that was their call to make, not mine to make for them.
- **The notification has still not been seen in a real shade.** Posting one needs a download, which
  needs Nextcloud and a granted folder. The icon — the part that could have been wrong — is tested
  against the same alpha reduction the system performs. Recorded as such in `logo.md` §7 rather than
  rounded up to "verified".

---

## 2026-08-09 (later) — four issues from real use, and three of them were not what they said

Planning only, no code. Read `.claude/CLAUDE.md`, the eight documents in `docs/`, and the four open
GitHub issues (#46–#49), then traced each one into the source before writing a plan. The result is
`TODO.md`'s new Tier 5.

### The pattern that keeps recurring

Three of the four issues describe a symptom whose cause is somewhere other than where the issue
looks — and in two cases the cause is the same shape this project has now hit **four times**:
something specified in full, implemented at both ends, and never connected in the middle.

- **#46 (multi-select)** reads like a feature request. It is nearly finished. The whole selection
  model — five events, the "empty selection leaves selection mode" rule, the "a filter change drops
  the selection" rule — is implemented in `EpisodeListViewModel` and unit-tested. What is missing is
  `combinedClickable`: the row uses `clickable`, so long-press fires nothing and the mode is simply
  unreachable. Plus there is nowhere to put the toolbar, because —
- **#48 (clipped filter row)** is really two faults, and the bigger one is that **S2 has no
  `TopAppBar` at all** — the only one of eight screens without one. No back arrow, no title, no `⋮`
  carrying *Download all (n)* (whose event and confirmation dialog are built and tested and have no
  emitter), and content starting under the status bar. The chip row being a fixed `Row` with no
  scroll is the part the screenshot shows; the missing app bar is the part it doesn't.
- **#47 (activity delayed)** proposes an event bus. S7 already observes Room `Flow`s, so that
  plumbing exists. The real causes: `DownloadWorker` never calls `setProgress`, so
  `WorkInfo.progress` is always empty and **every** downloading row app-wide renders the
  indeterminate *resuming* bar forever (`WorkScheduler.observeDownloadWork()` exists and has no
  caller anywhere) — and `ActivityViewModel` re-projects the *entire* ledger with an N+1
  `episodeRepository.get()` per row on every emission, before filtering to the handful it renders.
  On a device with ~9,500 episodes that is thousands of sequential queries per ledger write, and it
  degrades as triage proceeds. Which matches the report exactly: fine at first, stale later.

Reading the code before believing the issue was worth roughly the whole session. The issues appear
to be LLM-drafted against assumptions about the app (RecyclerView, `ActionMode`, playlists, delete)
that do not hold — so the acceptance criteria are useful and the implementation notes mostly are not.

### #49 is the interesting one, because it overrules a shipped decision

`docs/UI.md` §12.3 is titled *"No undo — re-download instead"* and argues it from the protocol: a
skip becomes a `PLAY` in an **append-only** log that other clients act on, and the GPodder API has no
retraction. The author has now asked for undo, having hit the accidental swipe in practice. That is
a legitimate reversal — but the *how* decides whether it stays honest, so it goes to the author as a
decision rather than being implemented against the document that forbids it.

The sharp edge: `EpisodeLedgerRepository` has no delete, deliberately (CLAUDE.md §11 — the row "must
outlive the file"). So "write immediately, revert on undo" needs a new port method **and** cannot
help once the outbox has drained; "defer the write for the snackbar window" needs neither and cannot
post anything by mistake, at the cost of a decision being lost if the app dies within those seconds.
Recommended the latter, but did not write ADR 0021 — the lesson from ADR 0012, recorded in `TODO.md`,
is to write the ADR when the decision happens rather than ahead of it to reserve a number.

### Ordering, and the four decisions answered the same session

I1 (#48) → I2 (#47) → I3 (#46) → I4 (#49). Not by severity: I1 builds the app bar I3 needs, and I4
was last because it was the only one blocked on an answer.

All four decisions came back within the session, so nothing is actually blocked: **defer the write**
for undo (so no ledger delete, and an undone swipe leaves no trace anywhere), **keep** the bulk
confirmation dialogs alongside it, **scroll** the filter chips on one line, and **drop** *mark
unplayed* entirely rather than carry it as an open question. Three of the four were the recommended
option; the fourth went further than the recommendation — I had suggested deferring the ledger-delete
question, the author closed it.

Worth noting for the experiment: presenting each decision with its cost stated rather than as a
preference is what made them answerable in one pass. D1's real content was not "would you like
undo" but "which of these two things are you willing to lose" — a few seconds of durability, or the
guarantee that nothing un-retractable reaches the shared log.

### Also found, and deliberately not folded in

`EpisodeListUiState.feedError` is set by nobody and read by nobody, and `RetryFeedClicked` is in the
interface document but not in the code — so `docs/UI.md` §5's "feed fetch failed" banner cannot
occur, and a feed that fails to fetch is silent in S2. That is a *fifth* instance of the same shape.
Noted in `docs/backlog.md` rather than attached to #48, because that issue is about layout and this
is a missing state. Scope discipline over tidiness.

---

## 2026-08-09 (later still) — I1: the filter row was the smaller half of #48

First of Tier 5. Issue #48 reports clipped filter chips; the fix is two lines. The larger finding is
what the screenshot does *not* show: **S2 had no app bar at all**, alone among the eight screens.

That absence had been quietly load-bearing. No up navigation, no feed title, content starting at the
top of the window — and no host for *Download all (n)*, whose event, `BulkPreview`, confirmation
dialog and tests had all shipped with **nothing able to emit them**. Also no host for #46's selection
bar, which is why I3 was ordered after this one. The chip row is the part that got reported because
it is the part you can see.

### Two things the tests changed my mind about

**The regression test earned its keep by failing first.** Reverting the chip fix and re-running gave
`Semantic Node has no parent layout with a Scroll SemanticsAction` — which is the bug stated in the
framework's own words: the last chip is not merely off-screen, it is unreachable by any gesture. That
is the difference between a test that pins a fix and one that pins a bug.

**And it disproved something I had written down as fact.** The plan claimed
`sizeIn(minHeight = MinTouchTarget)` caused the "overlapping the action labels" half of the report.
A layout assertion at 320 dp says otherwise: the chip row and the first episode row do not overlap,
with or without the fix. What the screenshot actually shows is a row *scrolled under* a fixed chip
row — its bottom edge, the action buttons, hard against the chips with no gap to read as a boundary.
A legibility fault, not a layout one, fixed by padding rather than by the scroll. Both `TODO.md` and
the test's own KDoc now say so; the test is kept as an invariant guard, labelled as one, because a
test that passes against the unfixed code cannot claim to have found the bug.

### On-device tests, written blind

The author asked mid-session whether I had written tests that could run once a device is attached.
I had not — everything was Robolectric. Three instrumented tests now exist, and the reason they are
worth having is specific rather than ceremonial: #48 was a *measured layout* fault that stayed green
through 627 JVM tests. The Robolectric versions assert against a synthetic `w320dp` qualifier; the
device versions measure against the real width, density and **font scale**, which is the input most
likely to break the chip row next. One of them also exercises the overflow as a real popup window —
a separate window, not the shadow Robolectric substitutes.

They compile. They have not been run, and `TODO.md` says so rather than rounding up.

### detekt asked for the split, and it was right

`EpisodeListScreen.kt` hit `TooManyFunctions` at 11 on the way in. Same call as the DAO and the
ledger port before it: the threshold was pointing at a real seam — the screen owns the list and its
chrome, the app bar owns navigation *out* of the screen — so `EpisodeListAppBar.kt` exists rather
than a suppression.

### Not done, on purpose

S1's chip row is the identical `Row` with the identical defect, unreported and clipping only at a
large font scale. Two lines would fix it. It went to `docs/backlog.md` instead, because #48 is about
the episode screen. Same for the S2 *row* overflow, which §5 specifies and which does not exist —
noticed precisely because I was building the app-bar one.

635 JVM tests, 0 failures, 3 skipped.

---

## 2026-08-09 (I2) — the Activity screen was slow for a reason nobody had guessed

Issue #47 proposes an event bus. S7 already observed Room `Flow`s, so that plumbing existed and was
not the problem. Two faults were, and a third turned up on the way.

### Nothing published progress, and nothing observed any

`DownloadWorker` reported bytes to its notification and never called `setProgress`. Nothing read
`WorkInfo.progress` either — `WorkScheduler.observeDownloadWork()` had been written, documented and
left without a caller. So `docs/UI.md` §12.2 and `docs/UI_interface.md` §7, both specified in full,
described behaviour the app had never had: **every** `DOWNLOADING` row in S2, S3 and S7 drew the
indeterminate *resuming* bar from start to finish.

The fix publishes inside the notification's existing 1 Hz tick rather than adding a second timer.
That is the whole reason §7 can promise the surfaces never disagree — one clock drives them all.

One thing that had to be discovered rather than assumed: `WorkInfo` exposes its **tags** and not the
unique work name it was enqueued under. Without a tag carrying the episode key there is no way to
map a queued download back to its episode, so a per-feed count or an S7 row has nothing to key on.

### The actual latency: an N+1 over the whole ledger

`ActivityViewModel` observed *every* ledger row on the device, then looked each row's episode up one
at a time in Kotlin, and only then discarded all but the handful in flight. On the author's device
that is thousands of sequential queries per emission — and it re-ran on every ledger write anywhere
in the app. The screen was not "not observing"; it was doing far too much work each time it did.

Narrowing it is three queries (`observeInFlight`, `observeRecentlyDelivered`, `observeUnsyncedCount`)
and no schema change. The cost claim is now a test: a thousand decided rows must not enlarge the
in-flight result, which is exactly what the Kotlin-side filtering could never promise — by the time
it filtered, it had already loaded and joined all of them.

### Two dead things found by building the live one

- **`FeedUi.activeDownloads` had a default of 0 and no assignment anywhere.** S1's "n downloading"
  line and the app-bar badge have never once rendered. It falls out of the same bounded query.
- **No list query projected `lastErrorCause` or `lastErrorRetryable`.** The columns exist since
  schema v3, the entity declares them, and all three `SELECT`s left them out, so Room saw `NULL` and
  the defaults applied. ADR 0011's guarantee — *a lost folder grant offers "Choose folder", never a
  Retry that cannot work* — was therefore unreachable from the database. Both the ADR and `UI.md`
  §12.11 read as though it worked; only the SQL disagreed.

That last one is the uncomfortable pattern of this session: **the projection bug was invisible to
every existing test** because the tests that care about `FailureUi` construct it directly rather than
reading it back through a query. A regression test that goes through the DAO fails against the old
`SELECT`s; nothing else did.

### detekt as a design signal, again

`EpisodeLedgerDao` hit the function ceiling when the outbox-depth count was added. The count is a
*screen read*, so it moved to `EpisodeListDao` — the seam those two DAOs were already split along.
Third time the ceiling has pointed at a real seam rather than an arbitrary limit.

### S7's view model had no test at all

It does now, with its own fakes in `:app`. Worth noting that the fakes are per-query settable flows
on purpose: if one flow backed all three, "the delivered list changed" could be satisfied by the
in-flight query and the test would prove nothing.

653 tests, 0 failures, 3 skipped.

---

## 2026-08-09 (I3) — the feature that was three-quarters written

Issue #46 asks for multi-select. The view model already had it: five events, the "an empty selection
leaves the mode" rule, the "a filter change drops the selection" rule, and unit tests for all of it.
`EpisodeRow` used `clickable` rather than `combinedClickable`, so **nothing could emit
`SelectionStarted`** and the mode was unreachable — and with no app bar (until I1) there was nowhere
to render `n selected` anyway.

So the work was one new event pair and a bar. Which is the interesting part: this is the *sixth*
instance of the shape this project keeps producing — specified, wired at both ends, no connection in
the middle. The others were pull-to-refresh, the artwork slot, the swipe gesture, *Download all*, and
S2's feed-error banner. Something about building state-first, with the screen last, reliably leaves
exactly this gap, and unit tests never catch it because the view model genuinely works.

### The one thing I added to the model rather than the UI

The bar could have emitted `BulkConfirmed` straight from its buttons and let the screen own a
dialog. `SelectionActionRequested` exists instead, so that "name the count before anything is
written" is *structural* — the bar cannot reach the write path without passing through a
confirmation, exactly as *Download all* and *Mark all as played* already work. For an action that
emits `PLAY` to a shared log no undo reaches, a convention is not good enough.

`pendingSelectionAction` is its own state field rather than sharing `pendingBulk`, for the reason
already written into that field's KDoc: three dialogs say different things, and one shared field is
one bug away from rendering the download wording over a mark-as-played confirmation.

### Accessibility was the part with a real decision in it

`docs/UI.md` §12.12 says selection must be reachable without a long-press, and suggests "a checkbox
appears when the accessibility service is active". I did not implement service detection: branching
the UI on whether TalkBack is running means the layout a sighted user sees is not the one being
tested, and a11y-only code paths rot quietly. A **custom accessibility action** on every row is
better — always present, no detection, and it emits the *same* event the long-press does rather than
a parallel path that could drift.

That also gave `square`/`square-check` their first call site. They had been on §18's allow-list, for
exactly this, since the list was written.

### Scope the issue asked for and did not get

*Add to queue*, *add to playlist* and *remove/delete* are §1 non-goals; *mark unplayed* was declined
by the author as decision D4. All four were already recorded in `docs/backlog.md` during planning, so
this needed no new judgement — which is the value of having written it down a week earlier.

666 tests, 0 failures, 3 skipped, plus 2 instrumented (long-press is a *timed* gesture and depends on
the platform's real long-press timeout and touch slop — the kind of thing that passes headless and
fails in the hand). Not run: no device attached.

---

## 2026-08-09 (I4) — undo that never had to un-send anything

Last of Tier 5. Issue #49 asks for undo after a swipe, against a design document titled
*"No undo — re-download instead"* whose argument was the protocol: a skip becomes a `PLAY` in an
append-only log, other clients act on it, and the GPodder API has no retraction.

The interesting part is that the argument survived the reversal. The author asked for undo; the
question that went back (D1) was not *whether* but *which of two costs are you buying*. Write now and
revert on undo is durable across process death and needs a **delete on the ledger** — the one table
CLAUDE.md §11 says must never lose a row — and it still cannot help once the outbox has drained,
which can happen immediately. Defer the write needs no delete and cannot post something it must
retract, at the cost of losing a decision if the process dies inside the window.

The author chose to defer. So the implementation never touches the ledger until the window closes,
and *Undo* is a `null` assignment. The ADR states the loss case explicitly rather than leaving it to
be discovered.

### Three things that were not obvious until they were written

**The view model has to own the window, not the snackbar.** The tempting version lets
`SnackbarResult.ActionPerformed` decide. Then the snackbar's duration and the write timer are two
clocks, and a tap at 4.9 s races a write at 5.0 s. Making the view model authoritative turns the race
into a no-op: an undo that arrives late finds nothing to discard. The host only reports the tap.

**Leaving the screen must commit, and `viewModelScope` cannot do it.** By the time `onCleared` runs
the scope is already cancelled, so a write launched there never happens — silently. The commit goes
to an injected scope that outlives the view model. That is the one place this class reaches outside
its own lifecycle, and it is worth the parameter: the alternative is a decision the user watched take
effect vanishing because they hit Back.

**The row has to lie for five seconds.** A swipe that appears to do nothing reads as the app ignoring
it, and the user swipes again. So the row renders the state the decision *will* produce — which also
means it does not change appearance a second time when the write lands. Presentation only; every
other screen reads storage and correctly lags by the window.

### Testing the lifecycle without a test hook

`onCleared` is `protected`. The options were adding a `@VisibleForTesting` method to production code
or going through a real `ViewModelStore` — construct a provider that hands back the instance, then
`store.clear()`. The second is four lines and exercises the actual hook; the first would have been a
method that exists only because a test wanted it.

### detekt, a fourth time

`EpisodeListViewModelTest` went over `LargeClass`. The undo window is genuinely its own behaviour —
every test in it moves virtual time — so the harness moved to `EpisodeListTestHarness` and the undo
tests to `EpisodeListUndoTest`. Fourth time this ceiling has pointed at a real seam rather than an
arbitrary limit (after the DAO split, the ledger port, and `EpisodeListScreen`).

### A pre-existing test caught the behaviour change, which is the system working

`a swipe performs the configured action, not a hard-coded one` failed immediately: it asserted a
write straight after the swipe. Its subject is *which* action, not *when*, so it now waits the window
out. Worth noting because it is the only signal that would have caught an accidental change in swipe
semantics — and it fired on the first run.

673 tests, 0 failures, 3 skipped.

---

## 2026-08-10 — three backlog notes, and the two bugs hiding behind them

The author asked for the three items `docs/backlog.md` picked up while Tier 5 was being built. Each
was small; two of them were sitting on top of something that was not.

### The row overflow was a design decision, not a gap

`docs/UI.md` §5's row anatomy ends at "status badge/progress, overflow `⋮`", and §12.1 calls that
overflow a **mandatory** non-gesture equivalent of the swipes. The row instead drew its applicable
actions as inline `TextButton`s. Building the overflow therefore meant *removing* those buttons, not
adding a menu beside them — which is a visible change to something the author uses daily, so it is
flagged rather than slipped in. The design is the reason; if it turns out wrong in the hand it is a
small revert.

**What it exposed:** `COPY_LINK` and `OPEN_IN_BROWSER` both emitted `OpenUrl` — in S2 *and* S3. So
*Copy episode link* opened a browser, and `SnackbarText.LinkCopied` had no producer anywhere. That
had been true since the actions were written; nothing caught it because `labelFor` returned `null`
for both, so the list had no call site and only the sheet could reach them — where "copy" quietly
did the wrong thing. Giving an action its first real call site is a good way to find out it never
worked.

### The banner needed a fact the app was not recording

The feed-error banner is easy: read the plain sentence `FeedRefresher` already writes to the error
log. The hard half is *when to stop showing it*. "An error exists" is wrong — one from three days ago
that a later refresh cleared would sit there for ever. The right rule is "newer than the last
successful refresh".

Except a **304 did not update `lastRefreshedAt`**. A feed that is reached and unchanged — the common
case for a podcast between episodes — recorded nothing at all, so S1 showed an ever-older "last
refreshed" for a feed being checked every fifteen minutes, and the banner could never clear. That is
a real bug in its own right; the banner just needed it to be true.

Its test asserted `metadataUpdates == 0`, so the change failed immediately. The old assertion was
defensible about the thing it was protecting (don't rewrite what you didn't fetch) and wrong about
how it checked (nothing at all). It now asserts the validators and title survive **and** the
timestamp moves.

### Two more tests broke for a reason worth keeping

Both pull-to-refresh tests failed once the buttons left the rows. `swipeDown()` travels from a node's
top to its bottom, so the gesture's distance depended on how tall a row happened to be — and a
shorter row stopped crossing the refresh threshold. They now swipe an explicit distance. A test whose
subject is "does pulling refresh" should not be coupled to row height.

The `FakeFeedRepository` also turned out to append on `seed`, so seeding one URL twice produced two
rows and which one the screen saw depended on ordering. It replaces by URL now, as the real primary
key would, and the harness defers to a test's own row.

### A NUL byte, self-inflicted

A `perl -0pi` edit with a mistyped `\x A7` wrote a literal NUL into a test file, and every `grep`
against it silently returned nothing — the file was being treated as binary. Worth remembering as a
failure mode: the tool did not report an error, it just stopped answering. `rg` said "binary file
matches" and gave the offset, which is how it was found.

detekt asked for two splits along the way (`EpisodeRowText.kt`, `EpisodeListFeedErrorTest`), both
real seams. That is the fifth and sixth time.

684 tests, 0 failures, 3 skipped.
