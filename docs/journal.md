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
