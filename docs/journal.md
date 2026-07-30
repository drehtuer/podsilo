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

**Observed mid-session:** the author had already built and run the real dev container in VS Code, which
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
