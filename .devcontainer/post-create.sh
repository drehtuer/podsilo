#!/usr/bin/env bash
# SPDX-License-Identifier: GPL-3.0-or-later
#
# Runs once after the dev container is created. Installs the Android SDK
# components into $ANDROID_HOME (a named volume, so this survives image
# rebuilds) and reports what the host can actually support.
#
# Idempotent: re-running it is cheap, sdkmanager skips what is already there.

set -euo pipefail

log()  { printf '\n\033[1;34m==> %s\033[0m\n' "$*"; }
warn() { printf '\n\033[1;33m!!  %s\033[0m\n' "$*"; }

# compileSdk / build-tools / system image. Override for a one-off, e.g.
#   SDK_PACKAGES="platform-tools platforms;android-38" bash .devcontainer/post-create.sh
#
# Kept in step with the Gradle project's compileSdk (gradle/libs.versions.toml
# / each module's build.gradle.kts). Bumped from 35 to 37.0 when the current
# stable androidx.activity/androidx.core releases started requiring compileSdk
# >= 36 to even resolve (AAR metadata check) — discovered building the hello
# world skeleton, not a pre-emptive bump. The emulator system image stays on
# android-35 for now; Tier 2 is out of scope for that change (see journal).
: "${SDK_PACKAGES:=platform-tools platforms;android-37.0 build-tools;37.0.0 emulator system-images;android-35;google_apis;x86_64}"

# The system image is ~1.5 GB. Set to 0 to skip it if you only use Tier 1
# (JVM tests) and Tier 3 (emulator on the Windows host).
: "${INSTALL_SYSTEM_IMAGE:=1}"

# sdkmanager lives in /opt (in the image) while the SDK lives in $ANDROID_HOME (a
# volume), so it can never infer the root — always pass --sdk_root explicitly.
sdkm() { sdkmanager --sdk_root="${ANDROID_HOME}" "$@"; }

# IPv6-only network? The JVM tries IPv4 first and does not fall back the way curl's
# happy-eyeballs does, so sdkmanager sits on IPv4 connect timeouts for tens of
# minutes and then reports "Failed to download any source lists! / IO exception
# while downloading manifest" — on a host where plain `curl https://dl.google.com`
# returns 200. Steer it to IPv6 instead of letting that look like a broken mirror.
#
# This probes reachability rather than checking for an IPv4 default route: on this
# WSL2 host the route can be present while IPv4 is entirely dead (the access point
# offers IPv6 only), and a route check happily reports "IPv4 fine" and hangs.
probe_url="https://dl.google.com/android/repository/repository2-3.xml"
if ! curl -4 -s -o /dev/null --connect-timeout 5 --max-time 15 "${probe_url}"; then
  if curl -6 -s -o /dev/null --connect-timeout 5 --max-time 15 "${probe_url}"; then
    warn "IPv4 cannot reach dl.google.com, IPv6 can — treating this network as IPv6-only."
    echo "  Pointing the JVM at IPv6 so sdkmanager does not stall on dead IPv4 routes."
    echo "  Gradle will need the same once the project exists:"
    echo "    export _JAVA_OPTIONS=-Djava.net.preferIPv6Addresses=true"
    export _JAVA_OPTIONS="${_JAVA_OPTIONS:+${_JAVA_OPTIONS} }-Djava.net.preferIPv6Addresses=true"
  else
    warn "Neither IPv4 nor IPv6 reaches dl.google.com — the SDK install below will fail."
    echo "  Check the host's connectivity before blaming this script."
  fi
fi

log "Toolchain"
java -version
echo "ANDROID_HOME=${ANDROID_HOME}"
sdkm --version

log "Accepting Android SDK licences"
# `yes` closes the pipe early once sdkmanager stops reading; that is expected.
yes | sdkm --licenses >/dev/null || true

