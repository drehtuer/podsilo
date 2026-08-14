# Podsilo

[![CI](https://github.com/drehtuer/podsilo/actions/workflows/ci.yml/badge.svg?branch=main)](https://github.com/drehtuer/podsilo/actions/workflows/ci.yml)
[![CodeQL](https://github.com/drehtuer/podsilo/actions/workflows/github-code-scanning/codeql/badge.svg?branch=main)](https://github.com/drehtuer/podsilo/security/code-scanning)
[![Release](https://img.shields.io/github/v/release/drehtuer/podsilo?sort=semver)](https://github.com/drehtuer/podsilo/releases/latest)
[![Licence: GPL-3.0-or-later](https://img.shields.io/badge/licence-GPL--3.0--or--later-blue)](LICENSE)

The **CI** badge covers six checks — yamllint, shellcheck, ktlint, detekt, the Tier 1 unit tests and
both APK builds — and the whole workflow is one job, so a green badge means all six passed and a red
one means at least one did not. The first two lint the languages Gradle cannot see: the YAML that
carries the release signing and the dependency policy, and the bash scripts that set up the
container and drive a real phone. Rules live in `.yamllint.yml` and `.shellcheckrc`, and the dev
container carries both tools, so the same two commands run locally.

The badge deliberately says nothing about the device tests: those live in `src/androidTest/` and no
hosted runner has a device to run them on (see `.github/workflows/ci.yml`, which explains at length
why an emulator is not added there).

The **CodeQL** badge is GitHub code scanning, on default setup rather than a workflow file in this
repository — which is why its badge URL points at `github-code-scanning/codeql` and not at
`.github/workflows/`. It analyses `java-kotlin` and `actions` on every push and pull request, plus
weekly. Green means the analysis ran, **not** that it found nothing; the alert count lives behind the
badge on the [code scanning page](https://github.com/drehtuer/podsilo/security/code-scanning).

The repository's other security checks — **Dependabot** alerts and security updates, **secret
scanning** and its push protection — have no badge, and cannot have one. Their state is private to
the repository even though the code is public, so GitHub publishes no endpoint for them and no
third-party badge service can read them either. [`SECURITY.md`](SECURITY.md) records what they cover.

An Android **podcast catcher** that downloads episodes into a folder you control — and deliberately
does not play them.

Think of it as a silo: episodes flow in from your feeds, pool in a folder you picked, and are
consumed by whatever audio player you actually like.

> **Status: [v0.4.0 released](https://github.com/drehtuer/podsilo/releases/latest) — the app works
> end to end, and triage now scales.** Subscription mirroring, feed refresh, the download pipeline,
> GPodder sync, Nextcloud login, naming and tagging are built and tested (684 JVM tests, green), and
> all eight screens in `docs/UI.md` render and are reachable. Verified against a **real Nextcloud on
> a real phone** — login, ~9,500 episodes across four feeds, reconciliation, downloading, tagging and
> backup/restore. `podsilo-0.4.0.apk` is a signed, minified release build; sideload it on your own
> device.
>
> The device test set was last run on 2026-08-11 against a Pixel 10a (Android 17): **60 tests, 54
> passed, 0 failed, 6 skipped.** The skips are the SAF write, which opts out on an install with no
> folder granted — see [`docs/dev-environment.md`](docs/dev-environment.md) §6.

## The idea

Podsilo follows the podcast subscriptions stored in a self-hosted **Nextcloud** instance (via the
[gpoddersync](https://github.com/thrillfall/nextcloud-gpodder) app) and lists their episodes for
manual triage. For each new episode you choose **download** or **skip** — nothing is ever downloaded
automatically, and nothing is downloaded twice.

That last part is the app's central job. Every download emits a `DOWNLOAD` action and every skip
emits a `PLAY` action back to the server, so an episode you have handled here never shows up as new
again — not on this device, and not in RePod or AntennaPod on another one.

Downloaded files are renamed and re-tagged to a configurable template
(`Der Podcast/20260714_Warum-Hamburg-immer-regnet.mp3`), because the filenames in someone else's
player are the entire user experience of a download. Feeds are inconsistent about this; Podsilo
isn't.

## What it is not

- **Not a player.** No playback, no playlists, no speed control.
- **Not a feed manager.** The subscription list is read-only — you add and remove feeds in Nextcloud.
  There is no add-feed UI and never will be.
- **Not automatic.** Nothing is ever downloaded that you didn't ask for. No auto-download rules, no
  background triage, nothing queued by a sync or a refresh. You can tell it to fetch a whole
  podcast's worth of undecided episodes in one go — that's a command you issue, not a rule that runs
  behind you. Sync works the same way: there is no periodic pass, so state moves between the phone
  and Nextcloud when you pull to refresh, press *Sync now*, or make a decision — never on a timer.
- **Not a file manager.** Once a file lands in your folder it belongs to you and your player.
  Podsilo does not delete it, track it, or care whether it still exists.

Personal project, single user, no cloud service, no telemetry, no ads.

## Technologies

Kotlin, Jetpack Compose and Material 3 on the surface; Room, WorkManager, DataStore, Hilt and
Retrofit/OkHttp underneath. Feeds are parsed with
[rssparser](https://github.com/prof18/RSS-Parser), audio tags rewritten with the Android-compatible
[jaudiotagger fork](https://github.com/Kaned1as/jaudiotagger), and downloads written through the
Storage Access Framework so you can point them at an SD card. Both library choices deviate from the
first pick, and [`docs/architecture.md`](docs/architecture.md) §7 and §11 say why — as does the
decision to add no date-time library at all.

The codebase is split into small modules (`:core:model`, `:core:feed`, `:core:naming`,
`:core:download`, `:core:gpodder`, `:core:sync`, `:feature:*`) so the interesting logic — sync
reconciliation and filename generation above all — is plain-JVM testable without an emulator.

A guiding rule: **use existing libraries, don't invent replacements.** No hand-rolled XML parser, no
hand-written ID3 frames, no bespoke retry logic.

## Development

Development happens in a Docker dev container (JDK 17 + Android SDK) on Windows/WSL2. Tests come in
three tiers: JVM unit tests as the default and main workload, a headless emulator in the container
for instrumented runs, and a real device — over USB or wireless debugging — driven from the container
for everything a device has to answer for. Sync is tested against a disposable
[opodsync](https://codeberg.org/kd2/opodsync) server, never against a real Nextcloud.

Setup instructions are in [`docs/dev-environment.md`](docs/dev-environment.md), which is also honest
about which tiers have actually been run — all three have, and its status table says what each run
proved and what is still unverified. The module design, schema and sync semantics are in
[`docs/architecture.md`](docs/architecture.md); the whole of the UI — the screen design, the Compose
seam it binds to, and the brand mark — is in [`docs/UI.md`](docs/UI.md).

Five documents, and that is deliberate: `architecture.md` and `UI.md` are the two references,
[`docs/backlog.md`](docs/backlog.md) holds what is not being built, `docs/third-party.md` tracks
licences, and `docs/journal.md` keeps a running log — this project doubles as an experiment in
agent-driven development, so the process is recorded alongside the result. `docs/decisions/` keeps
only the decisions that still constrain the code and cannot be re-derived from the two references;
everything else was folded into the section that governs it.

## Licence

GPL-3.0-or-later.
