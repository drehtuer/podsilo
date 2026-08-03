#!/usr/bin/env bash
# SPDX-License-Identifier: GPL-3.0-or-later
#
# Tier 3 — the device test set. Everything under `src/androidTest/` across the project, run against
# a real phone (or an emulator, if that is what is attached).
#
# WHY THIS IS SEPARATE FROM CI, AND STAYS SEPARATE
#
# `.github/workflows/ci.yml` runs `ktlintCheck`, `detekt`, `test` and `assembleDebug` — and nothing
# else. `connectedDebugAndroidTest` is not in it and must not be added: GitHub's runners have no
# device, so the job could only ever be skipped, fail, or spin up an emulator whose whole purpose is
# to *not* be the thing these tests exist to check. The isolation is therefore structural rather
# than a matter of tagging: a test in `src/test/` runs on CI, a test in `src/androidTest/` runs here.
#
# WHAT THIS SET IS FOR
#
# Three things the JVM suite cannot reach, in rough order of how much they have actually cost:
#
#   1. **Android-vs-JVM behavioural deviations.** Regexes compiled by ICU rather than
#      `java.util.regex`, locale-sensitive case conversion, ICU date formatting, the device's own
#      SQLite. `docs/decisions/0017` exists because one of these shipped a bug past 437 green tests.
#   2. **Platform surfaces with no test double worth trusting** — SAF writes, the Keystore cipher,
#      a foreground service actually starting, the cleartext-traffic policy.
#   3. **UI conformance to `docs/UI.md` and `docs/UI_interface.md`** on a real Compose runtime.
#
# ⚠ THIS UNINSTALLS AND REINSTALLS THE APP, SO IT WIPES ITS DATA
#
# `connectedAndroidTest` installs the app and its test package, and an install that cannot replace
# the existing one uninstalls it first. On a phone that has been used for real that costs the
# Nextcloud login, the SAF folder grant, and — the part that matters — **the episode ledger**, which
# CLAUDE.md §5 calls the one table that must never be lost.
#
# Downloaded files are unaffected: they live in the user's own folder, not in app storage.
#
# So: export a backup from Settings first if the install holds anything you want to keep, and expect
# to reconnect Nextcloud afterwards. Restoring that backup is itself gated on being connected again
# (`docs/decisions/0018`), which is the intended order rather than an obstacle.
#
# USAGE
#
#     ./scripts/device-test.sh            # everything
#     ./scripts/device-test.sh :app       # one module
set -euo pipefail

cd "$(dirname "$0")/.."

# Reuse the connection check rather than restating it: it also refuses to start a container-local
# adb server, which is the failure that makes a working phone look unplugged (§9.3).
if ! ./scripts/adb-connect-host.sh >/dev/null 2>&1; then
    echo "No usable device. Run ./scripts/adb-connect-host.sh for the diagnosis." >&2
    exit 1
fi

device="$(adb devices | awk 'NR>1 && $2=="device" {print $1; exit}')"
echo "==> Device test set against ${device}"
echo "    (CI never runs these — see the header of this script)"
echo

if [ $# -gt 0 ]; then
    ./gradlew "$1:connectedDebugAndroidTest"
    exit
fi

# The library modules run through Gradle normally. They install only their own small test APK, and
# UTP handles that fine over this link.
./gradlew :core:database:connectedDebugAndroidTest \
    :core:datastore:connectedDebugAndroidTest \
    :feature:episodes:connectedDebugAndroidTest \
    :feature:settings:connectedDebugAndroidTest

# :app IS DELIBERATELY RUN BY HAND, AND THIS IS NOT PREMATURE CLEVERNESS.
#
# `:app:connectedDebugAndroidTest` cannot install on a usbip-attached phone: UTP's own installer
# fails on the ~58 MB app APK with `AndroidTestApkInstallerPlugin ErrorCode: 2002 — Failed to install
# APK`, producing a report that reads `tests="0" failures="0"` while Gradle announces "There were
# failing tests". Everything else about the run is fine — the same two APKs install in seconds with
# `adb install` using AGP's own flags (`-r -g`), and the tests then pass.
#
# Ruled out, each by experiment rather than by reading:
#   - a stale package         — clearing every podsilo package changed nothing
#   - AGP's install timeout   — `installation { timeOutInMs }` at 30 min made no difference
#   - the permission flags    — `adb install -r -g` of both APKs succeeds every time
#   - disabling UTP           — `useUnifiedTestPlatform=false` is deprecated and ignored in AGP 9
#
# So the install goes through adb and the run through `am instrument`, which is the same runner
# Gradle would have invoked. This is a workaround for one broken tool path on one transport, not a
# replacement for Gradle — hence it stays in this script rather than becoming a Gradle task.
echo
echo "==> :app via adb + am instrument (see the comment in this script for why)"
./gradlew :app:assembleDebug :app:assembleDebugAndroidTest -q

adb install -r -g app/build/outputs/apk/debug/app-debug.apk >/dev/null
adb install -r -g -t app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk >/dev/null

# `am instrument` exits 0 even when tests fail, so the summary line is what decides.
result="$(adb shell am instrument -w net.drehtuer.podsilo.test/androidx.test.runner.AndroidJUnitRunner 2>&1)"
echo "$result" | tail -20
if echo "$result" | grep -qE "^(FAILURES!!!|INSTRUMENTATION_CODE: 0)"; then
    echo "==> :app FAILED" >&2
    exit 1
fi

echo
echo "==> Reports:"
find . -path '*reports/androidTests/connected*' -name index.html -not -path '*/tmp/*' 2>/dev/null | sed 's|^|    |'

# TROUBLESHOOTING
#
# `Failed to install APK … ErrorCode: 2002` with "Starting 0 tests" is the UTP install failure
# described above — not a test result. It is why :app is run by hand here. If a future AGP fixes it,
# collapse this script back to a single `./gradlew connectedDebugAndroidTest`.
