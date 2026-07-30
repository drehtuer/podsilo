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
#   SDK_PACKAGES="platform-tools platforms;android-36" bash .devcontainer/post-create.sh
: "${SDK_PACKAGES:=platform-tools platforms;android-35 build-tools;35.0.0 emulator system-images;android-35;google_apis;x86_64}"

# The system image is ~1.5 GB. Set to 0 to skip it if you only use Tier 1
# (JVM tests) and Tier 3 (emulator on the Windows host).
: "${INSTALL_SYSTEM_IMAGE:=1}"

# sdkmanager lives in /opt (in the image) while the SDK lives in $ANDROID_HOME (a
# volume), so it can never infer the root — always pass --sdk_root explicitly.
sdkm() { sdkmanager --sdk_root="${ANDROID_HOME}" "$@"; }

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
  warn "claude not on PATH — the image build step may have failed."
fi

# --- Gradle warm-up -------------------------------------------------------------
if [[ -x ./gradlew ]]; then
  log "Gradle wrapper warm-up"
  ./gradlew --version
else
  warn "No ./gradlew yet — skipping Gradle warm-up (expected until the Gradle skeleton lands)."
fi

log "Dev container ready"
