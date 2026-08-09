<!-- SPDX-License-Identifier: GPL-3.0-or-later -->

# Backlog

Where ideas go instead of into the code (CLAUDE.md §1/§9: "if you spot something worth doing that
isn't asked for, note it here and move on"). Nothing in this file is committed to, scheduled, or
implied. Anything that would touch a §1 non-goal stays a note here permanently unless the author
says otherwise.

This file was created empty-ish on 2026-08-01 during a documentation consistency pass — CLAUDE.md
had referenced it since the beginning and it had never existed, so anything that *should* have been
noted here before that date was instead either built, declined in conversation, or lost.

## Open items

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
  (`docs/decisions/0006`). Needs an encoder the dev container lacks; a few tiny committed fixtures
  would close it permanently.
- **A device test for the download pipeline end to end** — enclosure fetch → tag write → SAF copy →
  ledger → outbox. Blocked on nothing but a subscription: subscriptions come only from Nextcloud, and
  seeding the SQLite file directly does not help, because with no account configured S1 correctly
  shows the *not configured* empty state instead of the list (`docs/UI.md` §4). Do it alongside the
  real-device Nextcloud login.
- **A `scripts/adb-connect-host.sh` helper** for Tier 3 (emulator on the Windows host, driven from
  the container). CLAUDE.md §4 asks for it explicitly; `docs/dev-environment.md` §6 records that
  neither it nor Tier 3 exists yet. Worth writing the first time someone actually needs a device.
- **Paging 3 for the episode list.** CLAUDE.md §3/§5 mandate it for long lists; the UI contract
  currently says "paging or a keyed `LazyColumn`" (`docs/UI_interface.md` §14.3). A 500-episode feed
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
- **S1's filter chips have the same shape as the row #48 reported on S2.** `PodcastFilterChips` is
  the same fixed `Row` with no scroll and no wrap. It has not been reported, and it has only two
  chips — *With new episodes* and *All podcasts* — so it fits a 360 dp screen at the default font
  scale and clips only at a large one. Deliberately **not** changed while fixing #48: that issue is
  about the episode screen, and CLAUDE.md §9 says note it rather than widen the change. The fix is
  the same two lines if it is ever wanted.

- **The S2 row overflow `⋮` does not exist.** `docs/UI.md` §5's row anatomy ends with an overflow
  carrying *Download* / *Download again*, *Mark as played*, *Retry*, *Cancel download*, *Copy episode
  link* and *Open in browser*; `EpisodeRow` instead renders the applicable ones as inline
  `TextButton`s from `EpisodeUi.actions`. Nothing is unreachable as a result — `actions` is the same
  single source of truth either way — but *Copy episode link* and *Open in browser* have no row-level
  call site at all, since `labelFor` returns `null` for both (they exist only in S3). Noticed while
  building the *app-bar* overflow for #48, and left alone: swapping visible buttons for a hidden menu
  is a design change, not a bug fix.

- **S2's feed-error banner is specified, has a state field, and is connected at neither end.** Found
  on 2026-08-09 while tracing the four v0.3.0 issues. `EpisodeListUiState.feedError` exists with a
  KDoc explaining that it "renders as an inline banner *above* the list, never in place of it" —
  `EpisodeListViewModel` never sets it, and `EpisodeListScreen` never reads it. `docs/UI_interface.md`
  §3 also declares a `RetryFeedClicked` event that does not exist in the code. So `docs/UI.md` §5's
  "Feed fetch failed: inline banner with the reason in plain words + **Try again**" state cannot
  occur: a feed that fails to fetch is silent in S2, and the only trace is the S8 entry
  `FeedRefresher` writes. Not folded into the #48 work — that issue is about layout, and this is a
  missing state — but it is the fourth instance of the same shape (specified, wired at one end, no
  connection in the middle) and cheap to close alongside any other S2 change.

- **Revoke the app password when the account is rejected.** S5 now confirms the account before
  storing it (ADR 0019), so *Use a different account* throws away a password that Nextcloud has
  already issued and still lists under *Security*. `DELETE /ocs/v2.php/core/apppassword`
  authenticated with that password would clean it up. Left out of the fix deliberately: it is a new
  endpoint with its own failure modes, added to the one code path whose job is to store nothing, and
  the leftover is harmless and user-revocable. Worth doing if rejection turns out to be common.

## Distribution readiness (audited 2026-08-04)

Against [developer.android.com/studio/publish](https://developer.android.com/studio/publish) and the
[F-Droid quick start](https://f-droid.org/en/docs/Submitting_to_F-Droid_Quick_Start_Guide/). What
already holds is in `docs/dev-environment.md` §10; these are the gaps, in the order they bite.

- ~~**The app has no icon.**~~ **Done 2026-08-04, replaced 2026-08-08.** The placeholder adaptive
  icon has given way to the real brand mark (`docs/logo.md`): the silo build, vector-only, with a
  `<monochrome>` layer for Android 13 themed icons. What is still missing is a **512×512 PNG for
  store listings** — Play and F-Droid both want a raster, and neither reads it from the APK. It
  belongs with the Fastlane metadata below; the dev container has no image tooling, so it needs
  generating elsewhere. Source is `assets/logos/podsilo-icon.svg`, whose geometry does not change
  with scale.
- ~~**Espresso is too old for the phone.**~~ **Fixed 2026-08-09.** Compose UI tests pull
  `androidx.test.espresso:espresso-core` transitively, and nothing pinned it — so it resolved to
  **3.5.0** (2022) while `androidx.test:core` had been bumped to 1.7.0. On Android 17 every Compose
  instrumented test died inside `Espresso.onIdle()` with
  `NoSuchMethodException: android.hardware.input.InputManager.getInstance`, before running a line of
  its own. That silently killed the **entire** Tier 3 Compose suite — 21 tests across `:feature:*`,
  including conformance tests that had been passing on older devices. Pinned to 3.7.0 in the version
  catalog. Worth remembering as a shape: a transitive test dependency nobody pinned is one Android
  release away from taking a whole tier with it, and the failure names a platform method rather than
  anything about this project.
- **Outline the wordmark in the lockup SVGs.** `docs/logo.md` §2: anything leaving the app — store
  listing, README, press — must not depend on Archivo being installed. Nothing in-app is blocked,
  since the in-app lockups are composed from live type.
- **jaudiotagger comes from JitPack.** F-Droid builds everything from source in its own buildserver
  and treats a JitPack coordinate as a third-party prebuilt binary. Expect to either add a `srclibs`
  entry that builds `Adonai/jaudiotagger` from source, or vendor it. This is the substantive F-Droid
  blocker, and it is a consequence of ADR 0006 — worth reopening only if F-Droid is actually wanted.
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
  (ADR 0019). **Declined by the author on 2026-08-04: grant-by-browser stays, and no login or
  password field goes in the app.** That keeps ADR 0010 and CLAUDE.md §5 intact — the app never
  handles the account password — at the known cost that a wrong browser session can only be fixed in
  the browser.
- **The batch actions issue #46 asked for beyond triage.** *Add to queue*, *add to playlist* and
  *remove/delete* are CLAUDE.md §1 non-goals permanently — no player, no playlists, no file lifecycle
  — recorded here so the answer does not have to be re-derived each time an issue asks for them.
  **Declined by the author on 2026-08-09 along with *mark unplayed***, which is the interesting one:
  it is not a non-goal, it simply has no representation. An undecided episode is one with **no**
  ledger row, so "unmark" means *deleting* the record that stops an episode being downloaded twice
  (CLAUDE.md §11). The ledger stays append-only, and undo (#49) was designed to need no delete
  either — see `TODO.md` Tier 5, decisions D1 and D4.

- **"Download all visible" as a prominent button.** CLAUDE.md §1 names this specifically as the kind
  of thing that looks helpful and isn't. The UI design's per-podcast *Download all (n)* overflow item
  is a narrower version, and was accepted as a *command* rather than a rule — ADR 0014.
- **A two-pane tablet layout.** Not an omission: `docs/UI.md` §19 explains why the triage model makes
  it the wrong shape. The content-width cap is the part of it worth keeping.
- **A splash screen.** `docs/logo.md` §3 originally specified one — the mono mark on `#EC3013`, via
  `androidx.core:core-splashscreen`. **Declined by the author on 2026-08-08.** The app reaches S1
  well inside the splash's own minimum duration, so it would be a delay dressed as a brand moment,
  and it costs a dependency. `logo.md` §3 no longer describes one; do not reintroduce it as polish.
- **Anything that writes to the subscription list.** Permanently out of scope (CLAUDE.md §1).
