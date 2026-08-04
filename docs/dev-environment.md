<!-- SPDX-License-Identifier: GPL-3.0-or-later -->

# Development environment

How to get from a clean checkout to a green `./gradlew test`, and what each testing tier can and
cannot do on this project's target host (Windows 11 + WSL2 + Docker Engine).

Implements CLAUDE.md §4. Where this document and CLAUDE.md disagree, CLAUDE.md is the requirement
and this document records what was actually built — deviations are called out explicitly.

## Contents

1. [What works, and what has never been run](#1-what-works-and-what-has-never-been-run)
2. [Quick start](#2-quick-start)
3. [Host prerequisites](#3-host-prerequisites-windows--wsl2--docker)
4. [Host UID/GID portability](#4-host-uidgid-portability)
5. [Clean checkout to green tests](#5-clean-checkout-to-green-tests)
6. [Testing tiers](#6-testing-tiers)
7. [The opodsync test sync server](#7-the-opodsync-test-sync-server)
8. [Troubleshooting](#8-troubleshooting)
9. [Attaching a real Android device](#9-attaching-a-real-android-device)
10. [Version reference](#10-version-reference)

---

## 1. What works, and what has never been run

Read this first. CLAUDE.md §9 asks for honesty about state over confident claims, and the tiers
below differ enormously in how well-proven they are.

| Capability | Status | Evidence |
|---|---|---|
| Dev container builds and starts | ✅ Verified | Repeatedly, incl. 2026-07-31 |
| Android SDK provisioning (`post-create.sh`) | ✅ Verified | Idempotent, installs into the named volume |
| Portability across hosts with different UID/GID | ✅ Verified | 2026-07-31: second machine, uid 1002 / docker gid 108 — see [§4](#4-host-uidgid-portability) |
| **Tier 1 — `./gradlew ktlintCheck detekt test`** | ✅ **Verified green** | 2026-08-02, Tier 4c complete: 502 tests, 3 skipped, exit 0 |
| `./gradlew assembleDebug` | ✅ Verified | 29 MB debug APK |
| opodsync test sync server | ✅ Verified | 0.5.3, boots + serves the API + integration test green (3 tests, 0 skipped) |
| `docker` from inside the container | ✅ Verified | Host daemon, group aligned at runtime by `post-create.sh` |
| `gh` (GitHub CLI) | ✅ Verified | 2.97.0, upstream release tarball |
| `/dev/kvm` usable in-container | ✅ Verified | `emulator -accel-check` → "KVM (version 12) is installed and usable" |
| **Tier 2 — emulator booting in-container** | ✅ **Verified** | 2026-08-02: `scripts/emulator-start.sh` creates + boots `podsilo-ci` headless in ~28 s from nothing |
| **Tier 2 — `connectedAndroidTest`** | ✅ **Verified** | 2026-08-02: 6 tests green on `podsilo-ci(AVD) - 15` across `:app` and `:feature:episodes` |
| **Tier 3 — a real device over adb from the container** | ✅ **Verified** | 2026-08-02: a physical Pixel 5 passed into WSL with usbipd-win, visible in here with no image change — see [§9](#9-attaching-a-real-android-device) |
| **Tier 3 — adb to an emulator on *Windows*** | ❌ **Never run** | Same mechanism as the row above, but the Windows-side `adb -a -P 5037 nodaemon server` half is untested |
| **A real Nextcloud login from the phone** | ✅ **Verified** | 2026-08-02: Login Flow v2 approved in the phone's browser; 4 subscriptions and 9,565 episodes arrived |
| **The download pipeline end to end** | ✅ **Verified** | 2026-08-02: two real episodes fetched, tagged (TIT2/TPE1/TALB/TCON/TYER/COMM **and APIC**) and written through SAF on a Pixel 5 |
| **The foreground-service notification** | ✅ **Verified** | 2026-08-02 — after fixing the manifest crash it caused on API 34 (`docs/journal.md`) |
| **Backup / restore with real data** | ✅ **Verified** | 2026-08-02: 9,565 episodes round-tripped; a ledger row created after the export was correctly removed by the restore |
| **The device test set** | ◐ **Partly** | 2026-08-03: 41 declared, **35 executed, 6 skipped**. `SafDownloadTargetInstrumentedTest` opts out without a SAF grant — which the set's own uninstall removes — and `am instrument` reports those skips as `OK`. The script now fails on a skip rather than calling it green; see [§6](#6-testing-tiers) |
| `KeystoreAppPasswordCipher` round-trip | ✅ **Verified** | 2026-08-02: 6 instrumented tests green on `podsilo-ci(AVD)`, incl. a second instance decrypting the first's output (ADR 0010) |
| `SafDownloadTarget` (the actual SAF write) | ✅ **Verified** | 2026-08-02: 6 instrumented tests green; files confirmed on the emulator's filesystem, umlauts intact, retry overwrote (ADR 0011) |
| SAF grant via the real picker, surviving a restart | ✅ **Verified** | 2026-08-02: driven through S1's checklist; `dumpsys` shows `persistable=0x3 persisted=0x3` (CLAUDE.md §11) |
| **The app actually running on a device** | ✅ **Verified** | 2026-08-02: installed on the Tier 2 emulator and driven through all eight screens. Its first run found the ICU regex bug (`docs/decisions/0017`) |
| **A real Nextcloud (read)** | ✅ **Verified** | 2026-08-02: Login Flow v2, gpoddersync, subscriptions and 3,022 episode actions read from Nextcloud 33.0.5 (`docs/decisions/0009`) |
| **A real Nextcloud (write)** | ✅ **Verified** | 2026-08-02: on a dedicated test account — `DOWNLOAD` confirmed discarded (`docs/decisions/0008`), mark-as-played `PLAY` round-tripped intact (`docs/decisions/0002`) |
| **A full `SyncOrchestrator` pass on real data** | ✅ **Verified** | 2026-08-02: real subscriptions + a real episode — outbox push, the echo of our own action, and server-clock `since` all confirmed (`docs/journal.md`) |

**In short: Tier 1 is the everyday path, Tier 2 covers what cannot run headless, and Tier 3 works
with a real phone.** Tier 1 is where CLAUDE.md §4 says the majority of tests must live, and Tier 2 is
slow enough (≈28 s to boot, minutes per run) that it should stay reserved for what genuinely cannot
run headless. Tier 3 turned out to need **nothing added to the container** — see [§9](#9-attaching-a-real-android-device)
for why, and for the one failure mode that breaks it.

---

## 2. Quick start

Assuming the [host prerequisites](#3-host-prerequisites-windows--wsl2--docker) are already met:

```bash
git clone https://github.com/drehtuer/podsilo.git
cd podsilo
code .          # then: "Reopen in Container" when VS Code offers it
```

Or without VS Code:

```bash
npm i -g @devcontainers/cli     # if you don't have it
devcontainer up --workspace-folder .
devcontainer exec --workspace-folder . bash
```

Then, inside the container:

```bash
./gradlew ktlintCheck detekt test
```

That is the full Tier 1 acceptance check and the same sequence CI runs.

---

## 3. Host prerequisites (Windows + WSL2 + Docker)

The project targets **Windows 11 running WSL2, with the dev container inside the WSL2 distro**.
Nothing here assumes a native Linux host.

### 3.1 Required

- **Windows 11.** Only needed for the emulator tiers — the `nestedVirtualization` flag does not
  exist on Windows 10. Tier 1 works fine on Windows 10.
- **A WSL2 distro** (Ubuntu is what this was built against).
- **Docker Engine installed natively inside the WSL2 distro — not Docker Desktop.** Docker Desktop
  runs containers in its own utility VM which does not reliably expose `/dev/kvm`. If you are on
  Docker Desktop, check before relying on any emulator tier:

  ```bash
  docker run --rm --device /dev/kvm alpine ls -l /dev/kvm
  ```

### 3.2 For the in-container emulator (Tier 2) only

`%USERPROFILE%\.wslconfig` on the Windows side:

```ini
[wsl2]
nestedVirtualization=true
memory=12GB          # the emulator and the Gradle daemon are both hungry
processors=6
```

Then `wsl --shutdown` and restart the distro. Confirm inside the distro with `ls -l /dev/kvm`.

`/dev/kvm` comes up root-owned on each boot, so add to `/etc/wsl.conf` inside the distro:

```ini
[boot]
command = /bin/bash -c 'chown -v root:kvm /dev/kvm && chmod 660 /dev/kvm'
```

and add your user to the `kvm` group.

**On AMD CPUs**, nested virtualisation support has historically lagged Intel. Check rather than
assume:

```bash
grep -E 'vmx|svm' /proc/cpuinfo | head -1
```

This project's host is AMD and `/dev/kvm` works there, so AMD is not a blocker as such.

**Note:** if you move to WSL's newer container runtime (`wslc`), nested virtualisation is not
currently exposed to those containers at all (microsoft/WSL#40736). Stay on a WSL2 distro plus
Docker Engine.

### 3.3 Optional but assumed

- `~/.gitconfig` must **exist** on the host. `devcontainer.json` bind-mounts it read-only so commits
  from inside the container carry your identity. Docker silently creates a *directory* for a missing
  bind source, which git then ignores — so a missing file degrades confusingly rather than loudly.
  Delete that mount line if you have no host git config.

---

## 4. Host UID/GID portability

**You should not need to change anything here for a new machine.** This section explains why, since
the four host-specific build args in `.devcontainer/devcontainer.json` look like they need editing.

| Arg | Committed default | What it actually is on your host |
|---|---|---|
| `USER_UID` / `USER_GID` | `1000` | `id -u` / `id -g` |
| `KVM_GID` | `993` | `getent group kvm \| cut -d: -f3` |
| `DOCKER_GID` | `109` | `getent group docker \| cut -d: -f3` |

These are **best-effort defaults, not requirements**, because a build arg cannot know the host's IDs
in the general case, and two of the three things that depend on them are decided *after* the image
is built:

- The devcontainer CLI rewrites the container user's UID/GID at container start
  (`updateRemoteUserUID`, on by default) to match the host user — you can spot this in the image
  name, which gains a `-uid` suffix. It chowns `$HOME`, but **not** the named volumes, so
  `/opt/android-sdk` keeps whatever ownership the image gave it.
- `/dev/kvm` and the bind-mounted `/var/run/docker.sock` arrive carrying the **host's** GIDs,
  whatever those happen to be.

So `post-create.sh` repairs all of it at runtime, where the real IDs are knowable: it re-chowns the
persisted directories to the current user, and aligns the `kvm`/`docker` groups to whatever GID owns
those nodes (adding the user to an existing group if the GID is already taken, and never `chgrp`-ing
the socket itself — it is a bind mount, so that would change it on the host too).

This was not theoretical: the project moved from a `uid 1000` / `docker gid 109` machine to a
`uid 1002` / `docker gid 108` one, and every one of these broke at once. See
[§8.2](#82-android-sdk-install-skips-every-package) for the failure mode, which is much less obvious
than it sounds.

> **Group changes need a new shell.** `usermod -aG` only affects processes started afterwards, so
> `docker ps` will still fail in the terminal `post-create.sh` ran in. Open a new one (the script
> says so when it happens).

### `--device=/dev/kvm`

`devcontainer.json` passes this in `runArgs`. **If your host has no `/dev/kvm`, the container will
not start at all** — delete that line. Tiers 1 and 3 do not need it.

### `--network=host`

Also in `runArgs`, and in `build.options`. This was added because the host was on an IPv6-only
access point at the time (see [§8.1](#81-network-ipv6-only-or-half-broken-ipv4)) — Docker's default
bridge is IPv4-only, so bridged containers had no route out at all. It is harmless when IPv4 works,
and it has a useful side effect for Tier 3: the container's `localhost` is the WSL2 host's, so a
Windows-side adb server is reachable without a host-IP lookup.

Consequences worth knowing: no network isolation, no devcontainer port forwarding (anything the
container listens on binds straight onto the host), and no container-to-container DNS.

---

## 5. Clean checkout to green tests

CLAUDE.md §4 requires that following this document literally gets you to a green `./gradlew test`.

```bash
# 1. In the container, provision the Android SDK (runs automatically as postCreateCommand;
#    re-running it by hand is cheap and idempotent).
bash .devcontainer/post-create.sh

# 2. Lint, static analysis, and the Tier 1 unit tests.
./gradlew ktlintCheck detekt test
```

**Verified 2026-08-01** — `BUILD SUCCESSFUL`, exit 0:

| Module | Tests |
|---|---|
| `:core:naming` | 81 |
| `:core:feed` | 48 |
| `:core:sync` | 43 |
| `:core:download` | 43 |
| `:core:database` | 39 |
| `:core:gpodder` | 36 (3 of them skipped — see below) |
| `:core:model` | 23 |
| `:feature:episodes` | 16 |
| `:core:datastore` | 7 |
| `:app` | 3 |
| **Total** | **339 discovered, 3 skipped, 336 executed** |

The 3 skips are `OpodsyncIntegrationTest`, which self-skips via JUnit's `assumeTrue` unless
`PODSILO_OPODSYNC_URL` is set. That is deliberate: CLAUDE.md §7 requires Tier 1 to be offline and
deterministic, so `./gradlew test` must never need a server running. See [§7](#7-the-opodsync-test-sync-server).

⚠️ **Caveat on the word "verified":** that run happened in an already-provisioned container with a
warm Gradle cache, not from a literally-fresh clone plus a from-scratch image build. Every
individual step here has worked at some point, but the whole sequence end-to-end on a virgin host
has not been timed or re-proven in one go. Expect a first run to spend a long time downloading the
Gradle distribution, the AGP/Kotlin toolchain, and Robolectric's `android-all` jar (~150 MB).

`./gradlew assembleDebug` additionally produces an installable debug APK (AGP auto-signs it with the
debug keystore; there is no release signing config).

---

## 6. Testing tiers

CLAUDE.md §4 defines three tiers. Their real status here differs sharply.

### Tier 1 — JVM unit tests ✅ supported

No emulator, no network. Room via in-memory DB, HTTP via MockWebServer, Android framework bits via
Robolectric. This is where CLAUDE.md §7 says the majority of tests must live, and where all 266
currently-executing tests are (see [§5](#5-clean-checkout-to-green-tests) for the per-module split).

Four modules need **Robolectric**:

- `:core:feed` — rssparser is a Kotlin Multiplatform library whose *Android* target resolves
  `org.xmlpull.v1.XmlPullParserFactory` at runtime (ADR 0005).
- `:core:database` — Room, obviously.
- `:core:download` and `:app` — WorkManager's `TestListenableWorkerBuilder` and the
  `ContentResolver` behind the SAF grant check both need an Android `Context`.

Each of those carries a `src/test/resources/robolectric.properties` pinning `sdk=34`: Robolectric
4.15.1 supports up to SDK 35, and the project's `compileSdk`/`targetSdk` is 37, which it refuses to
instrument. Without the pin the tests fail at *runner construction* with
`targetSdkVersion=37 > maxSdkVersion=35`, which reads like a build misconfiguration rather than a
tooling version gap.

Robolectric downloads an `android-all` jar on first use. That is still Tier 1 by CLAUDE.md §4's own
definition (headless, no emulator), just not dependency-free.

### Tier 2 — headless emulator in the container ✅ works

```bash
./scripts/emulator-start.sh                              # create if needed, boot, wait for the flag
./gradlew :feature:episodes:connectedDebugAndroidTest
adb emu kill                                             # when you are done
```

The script is idempotent, refuses to run without a writable `/dev/kvm` rather than degrading to an
unusably slow software emulator (CLAUDE.md §11), and polls `sys.boot_completed` instead of sleeping.
Boot takes ≈28 s on this host despite the nested-virtualisation warning.

**The failure this replaces, because the error message points at the wrong thing.** `avdmanager`
used to produce an AVD the emulator rejected with:

```
Error: Missing system image for Google APIs x86_64 podsilo-test.
```

The system image was installed all along. The cause is that the command-line tools live at
`/opt/android-cmdline-tools/latest/bin`, **outside** `ANDROID_HOME=/opt/android-sdk`, and
`avdmanager` infers the SDK root from its own location rather than from `ANDROID_HOME` — so it
decided the root was `/opt` and wrote a path relative to that:

```ini
image.sysdir.1 = android-sdk/system-images/android-35/google_apis/x86_64/   # → /opt/android-sdk/android-sdk/...
```

The same misinference is visible in the warnings it prints (`Observed package id 'emulator' in
inconsistent location '/opt/android-sdk/emulator' (Expected '/opt/emulator')`) — related to
[§8.5](#85-sdkmanager-could-not-determine-sdk-root). The script rewrites that one line after
creating the AVD; nothing else was wrong.

Tier 2 stays a convenience rather than the main workflow, per CLAUDE.md §4: it is minutes per run
against seconds for Tier 1, so use it for what genuinely needs a device — SAF, the Keystore cipher,
WorkManager — and keep everything else in Robolectric. **CI still runs no emulator job.**

### Tier 3 — a device or emulator outside the container ✅ works, with a real phone

**Verified 2026-08-02** against a physical Pixel 5 (`redfin`) passed into WSL with usbipd-win:

```
$ ./scripts/adb-connect-host.sh
==> adb server answering on 127.0.0.1:5037
==> Attached
08241FDD40014S   device usb:1-1 product:redfin model:Pixel_5 device:redfin transport_id:2
```

Nothing had to be added to the container image to make that work. See §9 below for why, and for the
one failure mode that can break it.

The same arrangement drives an emulator running natively on Windows (WHPX acceleration, full speed,
real window, no nested virtualisation), which remains the recommended path for interactive UI work:

- On Windows: `adb -a -P 5037 nodaemon server` so the adb server listens on all interfaces.
- In the container: `export ADB_SERVER_SOCKET=tcp:<windows-host-ip>:5037`.
- With WSL2 `networkingMode=mirrored`, `localhost` reaches the Windows host directly; otherwise
  resolve the host IP from `ip route show default`. `scripts/adb-connect-host.sh` handles both.
- **adb versions must match** between the server side and the container or the handshake fails
  confusingly — see §9.3. The container has **platform-tools 37.0.1 / adb 1.0.41**.

That Windows-server variant is still **untested**; only the WSL-server variant above has been run.

### The device test set

```bash
./scripts/device-test.sh          # everything under src/androidTest/
./scripts/device-test.sh :app     # one module
```

⚠ **It uninstalls and reinstalls the app, so it wipes the app's data** — the Nextcloud login, the
SAF grant and the episode ledger. Downloaded files are untouched (they are in the user's folder, not
app storage). Export a backup from Settings first if the install holds anything worth keeping, and
expect to reconnect afterwards; restoring that backup is itself gated on being connected again
(`docs/decisions/0018`), which is the intended order.

**It never runs on CI, and that isolation is structural rather than a matter of tagging.**
`.github/workflows/ci.yml` runs `ktlintCheck`, `detekt`, `test` and `assembleDebug` — nothing else.
A test in `src/test/` runs on CI; a test in `src/androidTest/` runs only here. Do not add
`connectedAndroidTest` to the workflow: GitHub's runners have no device, so the job could only be
skipped, fail, or boot an emulator whose whole purpose is to *not* be the thing these tests check.

What the set covers, in rough order of what it has actually caught:

| Area | Class | What it exists for |
|---|---|---|
| Android-vs-JVM deviations | `AndroidDeviationsTest` | ICU regex strictness (ADR 0017), locale-sensitive case, astral code points, NFC, ICU date patterns |
| | `NamingOnAndroidTest` | `:core:naming` compiled by ICU — the module is pure JVM by design, so its own suite cannot reach this |
| | `RoomOnDeviceSqliteTest` | the schema and migrations on the phone's SQLite, not Robolectric's; pins that removing a feed keeps its ledger |
| Platform surfaces | `PlatformSurfacesTest` | the foreground-service type as installed, its permission, and that cleartext `http://` is refused |
| | `SafDownloadTargetInstrumentedTest` | the actual SAF write (ADR 0011) |
| | `KeystoreAppPasswordCipherTest` | the real Keystore round trip (ADR 0010) |
| UI conformance | `PodcastListConformanceTest` | S1 against `docs/UI.md` §4 / §12.5 / §17 / §18 |
| | `SettingsConformanceTest` | S4/S5 against §7/§8 — no password field, restore gating, the bulk preview |
| | `EpisodeListScreenInstrumentedTest` | S2 rows on a real Compose runtime |

**`:app` is run by `adb install` + `am instrument`, not by Gradle.** UTP's installer cannot place the
~58 MB app APK on a usbip-attached phone and fails with `ErrorCode: 2002` over a report reading
`tests="0" failures="0"`. Stale packages, install timeouts, permission flags and disabling UTP were
each ruled out by experiment; the script records all four. The library modules, which install only
their own small test APK, run through Gradle normally.

The UI conformance tests duplicate assertions that also exist under Robolectric. That is deliberate:
three of the bugs found on the author's phone were things a Robolectric render agreed with and a
device did not — an ICU regex, a manifest attribute, and a dependency that was never on the compile
classpath.

---

## 7. The opodsync test sync server

A disposable GPodder sync server, so **automated** sync tests never touch the author's real
Nextcloud (CLAUDE.md §4). ✅ Verified working 2026-07-31 with **opodsync 0.5.3**.

### The manual probe against a real Nextcloud

Separate from the above, and deliberately not a test:

```bash
./gradlew :core:gpodder:nextcloudProbe -Phost=cloud.example.org
```

Read-only by default. Writes are opt-in **and** name the account they may touch:

```bash
./gradlew :core:gpodder:nextcloudProbe -Phost=cloud.example.org -Pwrite=<loginName>
```

A login flow is approved by whoever is signed in to the browser, so without that guard a write pass
could post to a real account by accident. If a different account approves, the probe reports what it
saw and stops without writing. Use a **test account** — gpoddersync cannot delete an episode action,
so anything written stays.

It drives the **production** `RetrofitNextcloudLoginFlowClient` and `RetrofitGpodderClient` through
Login Flow v2, prints the URL for a human to approve in a browser, polls, verifies gpoddersync, and
then performs two `GET`s. **Read-only** — it never posts an episode action and never calls
`subscription_change/create`. The app password stays in memory: never printed, never written to
disk.

It is a `main`, not a ``, so JUnit never collects it and CLAUDE.md §7's "deterministic and
offline" rule is untouched. It exists because one thing the whole suite cannot prove is that the
client works against an actual server — see `docs/decisions/0009`'s verification section for what a
run of it settled.

```bash
cd .devcontainer
docker compose up -d opodsync
```

Because the dev container has the host's Docker socket bind-mounted (docker-outside-of-docker),
opodsync comes up as a **sibling** container on the host daemon, not a child. With `--network=host`
it is reachable at `http://localhost:8080` from inside the dev container with no host-IP lookup.

The entrypoint seeds a test user on first boot by calling opodsync's own `GPodder::subscribe()`, so
no manual registration is needed. Defaults are `podsilo` / `podsilo-test-password`; override via
`.env` (copy `.env.example`, which is gitignored — CLAUDE.md §4 forbids secrets in git).

Run the opt-in integration test:

```bash
PODSILO_OPODSYNC_URL=http://localhost:8080 \
  ./gradlew :core:gpodder:test --tests '*OpodsyncIntegrationTest*'
```

Reset to a clean server:

```bash
docker compose down -v
```

⚠️ **opodsync is not evidence about Nextcloud.** It *stores* `DOWNLOAD` actions; `nextcloud-gpodder`
≥ 3.13.3 silently discards them and returns HTTP 200 anyway (ADR 0008). A green run here therefore
says nothing about whether mark-on-download syncs cross-client on a real Nextcloud. The integration
test asserts this difference explicitly so it stays visible rather than being quietly assumed.

The heavier **full Nextcloud + `gpoddersync`** option that CLAUDE.md §4 mentions as an opt-in
`--profile nextcloud` is **deliberately not built** — far more setup overhead for occasional
verification, and CLAUDE.md itself ranks opodsync as the preferred default. The cost is that ADR
0008 stays source-read-only, permanently.

---

## 8. Troubleshooting

### 8.1 Network: IPv6-only, or half-broken IPv4

The single most expensive failure mode encountered on this project. Symptoms range from "the
devcontainer build fails at `apt-get update`" to "`sdkmanager` hangs for 20 minutes then blames the
mirror".

> **Resolved on the current machine (2026-07-31)** by moving to a different PC/network. The state
> is now the mirror image of the original problem: `curl -4` returns 302 and `curl -6` fails, so
> everything works and the IPv6 steering in `post-create.sh` correctly does nothing. Kept because
> the diagnostic method below is what matters, not which family happened to be broken.

**Probe reachability; never infer it from a routing table.** An IPv4 default route can be present
while IPv4 is completely dead:

```bash
curl -4 -sS -o /dev/null -w '%{http_code}\n' --max-time 10 https://dl.google.com
curl -6 -sS -o /dev/null -w '%{http_code}\n' --max-time 10 https://dl.google.com
```

Which hosts actually need IPv4:

| Host | Has IPv6 | Breaks without IPv4 |
|---|---|---|
| `archive.ubuntu.com`, `dl.google.com`, `repo.maven.apache.org` | yes | — |
| `downloads.claude.ai` | **no** | Claude Code cannot install (build with `--build-arg CLAUDE_CODE_VERSION=skip`) |
| `downloads.gradle-dn.com` (redirect target of `services.gradle.org`) | **no** | `./gradlew` cannot bootstrap |
| `github.com`, `codeload.github.com` | **no** | opodsync image cannot be built |

The JVM tries IPv4 first and does *not* fall back the way curl's happy-eyeballs does, which is why
`sdkmanager` stalls rather than failing fast. `post-create.sh` probes and sets
`_JAVA_OPTIONS=-Djava.net.preferIPv6Addresses=true` automatically when IPv4 is dead but IPv6 works.
Gradle needs the same export.

### 8.2 Android SDK install skips every package

Symptom — `post-create.sh` scrolls a licence, then:

```
Accept? (y/N): Skipping following packages as the license is not accepted:
...
The following packages can not be installed since their licenses ... were not accepted:
  emulator
  platform-tools
  ...
```

and the script dies at `adb: command not found`.

**This is a file-ownership problem wearing a licence problem's clothes.** `$ANDROID_HOME` is owned
by a UID that isn't yours (see [§4](#4-host-uidgid-portability)), so `sdkmanager --licenses` cannot
create `$ANDROID_HOME/licenses` — and it does not say so. It prints *"All SDK package licenses
accepted"* and exits **0** having written nothing. The install that follows then re-prompts on a
closed stdin, reads EOF, takes the `N` default, and skips everything.

`post-create.sh` now repairs the ownership before it starts and verifies the licence files exist
afterwards, so this should be self-healing. To confirm by hand:

```bash
ls -ld /opt/android-sdk        # should be owned by `id -u`:`id -g`
ls /opt/android-sdk/licenses   # should be non-empty
```

### 8.3 `docker ps` fails with a permission error

The `docker` group inside the container doesn't match the GID owning the bind-mounted
`/var/run/docker.sock`. `post-create.sh` aligns them automatically — but **group changes only apply
to new processes**, so open a fresh terminal after it runs. To check without one:

```bash
stat -c '%g' /var/run/docker.sock   # host's docker GID
id -G                               # must contain it
sg docker -c 'docker ps'            # test with the new group applied
```

### 8.4 `/dev/kvm` exists but isn't accessible

Same class of problem as 8.3, handled the same way — or `/etc/wsl.conf` isn't chowning the device on
boot. `post-create.sh` distinguishes "not present" from "present but inaccessible" and prints the fix
for each.

### 8.5 `sdkmanager: Could not determine SDK root`

`sdkmanager` lives in `/opt/android-cmdline-tools` (part of the image) while the SDK lives in
`$ANDROID_HOME` (a named volume), so the volume mount can never shadow it — but it also means
`sdkmanager` can never infer its root. **Every** invocation needs `--sdk_root="$ANDROID_HOME"`.
`post-create.sh` wraps this in an `sdkm()` shell function.

`avdmanager` has the same blind spot but **fails silently instead of loudly**: it writes an AVD whose
`image.sysdir.1` is relative to `/opt`, and the emulator then reports a *missing system image* that
is in fact installed. `scripts/emulator-start.sh` fixes the line after creation — see
[§6](#tier-2--headless-emulator-in-the-container--works).

### 8.6 ktlint and detekt disagree with each other

**Settled in Tier 4b — this should no longer bite.** Kept because the reasoning matters if anyone
reconsiders the config.

The ktlint Gradle plugin (14.x) and `detekt-formatting` 1.23.8 bundle **different ktlint versions
with different wrapping and indentation opinions**, so `./gradlew ktlintFormat` could produce code
that then failed `detekt`. For three tiers the workaround was to restructure the code by hand. That
stopped being possible with Hilt workers: ktlint 14 formats an annotated constructor
(`class W @AssistedInject constructor`) with the whole class body indented one level, which the
bundled older ktlint reports as wrong indentation — 358 findings on code `ktlintFormat` had just
produced, and no code shape satisfies both.

The settlement, in two places:

- `config/detekt/detekt.yml` turns off detekt's duplicate copies of the formatting rules
  (`Indentation`, `ParameterListWrapping`, `ArgumentListWrapping`, `Wrapping`,
  `MaximumLineLength`). **`ktlintCheck` is the single authority on formatting.** Everything detekt
  uniquely contributes — complexity, correctness, naming, its own `MaxLineLength` — stays on.
- `.editorconfig` sets `max_line_length = 120`, matching detekt's `MaxLineLength` default. Without
  it ktlint had no line limit at all and would happily join a wrapped expression into a
  130-character line that detekt then rejected.

Still true, and still good advice: prefer extracting a local variable over clever multiline
wrapping.

### 8.7 Robolectric can't download `android-all`

Needs network from the Gradle test worker specifically. On a healthy network this is automatic. If
the worker cannot reach Maven Central while `curl` can, pre-fetch the jar and run Robolectric in
offline mode — see the 2026-07-31 Tier 4a journal entry for the exact workaround used.

---

## 9. Attaching a real Android device

**Verified 2026-08-02 with a Pixel 5.** Short version: get the phone into WSL, then run
`./scripts/adb-connect-host.sh` in the container. There is nothing to install in here.

### 9.1 Why the container needs no USB support at all

This is worth stating plainly because the obvious assumption is the opposite one.

**adb is a client/server protocol over TCP.** Exactly one process — the *server* — opens the USB
device; every `adb` command you type is a *client* that connects to it on port 5037 and speaks a
text protocol. The client never touches USB.

So the split is:

| | Owns the USB device | Needs `usbip`, `hwdata`, `/dev/bus/usb`, udev rules |
|---|---|---|
| **Windows** (`usbipd-win`) | shares it over the network | n/a |
| **WSL2 distro** | yes — the adb **server** runs here | **yes**, this is where `linux-tools-virtual` + `hwdata` go |
| **This container** | no — only the adb **client** | **no** |

And the container reaches WSL's server for free: `devcontainer.json` runs with `--network=host`, so
the container shares the host's network namespace and WSL's `127.0.0.1:5037` *is* the container's
`127.0.0.1:5037`. No `ADB_SERVER_SOCKET`, no port forwarding, no configuration.

Confirmed inside the running container:

```
$ ls /dev/bus/usb
ls: cannot access '/dev/bus/usb': No such file or directory      # no USB in here at all
$ pgrep -x adb
                                                                  # no server in here either
$ adb devices -l
08241FDD40014S   device usb:1-1 product:redfin model:Pixel_5 …    # …and yet
```

Adding `linux-tools-virtual`, `hwdata` and a `/dev/bus/usb` mount to this image would give the
container a *second* way to claim the same device. It would need `--privileged` (usbip writes to
sysfs, mounted read-only in containers), a `plugdev` group aligned to WSL's GID, and udev rules —
and the reward would be two processes competing for one USB interface. It is not wired up, on
purpose.

### 9.2 One-time Windows and WSL setup

On Windows, in an **elevated** prompt ([usbipd-win](https://github.com/dorssel/usbipd-win)):

```powershell
usbipd list                          # find the phone's BUSID
usbipd bind   --busid <busid>        # once per device; persists across reboots
usbipd attach --wsl --busid <busid>  # after every replug
```

In the WSL distro, once:

```bash
sudo apt install linux-tools-virtual hwdata
sudo update-alternatives --install /usr/local/bin/usbip usbip \
    "$(ls /usr/lib/linux-tools/*/usbip | tail -n1)" 20
```

Then `adb devices` **in WSL** — which both starts the server and prompts the phone for its USB
debugging authorisation. Accept the RSA fingerprint on the phone's screen.

### 9.3 The one thing that breaks it

**Never let an adb client in the container run while no server is listening.** adb silently starts a
server when none answers, and a server started *here* is blind to USB — yet because the network
namespace is shared it also answers for WSL. Both sides then report no devices, which looks like a
cable or a phone fault and is neither.

The same trap has a second door: if the container's adb build differs from the server's, the client
**kills the working server** and starts its own USB-blind replacement. Keep the versions in step.

`scripts/adb-connect-host.sh` is built around both hazards — it probes port 5037 with a raw TCP
connect rather than an adb command, so it can never trigger the problem it reports, and it detects
an already-running container-local server with `pgrep`:

```
$ ./scripts/adb-connect-host.sh
An adb server is running INSIDE this container (pid 3763).
  Fix, in this order:
      adb kill-server            # here
      adb devices                # in WSL, which starts a server that owns the device
```

Recovery is always: `adb kill-server` in the container, then `adb devices` in WSL.

---

## 10. Builds, versioning and release signing

### What each build is

| Build | File | What it is |
|---|---|---|
| `./gradlew assembleDebug` | `app/build/outputs/apk/debug/podsilo-<version>-debug.apk` | Debuggable, unminified, ~58 MB. Signed with AGP's debug keystore, so it installs as-is. **This is the sideload build.** |
| `./gradlew assembleRelease` | `app/build/outputs/apk/release/podsilo-<version>.apk` | R8-minified and resource-shrunk, ~4.8 MB. Signed **only** if a keystore is configured (below); unsigned APKs cannot be installed. |

Both names come from `androidComponents.onVariants` in `app/build.gradle.kts`. AGP's default is
`app-debug.apk`, which names the *module* — useless as a release asset and indistinguishable between
versions in a downloads folder.

### Version numbers

- **`versionName`** (`0.1.0`) is set by hand in `app/build.gradle.kts`. It is the only number a human
  chooses.
- **`versionCode`** is `git rev-list --count HEAD`, evaluated at configuration time. It only ever
  grows, needs no state outside the repository, and is the same on CI and on a laptop for the same
  commit — which a CI run number would not be. A shallow clone has no history and falls back to `1`,
  which is why the CI checkout uses `fetch-depth: 0`.
- **`BuildConfig.BUILD_TIME`** and **`GIT_SHA`** are generated per build and shown in Settings →
  About → *Build*, so "is this the build I just installed?" is answerable on the phone. `versionName`
  alone cannot answer it: `0.1.0` stays `0.1.0` across every sideload of the day.

### Creating a release keystore

**The keystore is yours and is never committed** — `.gitignore` covers `*.jks` and
`keystore.properties`. Generate one once and keep it safe: losing it means no future build can
upgrade an installed app in place, because Android identifies an app by its signature.

```bash
keytool -genkeypair -v \
  -keystore podsilo-release.jks \
  -alias podsilo \
  -keyalg RSA -keysize 4096 -validity 10000 \
  -dname "CN=Podsilo, O=drehtuer, C=DE"
```

Then, in the repository root, create `keystore.properties`:

```properties
storeFile=/absolute/path/to/podsilo-release.jks
storePassword=…
keyAlias=podsilo
keyPassword=…
```

`./gradlew assembleRelease` now produces a signed APK. Without the file (or the environment variables
below) the release build still succeeds, unsigned — deliberately, so a release build can be inspected
without holding the key.

### Signing on CI

The workflow reads the same values from environment variables, populated from repository secrets:

| Secret | Value |
|---|---|
| `PODSILO_KEYSTORE_BASE64` | `base64 -w0 podsilo-release.jks` — see the warning below |
| `PODSILO_KEYSTORE_PASSWORD` | `storePassword` |
| `PODSILO_KEY_ALIAS` | `podsilo` |
| `PODSILO_KEY_PASSWORD` | `keyPassword` |

The keystore is decoded into `$RUNNER_TEMP` for the length of the job and dies with the runner.

> **Generate the base64 in WSL, not in PowerShell, and check for stray line endings.** The workflow
> strips whitespace before decoding, so wrapped output and CRLF both work now — but they did not
> before, and `base64: invalid input` is what a CRLF-containing secret looks like from the runner. It
> reads as "your signing key is broken" when the truth is "your paste had Windows newlines in it".
> `certutil -encode` is worse: it wraps the output in `-----BEGIN CERTIFICATE-----` lines that are
> themselves valid base64 characters, so it decodes to garbage rather than failing.

If the secret cannot be decoded the build does **not** fail — it warns and produces an unsigned
release APK, which the release job then refuses to attach. A broken secret costs you the release
asset, never the build.

**Until those secrets exist, a published release gets the debug APK only.** The release job checks
the APK for a signature block and refuses to attach an unsigned one — a file that downloads like a
real build and then refuses to install is worse than a missing file.

### Installing a release build over a debug one

You can't, directly. They are signed with different keys, so Android refuses the upgrade and the
install must uninstall first — **which erases the episode ledger, the Nextcloud login and the SAF
folder grant**. Export a backup from Settings first (`docs/decisions/0018`), and expect to reconnect
and re-grant afterwards.

---

## 11. Version reference

Verified inside the container on 2026-07-31.

| Component | Version | Note |
|---|---|---|
| Base image | `ubuntu:24.04` | Distro packages only; no devcontainer features, no third-party apt repos |
| JDK | OpenJDK **17.0.19** (Ubuntu) | **Deviates from CLAUDE.md §4**, which asks for Temurin — Temurin needs a third-party apt repo, against the "generic container" constraint. Full JDK, not `-headless`: AGP crunches PNGs via `java.awt`. |
| Gradle | **9.6.1** (wrapper only) | No system Gradle is installed, on purpose |
| AGP | 9.3.1 | AGP ≥ 9 has built-in Kotlin support — applying `org.jetbrains.kotlin.android` is now a hard error |
| Kotlin | 2.4.10 | |
| KSP | 2.3.10 | KSP2's decoupled line; no `2.4.10-x` build exists |
| `compileSdk` / `targetSdk` | **37** | Bumped from 35: current `androidx.core`/`androidx.activity` fail AAR metadata checks below 36 |
| Build tools | 37.0.0 | |
| Platform tools / adb | **37.0.1** / adb 1.0.41 | Match this on the Windows side for Tier 3 |
| `gh` (GitHub CLI) | **2.97.0** | Upstream release tarball pinned via the `GH_VERSION` build arg, not apt — noble only packages 2.45.0 (Feb 2024) |
| Emulator | 37.1.11 | |
| System image | `android-35;google_apis;x86_64` | API 35 while `compileSdk` is 37; the instrumented tests do not depend on 36+ behaviour |
| cmdline-tools | build 13114758 | |
| opodsync | **0.5.3** | Upstream's version line is `0.x` — there has never been a `1.x` |

Persisted as named Docker volumes so rebuilds don't re-download gigabytes or re-authenticate: the
Android SDK (`/opt/android-sdk`), `~/.gradle`, `~/.android`, Claude Code's config dir, and
`~/.config/gh` (so `gh auth login` survives a rebuild).

---

## See also

- `CLAUDE.md` §4 — the requirements this document implements
- `docs/journal.md` — how each of these problems was actually diagnosed, including the wrong turns
- `docs/architecture.md` — module structure and what depends on what
- `docs/decisions/` — ADRs, notably 0008 (Nextcloud discards `DOWNLOAD`) and 0009 (wire contract)
