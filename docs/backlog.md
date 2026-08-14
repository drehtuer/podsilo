<!-- SPDX-License-Identifier: GPL-3.0-or-later -->

# Backlog

Where ideas go instead of into the code (CLAUDE.md §1/§9: "if you spot something worth doing that
isn't asked for, note it here and move on"). Nothing in this file is committed to, scheduled, or
implied. Anything that would touch a §1 non-goal stays a note here permanently unless the author
says otherwise.

This file was created empty-ish on 2026-08-01 during a documentation consistency pass — CLAUDE.md
had referenced it since the beginning and it had never existed, so anything that *should* have been
noted here before that date was instead either built, declined in conversation, or lost.

Finished items are **deleted**, not struck through: `docs/journal.md` is the record of what was
done and when, and a backlog that also keeps that record is two records (2026-08-13).

## Open items

- **A failed `GET` is not an `IOException`.** Retrofit throws its own `HttpException` for a non-2xx
  response on the two `GET`s, so an expired app password lands in `SyncOrchestrator`'s
  *non-retryable* branch and in the error log as a plain `SYNC` failure rather than `AUTH` — the one
  category S8's filter chips exist to separate. Sniffing "401" out of a message string works until a
  server rewords it, so the fix is a typed failure on the `GpodderClient` port. Carried over from
  `docs/TODO.md` when the #60 work landed and that file was retired (2026-08-14).

- **The default sync interval is four hours** (`DEFAULT_SYNC_INTERVAL_MINUTES = 240`). Since the
  triage triggers and pull-to-refresh both run a pass now, the user-visible delay is gone and this
  only governs unattended background catch-up; 15 minutes is WorkManager's floor and Doze stretches
  it anyway. Left as the author's call on battery and traffic — it was D1 in `docs/TODO.md`, and
  nothing depends on it.

- **Nothing lints `src/androidTest/`.** Verified 2026-08-11:
  `runKtlintCheckOverAndroidTestDebugSourceSet` reports **`NO-SOURCE`** (the Kotlin dir is never
  registered with ktlint), and detekt's default source roots are `src/main` + `src/test` only, which
  `build.gradle.kts` does not extend. So the device tests — the ones with no CI coverage either — are
  also the only Kotlin in the repo with no style or complexity checking at all. Caught while fixing
  the two stale tests: an over-length line in a device test passed `./gradlew ktlintCheck detekt`
  and had to be found by hand. Small to fix (add the source dirs to both), but it will surface a
  backlog of existing violations, which is why it is a note rather than a drive-by.

- **`device-test.sh` and `adb-connect-host.sh` refuse to run over wireless debugging.** Both gate on
  "no adb server inside the container", which is correct for the usbip path and exactly wrong for the
  wireless one, where that server is what holds the connection (`docs/dev-environment.md` §9.4). A
  wireless run currently means executing the script's steps by hand. The distinguishing fact is
  cheap — a device serial shaped `<ip>:<port>` is a network device, and `adb devices` showing one
  means the local server is legitimate — so the guard could be narrowed rather than removed.

- **Two `ErrorCause` values are unreachable by design.** `FEED_PARSE` and `TAG_WRITE` are declared
  and never produced anywhere: a tag-write failure must never fail a download (CLAUDE.md §6), so no
  ledger row can carry `TAG_WRITE`; and feed failures are recorded in the *error log*
  (`LogCategory.FEED`) rather than on an episode ledger row, so `FEED_PARSE` has no writer either.
  They are safe to delete — nothing has ever persisted them, so no stored `lastErrorCause` can hold
  one — but `ErrorCause` is a persisted vocabulary, which makes it the author's call rather than a
  tidy-up. Found by a repo-wide producer scan on 2026-08-03.

- **Cleartext `http://` URLs in feeds.** Android blocks them by default at `targetSdk` 28+, and the
  author's `heute journal` feed advertises its cover art over `http://` — so that one podcast shows
  the monogram rather than its image. Harmless there, since `PodsiloArtwork` falls back cleanly. The
  question is **enclosures**: none of the author's 9,565 episodes currently use `http://`, but a feed
  that did would fail to download with a network error and no obvious cause. Options are a
  network-security config permitting cleartext (weakens every request), permitting it per-domain
  (unmaintainable), or upgrading `http://` to `https://` and falling back — decide when a feed
  actually needs it, not before.

- **The restore file picker shows every file, not just zips.** `MainActivity` passes
  `arrayOf("application/zip", "application/octet-stream", "*/*")` to `OpenDocument`, and the `*/*`
  makes the filter a no-op — in a real Downloads folder the picker lists PDFs, APKs and photos. The
  wildcard is there because some file managers report zips under other MIME types, so a real backup
  must never be un-pickable; the question is whether dropping `*/*` and keeping the first two is
  enough coverage. Verified as a real annoyance on-device, not theorised.

- **Non-MP3 tagging fixtures.** `audio/silence.mp3` is the only audio fixture, so M4A, OGG and Opus
  tag and artwork writing is supported by jaudiotagger but never exercised by our tests
  (`docs/architecture.md` §11). Needs an encoder the dev container lacks; a few tiny committed fixtures
  would close it permanently.
- **A device test for the download pipeline end to end** — enclosure fetch → tag write → SAF copy →
  ledger → outbox. Blocked on nothing but a subscription: subscriptions come only from Nextcloud, and
  seeding the SQLite file directly does not help, because with no account configured S1 correctly
  shows the *not configured* empty state instead of the list (`docs/UI.md` §4). Do it alongside the
  real-device Nextcloud login.
- **Paging 3 for the episode list.** CLAUDE.md §3/§5 mandate it for long lists; the UI contract
  currently says "paging or a keyed `LazyColumn`" (`docs/UI.md` §B14.3). A 500-episode feed
  under the `All` filter is the case that decides it — measure before adding the dependency.
- **Split `EpisodeLedgerRepository` into two ports.** It now carries eleven methods covering two
  roles: the ledger and its outbox, and the UI-facing episode queries (`observeEpisodes`,
  `observeUndecidedCounts`, `previewUndecided`, `undecided`). The DAOs were already split along that
  line for the same reason. detekt flags the Room implementation's function count, suppressed there
  with this note, because splitting the *port* touches `:core:sync`, `:core:download` and
  `:feature:episodes` and does not belong inside a UI change.
- **Full Nextcloud + `gpoddersync` as an opt-in compose profile.** CLAUDE.md §4 offers it as an
  option; `docs/dev-environment.md` §7 records the deliberate decision not to build it. The cost is
  that ADR 0008 stays source-read-only, permanently.
- **Revoke the app password when the account is rejected.** S5 now confirms the account before
  storing it (UI.md §8), so *Use a different account* throws away a password that Nextcloud has
  already issued and still lists under *Security*. `DELETE /ocs/v2.php/core/apppassword`
  authenticated with that password would clean it up. Left out of the fix deliberately: it is a new
  endpoint with its own failure modes, added to the one code path whose job is to store nothing, and
  the leftover is harmless and user-revocable. Worth doing if rejection turns out to be common.

## Distribution readiness (audited 2026-08-04)

Against [developer.android.com/studio/publish](https://developer.android.com/studio/publish) and the
[F-Droid quick start](https://f-droid.org/en/docs/Submitting_to_F-Droid_Quick_Start_Guide/). What
already holds is in `docs/dev-environment.md` §10; these are the gaps, in the order they bite.

- **No 512×512 PNG for the store listings.** Play and F-Droid both want a raster and neither
  reads it from the APK, so it belongs with the Fastlane metadata below. The dev container has no
  image tooling, so it has to be generated elsewhere; the source is `assets/logos/podsilo-icon.svg`,
  whose geometry does not change with scale.
- **Outline the wordmark in the lockup SVGs.** `docs/UI.md` §C2: anything leaving the app — store
  listing, README, press — must not depend on Archivo being installed. Nothing in-app is blocked,
  since the in-app lockups are composed from live type.
- **jaudiotagger comes from JitPack.** F-Droid builds everything from source in its own buildserver
  and treats a JitPack coordinate as a third-party prebuilt binary. Expect to either add a `srclibs`
  entry that builds `Adonai/jaudiotagger` from source, or vendor it. This is the substantive F-Droid
  blocker, and it is a consequence of architecture §11 — worth reopening only if F-Droid is actually wanted.
- **No Fastlane metadata.** F-Droid reads `fastlane/metadata/android/en-US/` (title,
  short_description, full_description, changelogs, screenshots) straight from the repository. None
  exists.
- **No App Bundle.** Google Play has required `.aab` since 2021; we publish APKs. Correct for
  sideloading and F-Droid, and `bundleRelease` already exists if Play ever matters — but as it
  stands, Podsilo cannot be submitted to Play.
- **Reproducible builds are unverified.** `BuildConfig.BUILD_TIME` alone makes two builds of one
  commit differ. F-Droid does not require reproducibility, but it forecloses the verified-build
  badge, and the timestamp is the only obstacle.

## Declined, with reasons

- **An "alternative log in using app password" form.** Nextcloud offers one on its own flow page, and
  it is the only way to name an account directly instead of inheriting the browser's session
  (UI.md §8). **Declined by the author on 2026-08-04: grant-by-browser stays, and no login or
  password field goes in the app.** That keeps architecture §2 and CLAUDE.md §5 intact — the app never
  handles the account password — at the known cost that a wrong browser session can only be fixed in
  the browser.
- **The batch actions issue #46 asked for beyond triage.** *Add to queue*, *add to playlist* and
  *remove/delete* are CLAUDE.md §1 non-goals permanently — no player, no playlists, no file lifecycle
  — recorded here so the answer does not have to be re-derived each time an issue asks for them.
  **Declined by the author on 2026-08-09.** *Mark unplayed* was declined with them and has since been
  **built** (`docs/decisions/0024`, 2026-08-14): it turned out the objection was to the *delete*, not
  to the feature, and a new `UNPLAYED` state gives the user the affordance while the row — the dedup
  authority — stays exactly where it was.

- **"Download all visible" as a prominent button.** CLAUDE.md §1 names this specifically as the kind
  of thing that looks helpful and isn't. The UI design's per-podcast *Download all (n)* overflow item
  is a narrower version, and was accepted as a *command* rather than a rule — ADR 0014.
- **A two-pane tablet layout.** Not an omission: `docs/UI.md` §19 explains why the triage model makes
  it the wrong shape. The content-width cap is the part of it worth keeping.
- **A splash screen.** `docs/UI.md` §C3 originally specified one — the mono mark on `#EC3013`, via
  `androidx.core:core-splashscreen`. **Declined by the author on 2026-08-08.** The app reaches S1
  well inside the splash's own minimum duration, so it would be a delay dressed as a brand moment,
  and it costs a dependency. `docs/UI.md` §C3 no longer describes one; do not reintroduce it as polish.
- **Anything that writes to the subscription list.** Permanently out of scope (CLAUDE.md §1).
