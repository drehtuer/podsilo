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
