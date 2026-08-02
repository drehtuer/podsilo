#!/usr/bin/env bash
# SPDX-License-Identifier: GPL-3.0-or-later
#
# Tier 2: create (if needed) and boot the headless AVD used by `connectedAndroidTest`.
#
# Idempotent: re-running it with the AVD already up is a no-op that just waits for the boot flag.
set -euo pipefail

AVD_NAME="${AVD_NAME:-podsilo-ci}"
SYSTEM_IMAGE="${SYSTEM_IMAGE:-system-images;android-35;google_apis;x86_64}"
BOOT_TIMEOUT_SECONDS="${BOOT_TIMEOUT_SECONDS:-300}"

: "${ANDROID_HOME:?ANDROID_HOME must be set (the dev container sets it to /opt/android-sdk)}"
AVD_DIR="${ANDROID_AVD_HOME:-$HOME/.android/avd}/${AVD_NAME}.avd"

# Fail loudly rather than degrading to a software-rendered emulator nobody can wait for
# (CLAUDE.md §11).
if [ ! -w /dev/kvm ]; then
    echo "ERROR: /dev/kvm is not writable by $(id -un)." >&2
    echo "       See docs/dev-environment.md §3.2 and §8.4 — the emulator needs nested" >&2
    echo "       virtualisation on a Windows 11 host, and Tier 1 works without it." >&2
    exit 1
fi

if [ ! -d "$AVD_DIR" ]; then
    echo "Creating AVD $AVD_NAME from $SYSTEM_IMAGE ..."
    echo no | avdmanager create avd -n "$AVD_NAME" -k "$SYSTEM_IMAGE" -d pixel_6 >/dev/null

    # avdmanager writes image.sysdir.1 relative to ANDROID_HOME's *parent* when the SDK lives in
    # /opt/android-sdk, producing /opt/android-sdk/android-sdk/system-images/... — which the
    # emulator reports as the thoroughly misleading "Missing system image for Google APIs x86_64".
    # Rewriting the path is the whole fix.
    sed -i 's|^image.sysdir.1=android-sdk/system-images/|image.sysdir.1=system-images/|' \
        "$AVD_DIR/config.ini"
fi

if adb shell true >/dev/null 2>&1; then
    echo "An emulator is already attached; skipping launch."
else
    echo "Booting $AVD_NAME (headless) ..."
    # shellcheck disable=SC2086
    emulator -avd "$AVD_NAME" \
        -no-window -no-audio -gpu swiftshader_indirect -no-snapshot -no-boot-anim \
        >/tmp/emulator-"$AVD_NAME".log 2>&1 &
    adb wait-for-device
fi

# Poll the boot flag; never a fixed sleep (CLAUDE.md §4).
deadline=$((SECONDS + BOOT_TIMEOUT_SECONDS))
until [ "$(adb shell getprop sys.boot_completed 2>/dev/null | tr -d '\r')" = "1" ]; do
    if [ "$SECONDS" -ge "$deadline" ]; then
        echo "ERROR: $AVD_NAME did not finish booting within ${BOOT_TIMEOUT_SECONDS}s." >&2
        echo "       Log: /tmp/emulator-$AVD_NAME.log" >&2
        exit 1
    fi
    sleep 2
done

echo "$AVD_NAME is booted. Run: ./gradlew :feature:episodes:connectedDebugAndroidTest"
