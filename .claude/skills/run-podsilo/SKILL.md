---
name: run-podsilo
description: Build, install, launch, screenshot and drive the Podsilo Android app on the headless in-container emulator. Use when asked to run or start the app, take a screenshot of a screen, click through the UI, seed podcasts/episodes to look at, test a download end to end, or confirm a change works in the real app rather than only in tests.
---

# Running and driving Podsilo

Podsilo is an Android app, so "run it" means: boot the headless AVD, build and install the debug
APK, then poke it over adb. All of that is wrapped in **`.claude/skills/run-podsilo/driver.py`** —
a Python 3 CLI (stdlib only, no dependencies) that builds, installs, **seeds a connected account
with podcasts and episodes without any server**, taps by on-screen text, screenshots, and queries
the app's own Room database.

Paths below are relative to the repository root. `python3` is the only runtime needed — there is no
Node in this container.

## Prerequisites

Nothing to install: the dev container already carries JDK 17, the Android SDK at
`/opt/android-sdk`, a `podsilo-ci` AVD, and a writable `/dev/kvm`. Verify with:

```bash
ls -l /dev/kvm && adb devices
```

## Quick start

```bash
# boot + build + install + seed + launch + screenshot, from nothing
python3 .claude/skills/run-podsilo/driver.py up

# then look at it
python3 .claude/skills/run-podsilo/driver.py dump             # every label on screen, with bounds
python3 .claude/skills/run-podsilo/driver.py tap "Silo Stories"
python3 .claude/skills/run-podsilo/driver.py shot episode-list # → build/run-podsilo/episode-list.png
```

Screenshots land in `build/run-podsilo/`. **Open the PNG and look at it** — a blank or crashed
screen dumps and screenshots just fine.

## The smoke run — does the app actually work?

```bash
python3 .claude/skills/run-podsilo/driver.py smoke
```

Seeds, grants a download folder through the real SAF picker, marks one episode as played, downloads
another **for real** (100 KB over HTTPS → ID3 rewrite → SAF delivery), then asserts the ledger rows
and the delivered filename. Takes about a minute and prints:

```
OK — skipped seed-0-0, downloaded '20260814_Folge 1_ Warum Hamburg immer regnet.mp3', both ledger rows written
```

It asserts against `episode_ledger`, not against pixels — that row *is* the feature (CLAUDE.md §11).

## Commands

| Command | What it does |
|---|---|
| `up` | boot + build + install + seed + launch + screenshot |
| `boot` / `build` / `install` | individually; `boot` delegates to `scripts/emulator-start.sh` |
| `launch` / `stop` / `restart` / `reset` | `restart` after any `seed`; `reset` is `pm clear` |
| `seed [--feeds N] [--episodes N] [--no-connected]` | account + podcasts + episodes straight into the DB |
| `grant-folder` | taps through DocumentsUI and takes the persistable URI permission |
| `shot NAME` | `build/run-podsilo/NAME.png` |
| `dump [GREP]` | every labelled node: `bounds  [clickable]  text` |
| `tap TEXT [--exact]` | finds the node by label and taps its centre |
| `tapxy X Y`, `type TEXT`, `key BACK`, `swipe X1 Y1 X2 Y2 [MS]` | raw input |
| `sql "SELECT …"` | queries the app's Room DB via `run-as` |
| `logs [N]` | logcat for the app's pid |
| `smoke` | the scripted end-to-end flow above |

## What `seed` fakes, and what it cannot

The app gates its whole UI on being connected to Nextcloud, and this container has none. `seed`
writes the two DataStore keys that `observeNextcloudAccount()` actually reads — `nextcloud_server_url`
and `nextcloud_username` — by hand-encoding androidx's `preferences_pb` protobuf, then inserts feeds
and episodes straight into `podsilo.db` through `run-as`. That is enough for **every screen**: the
podcast list, the episode list and its four filters, the detail sheet, triage, Activity, Error log
and all of Settings.

It does **not** fake an app password, so anything that talks to a server is inert:

- *Sync now* logs `Worker result SUCCESS` and does nothing. Not a bug — there is no credential.
- Outbox rows stay `syncedToServer = 0` for ever. Expected; Activity shows "1 action pending".
- Pull-to-refresh fetches nothing (the feed URLs are `example.org`).

Re-seeding replaces the previous seed instead of piling on, so `--feeds 1 --episodes 3` really does
leave three episodes. Ledger rows survive it deliberately — same as when a subscription disappears
from the server.

