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
9. [Version reference](#9-version-reference)

---

## 1. What works, and what has never been run

Read this first. CLAUDE.md §9 asks for honesty about state over confident claims, and the tiers
below differ enormously in how well-proven they are.

| Capability | Status | Evidence |
|---|---|---|
| Dev container builds and starts | ✅ Verified | Repeatedly, incl. 2026-07-31 |
| Android SDK provisioning (`post-create.sh`) | ✅ Verified | Idempotent, installs into the named volume |
| Portability across hosts with different UID/GID | ✅ Verified | 2026-07-31: second machine, uid 1002 / docker gid 108 — see [§4](#4-host-uidgid-portability) |
| **Tier 1 — `./gradlew ktlintCheck detekt test`** | ✅ **Verified green** | 2026-08-01, after Tier 4c's foundations: 339 tests, 3 skipped, exit 0 |
| `./gradlew assembleDebug` | ✅ Verified | 29 MB debug APK |
| opodsync test sync server | ✅ Verified | 0.5.3, boots + serves the API + integration test green (3 tests, 0 skipped) |
| `docker` from inside the container | ✅ Verified | Host daemon, group aligned at runtime by `post-create.sh` |
| `gh` (GitHub CLI) | ✅ Verified | 2.97.0, upstream release tarball |
| `/dev/kvm` usable in-container | ✅ Verified | `emulator -accel-check` → "KVM (version 12) is installed and usable" |
| **Tier 2 — emulator booting in-container** | ❌ **Never run** | No AVD has ever booted; see [§6](#6-testing-tiers) |
| **Tier 2 — `connectedAndroidTest`** | ❌ **Never run** | Follows from the above |
| **Tier 3 — adb over TCP to a Windows emulator** | ❌ **Never run** | No `scripts/adb-connect-host.sh` exists |
| `KeystoreAppPasswordCipher` round-trip | ❌ Never run | Needs a real device/emulator (ADR 0010) |
| `SafDownloadTarget` (the actual SAF write) | ❌ Never run | Needs a real `DocumentsProvider` (ADR 0011) |
| The app actually running on a device | ❌ Never run | Tier 4b builds an APK; nothing has installed or launched it |

**In short: Tier 1 is the supported path today.** It is also where CLAUDE.md §4 says the majority of
tests must live, so this is not as limiting as it sounds — but do not assume Tiers 2 and 3 work
because they are described here. They are described because they are specified, not because they
have been proven.

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

### Tier 2 — headless emulator in the container ❌ never run

Everything *underneath* the emulator is verified: `/dev/kvm` is present and accessible to the
container user, and the emulator's own probe agrees —

```
$ emulator -accel-check
accel:
0
KVM (version 12) is installed and usable.
```

But **no AVD has ever been booted, and no `connectedAndroidTest` has ever run.** There is currently
an AVD directory at `~/.android/avd/podsilo-test.avd` which `avdmanager` refuses to load:

```
$ avdmanager list avd
The following Android Virtual Devices could not be loaded:
    Name: podsilo-test
   Error: Missing system image for Google APIs x86_64 podsilo-test.
```

The system image *is* installed (`system-images;android-35;google_apis;x86_64`), and the AVD's
`config.ini` contains placeholder values (`avd.name = <build>`), so this looks like a partially
created AVD rather than a missing dependency. It has not been investigated. To start clean:

```bash
rm -rf ~/.android/avd/podsilo-test.avd ~/.android/avd/podsilo-test.ini
avdmanager create avd -n podsilo-test -k "system-images;android-35;google_apis;x86_64" -d pixel_6
emulator -avd podsilo-test -no-window -no-audio -gpu swiftshader_indirect -no-snapshot -no-boot-anim &
adb wait-for-device
# Then poll properly — never a fixed sleep:
until [ "$(adb shell getprop sys.boot_completed 2>/dev/null | tr -d '\r')" = "1" ]; do sleep 2; done
```

Expect a nested-virtualisation warning and poor performance; that is normal in WSL2 and is exactly
why CLAUDE.md §4 makes Tier 2 a convenience rather than the main workflow. If it cannot be made
reliable here, the supported path stays Tier 1 + Tier 3 and CI keeps no emulator job — which is
currently the case.

### Tier 3 — emulator on the Windows host, driven from the container ❌ never run

The recommended path for interactive UI work once `:feature:*` exists, because the emulator runs
natively on Windows with WHPX acceleration (full speed, real window, no nested virtualisation).

Intended shape, **entirely untested**:

- On Windows: `adb -a -P 5037 nodaemon server` so the adb server listens on all interfaces.
- In the container: `export ADB_SERVER_SOCKET=tcp:<windows-host-ip>:5037`.
- With WSL2 `networkingMode=mirrored`, `localhost` reaches the Windows host directly; otherwise
  resolve the host IP from `ip route show default`.
- **adb versions must match** between Windows and the container or the handshake fails confusingly.
  The container currently has **platform-tools 37.0.1 / adb 1.0.41**; pin the Windows side to match.

CLAUDE.md §4 asks for a `scripts/adb-connect-host.sh` helper handling both networking modes.
**It does not exist yet.** There is no `scripts/` directory at all.

---

## 7. The opodsync test sync server

A disposable GPodder sync server, so sync is never tested against the author's real Nextcloud
(CLAUDE.md §4). ✅ Verified working 2026-07-31 with **opodsync 0.5.3**.

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

## 9. Version reference

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
| System image | `android-35;google_apis;x86_64` | Deliberately still 35; Tier 2 is unproven anyway |
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
