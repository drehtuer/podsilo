#!/usr/bin/env bash
# SPDX-License-Identifier: GPL-3.0-or-later
#
# Tier 3: point this container's adb at a real device attached outside it, and diagnose it when
# that does not work. The helper CLAUDE.md §4 asks for, "rather than making the author remember it".
#
# THE CONTAINER NEVER TOUCHES USB. It has no /dev/bus/usb, no usbip and no libusb access, and does
# not need any: adb is a client/server protocol over TCP, so the container runs only the *client*
# and the server that owns the device runs in WSL (or on Windows). That is why `linux-tools-virtual`
# and `hwdata` belong in the WSL distro, where usbipd-win delivers the device, and not in this
# image — see docs/dev-environment.md §9.
#
# Read-only and side-effect free by design: it will not start an adb server, because the one thing
# that reliably breaks this setup is a server started on the *container* side (see below).
set -euo pipefail

PORT="${ADB_SERVER_PORT:-5037}"

say()  { printf '%s\n' "$*"; }
fail() { printf '\033[1;31m%s\033[0m\n' "$*" >&2; }
note() { printf '\033[1;34m==> %s\033[0m\n' "$*"; }

# A plain TCP connect, deliberately not `adb devices`: every adb *client* command silently starts a
# local server when none answers, and on this side that server would be blind to USB — which is the
# exact failure this script exists to prevent, so it must not cause it while looking for it.
listening() {
    (exec 3<>"/dev/tcp/$1/$2") 2>/dev/null
}

# --- Where should the server be? ------------------------------------------------------------------
# With --network=host (devcontainer.json) the container shares the host's network namespace, so
# WSL's own adb server on 127.0.0.1:5037 is simply *there* and needs no configuration at all.
# Under ordinary bridge networking it does not exist here, and the host has to be addressed by IP —
# which is what ADB_SERVER_SOCKET is for.
if [ -n "${ADB_SERVER_SOCKET:-}" ]; then
    host="${ADB_SERVER_SOCKET#tcp:}"; host="${host%:*}"
    note "Using ADB_SERVER_SOCKET=$ADB_SERVER_SOCKET"
else
    host="127.0.0.1"
fi

# --- The failure mode worth defending against -----------------------------------------------------
# If `adb devices` is ever run in this container while no server is listening, adb starts one HERE.
# It then holds 127.0.0.1:5037 in the shared host namespace and answers for WSL too — with an empty
# device list, because it cannot see USB. The symptom is "my device disappeared from WSL as well",
# which looks like a cable or a phone problem and is neither.
if pgrep -x adb >/dev/null 2>&1; then
    fail "An adb server is running INSIDE this container (pid $(pgrep -x adb | tr '\n' ' '))."
    say  ""
    say  "  It cannot see USB — this container has no /dev/bus/usb — and because the network"
    say  "  namespace is shared it is also answering for WSL, so both sides report no devices."
    say  ""
    say  "  Fix, in this order:"
    say  "      adb kill-server            # here"
    say  "      adb devices                # in WSL, which starts a server that owns the device"
    say  "      $0                         # here again"
    exit 1
fi

# --- Is anything answering? -----------------------------------------------------------------------
if ! listening "$host" "$PORT"; then
    fail "No adb server is listening on $host:$PORT."
    say  ""
    say  "  Nothing is wrong in here — start the server on the side that owns the device."
    say  ""
    say  "  From WSL (the usual path; any adb command starts it):"
    say  "      adb devices"
    say  "  If the phone is not listed there either, it has not been passed through yet."
    say  "  On Windows, in an elevated prompt:"
    say  "      usbipd list"
    say  "      usbipd bind   --busid <busid>     # once per device, persists"
    say  "      usbipd attach --wsl --busid <busid>"
    say  ""
    say  "  Or from a Windows-side adb server instead of WSL's:"
    say  "      # Windows:   adb -a -P 5037 nodaemon server"
    say  "      # here:      export ADB_SERVER_SOCKET=tcp:\$(ip route show default | awk '{print \$3}'):5037"
    exit 1
fi

note "adb server answering on $host:$PORT"

# --- Version skew ---------------------------------------------------------------------------------
# CLAUDE.md §4: the client and server must be the same platform-tools build. When they are not, the
# client KILLS the running server and starts its own — which, from in here, is precisely the
# USB-blind server described above. So a mismatch does not fail loudly; it fails as "no devices".
say "  client here: $(adb version | sed -n '2p')"
say "  (the server must be the same platform-tools build — a mismatch makes this client kill it"
say "   and start a USB-blind one in its place, which reads as 'no devices attached')"

# --- What is attached -----------------------------------------------------------------------------
say ""
devices="$(adb devices -l | tail -n +2 | grep -v '^[[:space:]]*$' || true)"
if [ -z "$devices" ]; then
    fail "The server is up but no devices are attached."
    say  ""
    say  "  Check in WSL first — if 'adb devices' is empty there too, the phone is not passed"
    say  "  through (usbipd attach), is locked, or has not authorised this computer for USB"
    say  "  debugging. Watch the phone's screen for the RSA fingerprint prompt."
    exit 1
fi

note "Attached"
say "$devices"
say ""
say "Run instrumented tests against it with:"
say "    ./gradlew connectedDebugAndroidTest"