Downloads *do* work: the first episode of each seeded feed points at a real 100 KB MP3
(`--audio-url` to change it), every other episode points at `example.org` and fails — which is how
you exercise the `ERROR` state and the failure UI.

## Human path

```bash
./gradlew :app:assembleDebug
adb install -r -t app/build/outputs/apk/debug/podsilo-0.6.0-debug.apk
adb shell am start -n net.drehtuer.podsilo/.MainActivity
```

Headless, so there is no window — you still need `shot`/`dump` to see anything.

## Tests

```bash
./gradlew test              # Tier 1, ~15 s warm, exit 0
```

`./scripts/device-test.sh` runs the Tier 3 instrumented set against whatever is attached — not run
in this session, and note it uninstalls the app first, which wipes the ledger.

## Gotchas

These all cost time to find. None of them are guessable.

- **`run-as PKG sqlite3 db < file.sql` exits 0 and does nothing.** The redirected stdin does not
  survive into the `run-as`'d process, so sqlite3 reads EOF and quits happily, reporting success.
  Pipe instead: `adb shell "cat /sdcard/x.sql | run-as PKG sqlite3 databases/podsilo.db"`.
- **`run-as PKG cp /sdcard/x …` fails with "Permission denied"** — the app uid cannot read the
  shell user's files under scoped storage. Same fix: `cat … | run-as PKG sh -c 'cat > dest'`.
- **Seeding before the app has ever run creates an empty database.** After `reset` or a first
  install, `databases/podsilo.db` does not exist and sqlite3 will happily create a schema-less one,
  failing every INSERT. `seed` launches the app once first for exactly this reason.
- **Room's invalidation tracker is per-process and never sees an external write.** After any
  `seed` or `sql` write, `restart` — the list will not update on its own.
- **Never rewrite the DataStore file wholesale.** `download_folder_uri` lives in it, so a fresh
  write drops the SAF grant silently. `seed` decodes the existing file and merges.
- **The SAF grant cannot be faked at all** — it is a persisted URI permission held by the system,
  not a preference. `grant-folder` taps through DocumentsUI ("Choose folder" → "USE THIS FOLDER" →
  "ALLOW"). `reset` loses it, and until it is re-granted every screen shows "Downloads paused".
- **Compose exposes no resource-ids.** The whole app is one `AndroidComposeView`; `uiautomator`
  surfaces semantics as `text` / `content-desc` on generic `android.view.View` nodes. Match on
  labels — the driver does. Overflow menus are reachable as `"Actions for <episode title>"`.
- **`adb shell` re-parses your quoting on the device.** `adb shell rm -rf "/sdcard/Podcasts/Silo
  Stories"` deletes `/sdcard/Podcasts/Silo` *and* `Stories`. Wrap the whole command instead:
  `adb shell "rm -rf '/sdcard/Podcasts/Silo Stories'"`. Getting this wrong left a stale file behind
  and the next download came out as `… (2).mp3` — the app's collision suffixing was right; the
  cleanup was not.
- **The emulator's DNS must be pinned or downloads fail intermittently.**
  `scripts/emulator-start.sh` already passes `-dns-server 8.8.8.8,1.1.1.1`; boot through it, not
  through a bare `emulator -avd`.
- **`pm clear` wipes the episode ledger.** Harmless here, never do it against the author's phone —
  that table is the one that must never be lost.

## Troubleshooting

| Symptom | Cause / fix |
|---|---|
| `no node matching 'X'` | The label is not on screen. `dump` first; the app may still be animating (raise `--settle`). |
| App shows "No subscriptions found" after seeding | Seed wrote nothing, or the app was not restarted. Check `driver.py sql "SELECT count(*) FROM feeds"`, then `restart`. |
| App shows the stacked logo and "Connect Nextcloud" | The account keys are missing — you ran `seed --no-connected`, or `reset` wiped the DataStore. |
| Download stays `QUEUED`, banner says "Downloads paused" | No folder grant. `driver.py grant-folder`. |
| Ledger row shows `ERROR` | Expected for every episode except the first of each feed — their URLs are `example.org`. `driver.py logs` confirms. |
| `screencap failed` / adb not found | Emulator not attached. `driver.py boot`; if `/dev/kvm` is unwritable the emulator script fails loudly and Tier 1 tests are the only path. |