packages=()
for pkg in ${SDK_PACKAGES}; do
  if [[ "${pkg}" == system-images* && "${INSTALL_SYSTEM_IMAGE}" != "1" ]]; then
    warn "Skipping ${pkg} (INSTALL_SYSTEM_IMAGE=0)"
    continue
  fi
  packages+=("${pkg}")
done

log "Installing SDK components: ${packages[*]}"
sdkm "${packages[@]}"

log "Installed"
sdkm --list_installed || true
adb version

# --- Virtualisation report ------------------------------------------------------
# Fail loudly rather than silently degrading to a software-rendered emulator that
# nobody can wait for.
log "Hardware virtualisation (Tier 2, emulator inside this container)"
if [[ -r /dev/kvm && -w /dev/kvm ]]; then
  echo "/dev/kvm is readable and writable — in-container emulator should work."
  echo "Expect a nested-virtualisation warning and reduced speed; that is normal in WSL2."
  # The authoritative check: the emulator's own probe, not just device permissions.
  command -v emulator >/dev/null && emulator -accel-check || true
elif [[ -e /dev/kvm ]]; then
  warn "/dev/kvm exists but is not accessible to $(id -un) (gid $(id -g))."
  echo "  Fix on the WSL2 host: getent group kvm   -> rebuild with KVM_GID=<that gid>"
  echo "  and ensure /etc/wsl.conf chowns /dev/kvm to root:kvm 0660 on boot."
else
  warn "/dev/kvm is not present in this container. Tier 2 (in-container emulator) is unavailable."
  echo "  Requires: Windows 11, nestedVirtualization=true in %USERPROFILE%\\.wslconfig,"
  echo "  Docker Engine inside the WSL2 distro (not Docker Desktop), and"
  echo "  \"runArgs\": [\"--device=/dev/kvm\"] in devcontainer.json."
  echo "  Tier 1 (./gradlew test) and Tier 3 (emulator on the Windows host) are unaffected."
fi

# --- Claude Code ----------------------------------------------------------------
log "Claude Code"
if command -v claude >/dev/null; then
  claude --version
  echo "config dir: ${CLAUDE_CONFIG_DIR:-$HOME/.claude} (named volume — survives a rebuild)"
  if [[ -n "${ANTHROPIC_API_KEY:-}" ]]; then
    echo "ANTHROPIC_API_KEY is set in the environment."
  elif [[ -f "${CLAUDE_CONFIG_DIR:-$HOME/.claude}/.credentials.json" ]]; then
    echo "Credentials present — no login needed."
  else
    warn "Not logged in yet. Run 'claude' and then '/login' once; it persists in the volume."
  fi
else
  warn "claude not on PATH — image built with CLAUDE_CODE_VERSION=skip, or the install failed."
  echo "  Install it here once the host has IPv4 (downloads.claude.ai has no IPv6):"
  echo "    curl -fsSL https://claude.ai/install.sh | bash"
fi

# --- Gradle warm-up -------------------------------------------------------------
# Non-fatal on purpose. A failed warm-up must not fail the whole postCreateCommand
# and leave VS Code reporting a broken container — everything above it has already
# succeeded, and the wrapper can be retried by hand.
if [[ -x ./gradlew ]]; then
  log "Gradle wrapper warm-up"
  if ! ./gradlew --version; then
    warn "Gradle wrapper download failed — the container is otherwise fine."
    echo "  On an IPv6-only network this is expected and not fixable from here:"
    echo "  services.gradle.org redirects to downloads.gradle-dn.com, which has no"
    echo "  AAAA record, so the distribution zip is unreachable over IPv6."
    echo "  Maven Central and dl.google.com are dual-stack, so once the wrapper's"
    echo "  distribution is cached in ~/.gradle, builds work again. Re-run ./gradlew"
    echo "  from a network with IPv4."
  fi
else
  warn "No ./gradlew yet — skipping Gradle warm-up (expected until the Gradle skeleton lands)."
fi

log "Dev container ready"
