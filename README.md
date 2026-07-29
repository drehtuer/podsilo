# Podsilo

An Android **podcast catcher** that downloads episodes into a folder you control — and deliberately
does not play them.

Think of it as a silo: episodes flow in from your feeds, pool in a folder you picked, and are
consumed by whatever audio player you actually like.

> **Status: early.** This repository currently holds the project brief and agent instructions
> (`.claude/CLAUDE.md`). There is no working app yet.

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
- **Not automatic.** No auto-download rules, no "download all".
- **Not a file manager.** Once a file lands in your folder it belongs to you and your player.
  Podsilo does not delete it, track it, or care whether it still exists.

Personal project, single user, no cloud service, no telemetry, no ads.

## Technologies

Kotlin, Jetpack Compose and Material 3 on the surface; Room, WorkManager, DataStore, Hilt,
Retrofit/OkHttp and Paging 3 underneath. Feeds are parsed with [Stalla](https://github.com/mpetuska/stalla),
audio tags rewritten with [jaudiotagger](https://bitbucket.org/ijabz/jaudiotagger), and downloads
written through the Storage Access Framework so you can point them at an SD card.

The codebase is split into small modules (`:core:model`, `:core:feed`, `:core:naming`,
`:core:download`, `:core:gpodder`, `:core:sync`, `:feature:*`) so the interesting logic — sync
reconciliation and filename generation above all — is plain-JVM testable without an emulator.

A guiding rule: **use existing libraries, don't invent replacements.** No hand-rolled XML parser, no
hand-written ID3 frames, no bespoke retry logic.

## Development

Development happens in a Docker dev container (JDK 17 + Android SDK) on Windows/WSL2. Tests come in
three tiers: JVM unit tests as the default and main workload, a headless emulator in the container
for instrumented runs, and an emulator on the Windows host driven over TCP for interactive UI work.
Sync is tested against a disposable [opodsync](https://codeberg.org/kd2/opodsync) server, never
against a real Nextcloud.

Setup instructions will live in `docs/dev-environment.md`. Architecture decisions land in
`docs/decisions/`, and `docs/journal.md` keeps a running log — this project doubles as an experiment
in agent-driven development, so the process is recorded alongside the result.

## Licence

GPL-3.0-or-later.
