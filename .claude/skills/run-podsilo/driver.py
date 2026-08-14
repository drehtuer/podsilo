#!/usr/bin/env python3
# SPDX-License-Identifier: GPL-3.0-or-later
"""
Podsilo run-driver — build, install, seed and *drive* the app on an emulator or device.

Agent tooling, not product surface: this is how a future agent (or the author) gets from a clean
container to a screenshot of a real screen with real rows in it, without a Nextcloud account and
without waiting on `connectedAndroidTest`.

Everything goes through adb. Nothing here belongs in the app, and nothing in the app depends on it.

    python3 .claude/skills/run-podsilo/driver.py up          # boot + build + install + seed + launch
    python3 .claude/skills/run-podsilo/driver.py shot home
    python3 .claude/skills/run-podsilo/driver.py dump
    python3 .claude/skills/run-podsilo/driver.py tap "Der Test Podcast"

Run it from the repository root (it resolves the repo from its own location, so any cwd works).
"""

from __future__ import annotations

import argparse
import os
import re
import struct
import subprocess
import sys
import time
from pathlib import Path

PKG = "net.drehtuer.podsilo"
ACTIVITY = f"{PKG}/.MainActivity"
DB = "databases/podsilo.db"
# core/datastore/SettingsDataStore.kt: SETTINGS_DATASTORE_NAME + androidx's own ".preferences_pb".
DATASTORE = "files/datastore/podsilo_settings.preferences_pb"

REPO = Path(__file__).resolve().parents[3]
SHOTS = REPO / "build" / "run-podsilo"


# --------------------------------------------------------------------------------------- plumbing


def sh(cmd: list[str], check: bool = True, capture: bool = True, timeout: int = 900) -> str:
    """Run a command, return stdout. Prints the command so a transcript shows what was driven."""
    print(f"$ {' '.join(cmd)}", file=sys.stderr)
    proc = subprocess.run(
        cmd,
        check=False,
        capture_output=capture,
        text=True,
        cwd=REPO,
        timeout=timeout,
    )
    out = (proc.stdout or "") if capture else ""
    if check and proc.returncode != 0:
        sys.stderr.write(out + (proc.stderr or ""))
        raise SystemExit(f"command failed ({proc.returncode}): {' '.join(cmd)}")
    return out


def adb(*args: str, check: bool = True, timeout: int = 300) -> str:
    return sh(["adb", *args], check=check, timeout=timeout)


def shell(cmd: str, check: bool = True) -> str:
    return adb("shell", cmd, check=check)


def device_ready() -> bool:
    return "device" in [
        line.split("\t")[1].strip()
        for line in adb("devices", check=False).splitlines()[1:]
        if "\t" in line
    ]


# ------------------------------------------------------------------------------- lifecycle: up


def cmd_boot(_args) -> None:
    """Boot the headless AVD. Idempotent — the repo's own script waits on sys.boot_completed."""
    if device_ready():
        print("emulator already attached")
        return
    sh(["./scripts/emulator-start.sh"], capture=False, timeout=900)


def apk_path() -> Path:
    apks = sorted((REPO / "app/build/outputs/apk/debug").glob("*-debug.apk"))
    if not apks:
        raise SystemExit("no debug APK — run `driver.py build` first")
    return apks[-1]


def cmd_build(_args) -> None:
    sh(["./gradlew", "--console=plain", ":app:assembleDebug"], capture=False, timeout=2400)


def cmd_install(_args) -> None:
    # -r replaces, -t allows the test-only flag some debug builds carry.
    adb("install", "-r", "-t", str(apk_path()), timeout=600)


def cmd_launch(_args) -> None:
    shell(f"am start -n {ACTIVITY}")
    time.sleep(3)


def cmd_stop(_args) -> None:
    shell(f"am force-stop {PKG}")


def cmd_restart(_args) -> None:
    """Room's invalidation tracker is per-process: after `seed` the app must be restarted to see it."""
    shell(f"am force-stop {PKG}")
    time.sleep(1)
    shell(f"am start -n {ACTIVITY}")
    time.sleep(3)


def cmd_reset(_args) -> None:
    """Wipe app data — ledger included. Fine on the emulator, never do this on the author's phone."""
    shell(f"pm clear {PKG}")


# ------------------------------------------------------------------------------------ observing


def cmd_shot(args) -> None:
    SHOTS.mkdir(parents=True, exist_ok=True)
    dest = SHOTS / f"{args.name}.png"
    with dest.open("wb") as fh:
        print("$ adb exec-out screencap -p", file=sys.stderr)
        proc = subprocess.run(["adb", "exec-out", "screencap", "-p"], stdout=fh, timeout=120)
    if proc.returncode != 0 or dest.stat().st_size == 0:
        raise SystemExit("screencap failed")
    print(dest)


NODE_RE = re.compile(r"<node [^>]*/?>")
ATTR_RE = re.compile(r'(\w[\w-]*)="([^"]*)"')


def ui_nodes() -> list[dict[str, str]]:
    """Parse a uiautomator dump. Compose renders as one AndroidComposeView whose semantics land in
    `text` / `content-desc` on generic android.view.View nodes — so match on those, not on ids."""
    shell("uiautomator dump /sdcard/podsilo-ui.xml", check=False)
    xml = adb("exec-out", "cat", "/sdcard/podsilo-ui.xml")
    return [dict(ATTR_RE.findall(m.group(0))) for m in NODE_RE.finditer(xml)]


def label(node: dict[str, str]) -> str:
    return node.get("text") or node.get("content-desc") or ""


def cmd_dump(args) -> None:
    for node in ui_nodes():
        text = label(node)
        if not text:
            continue
        if args.grep and args.grep.lower() not in text.lower():
            continue
        print(f"{node.get('bounds', '')}\t{'[clickable]' if node.get('clickable') == 'true' else ''}\t{text}")


def centre(bounds: str) -> tuple[int, int]:
    x1, y1, x2, y2 = (int(v) for v in re.findall(r"-?\d+", bounds))
    return (x1 + x2) // 2, (y1 + y2) // 2


def find(needle: str, exact: bool = False) -> dict[str, str]:
    matches = [
        n
        for n in ui_nodes()
        if (label(n) == needle if exact else needle.lower() in label(n).lower())
    ]
    if not matches:
        raise SystemExit(f"no node matching {needle!r} — run `driver.py dump` to see what is there")
    # Prefer the smallest match: the label itself rather than a container that happens to contain it.
    matches.sort(key=lambda n: abs(len(label(n)) - len(needle)))
    return matches[0]


# ------------------------------------------------------------------------------------- driving


def cmd_tap(args) -> None:
    node = find(args.text, exact=args.exact)
    x, y = centre(node["bounds"])
    print(f"tapping {label(node)!r} at {x},{y}")
    shell(f"input tap {x} {y}")
    time.sleep(args.settle)


def cmd_tapxy(args) -> None:
    shell(f"input tap {args.x} {args.y}")
    time.sleep(args.settle)


def cmd_type(args) -> None:
    # `input text` takes no spaces; %s is the documented stand-in.
    shell("input text " + args.text.replace(" ", "%s"))


def cmd_key(args) -> None:
    shell(f"input keyevent {args.key}")
    time.sleep(0.5)


def cmd_swipe(args) -> None:
    shell(f"input swipe {args.x1} {args.y1} {args.x2} {args.y2} {args.ms}")
    time.sleep(1)


def cmd_logs(args) -> None:
    pid = shell(f"pidof {PKG}", check=False).strip()
    if pid:
        print(adb("logcat", "-d", "-t", str(args.lines), "--pid", pid))
    else:
        print(adb("logcat", "-d", "-t", str(args.lines), "-s", "Podsilo:*", "AndroidRuntime:E"))


def cmd_sql(args) -> None:
    """Query the app's own Room database. `run-as` works because the debug build is debuggable."""
    quoted = args.query.replace("'", "'\\''")
    print(shell(f"run-as {PKG} sqlite3 {DB} '{quoted}'", check=False))


# --------------------------------------------------------------------------------------- seeding


def _pb_varint(value: int) -> bytes:
    out = b""
    while True:
        byte = value & 0x7F
        value >>= 7
        out += bytes([byte | (0x80 if value else 0)])
        if not value:
            return out


def _pb_field(number: int, wire: int, payload: bytes) -> bytes:
    return _pb_varint((number << 3) | wire) + payload


def _pb_len(number: int, payload: bytes) -> bytes:
    return _pb_field(number, 2, _pb_varint(len(payload)) + payload)


def preferences_pb(values: dict[str, object]) -> bytes:
    """Hand-encode androidx.datastore's PreferencesProto.

    There is no CLI for this and no way to reach the real writer from outside the app process, so
    the wire format is written directly: PreferenceMap{ map<string, Value> preferences = 1 } with
    Value's oneof numbered bool=1, float=2, int32=3, int64=4, string=5, stringSet=6, double=7.
    """
    out = b""
    for key, value in values.items():
        if isinstance(value, bool):
            encoded = _pb_field(1, 0, _pb_varint(int(value)))
        elif isinstance(value, int):
            encoded = _pb_field(4, 0, _pb_varint(value))
        elif isinstance(value, float):
            encoded = _pb_field(2, 5, struct.pack("<f", value))
        else:
            encoded = _pb_len(5, str(value).encode())
        entry = _pb_len(1, key.encode()) + _pb_len(2, encoded)
        out += _pb_len(1, entry)
    return out


def _pb_read_varint(buf: bytes, i: int) -> tuple[int, int]:
    value = shift = 0
    while True:
        byte = buf[i]
        i += 1
        value |= (byte & 0x7F) << shift
        if not byte & 0x80:
            return value, i
        shift += 7


def parse_preferences_pb(raw: bytes) -> dict[str, object]:
    """Decode enough of PreferencesProto to *merge* rather than clobber.

    Worth the twenty lines: the download folder URI lives in this same file, and a `seed` that
    rewrote it from scratch would silently drop the SAF grant every time.
    """
    def fields(buf: bytes):
        i = 0
        while i < len(buf):
            tag, i = _pb_read_varint(buf, i)
            number, wire = tag >> 3, tag & 7
            if wire == 2:
                size, i = _pb_read_varint(buf, i)
                yield number, buf[i : i + size]
                i += size
            elif wire == 0:
                value, i = _pb_read_varint(buf, i)
                yield number, value
            elif wire == 5:
                yield number, buf[i : i + 4]
                i += 4
            else:  # 64-bit; unused by this schema, skipped for completeness
                yield number, buf[i : i + 8]
                i += 8

    out: dict[str, object] = {}
    for number, entry in fields(raw):
        if number != 1 or not isinstance(entry, bytes):
            continue
        key = None
        for sub_number, payload in fields(entry):
            if sub_number == 1:
                key = payload.decode()
            elif sub_number == 2 and key is not None:
                for value_number, value in fields(payload):
                    if value_number == 1:
                        out[key] = bool(value)
                    elif value_number in (3, 4):
                        out[key] = int(value)
                    elif value_number == 2:
                        out[key] = struct.unpack("<f", value)[0]
                    elif value_number == 5:
                        out[key] = value.decode()
    return out


def read_preferences() -> dict[str, object]:
    raw = subprocess.run(
        ["adb", "exec-out", f"run-as {PKG} cat {DATASTORE}"],
        capture_output=True,
        cwd=REPO,
        timeout=60,
    ).stdout
    return parse_preferences_pb(raw) if raw[:1] else {}


def push_private(local: Path, remote_rel: str) -> None:
    """adb push cannot write into an app's private dir, and `run-as cp /sdcard/...` fails too —
    the app uid cannot read the shell user's files under scoped storage. Piping works: the *device*
    shell reads /sdcard as `shell` and run-as only ever sees a stdin it inherited."""
    staged = f"/sdcard/{local.name}"
    adb("push", str(local), staged)
    shell(f"run-as {PKG} mkdir -p {os.path.dirname(remote_rel)}")
    shell(f"cat {staged} | run-as {PKG} sh -c 'cat > {remote_rel}'")
    shell(f"rm {staged}")


SEED_FEEDS = [
    ("https://example.org/regen.xml", "Der Regen-Podcast"),
    ("https://example.org/silo.xml", "Silo Stories"),
]


# The first episode of every seeded feed points at a real 100 KB file, so *one* download per feed
# can be driven for real — fetch, tag rewrite, SAF delivery — without pulling a 50 MB podcast. Every
# other episode points at example.org and fails, which is how the ERROR state gets exercised.
REAL_AUDIO_URL = "https://download.samplelib.com/mp3/sample-6s.mp3"


def seed_sql(feeds: int, episodes: int, audio_url: str) -> str:
    now = int(time.time() * 1000)
    day = 86_400_000
    lines = ["PRAGMA foreign_keys=ON;"]
    # Re-seeding replaces the previous seed rather than adding to it, so `--feeds 1` really does
    # leave one feed. Episodes cascade; ledger rows deliberately survive, exactly as they do when a
    # subscription disappears from the server (CLAUDE.md §5).
    for url, _ in SEED_FEEDS:
        lines.append(f"DELETE FROM feeds WHERE url='{url}';")
    for f_index, (url, title) in enumerate(SEED_FEEDS[:feeds]):
        lines.append(
            f"INSERT OR REPLACE INTO feeds (url,title,imageUrl,firstSeenAt,lastRefreshedAt) "
            f"VALUES ('{url}','{title}',NULL,{now - 30 * day},{now});"
        )
        for e_index in range(episodes):
            key = f"seed-{f_index}-{e_index}"
            # Umlauts on purpose: naming and Compose text both have form here (CLAUDE.md §6).
            name = f"Folge {e_index + 1}: Warum Hamburg immer regnet"
            enclosure = audio_url if e_index == 0 else f"https://example.org/{key}.mp3"
            lines.append(
                f"INSERT OR REPLACE INTO episodes "
                f"(episodeKey,feedUrl,guid,enclosureUrl,title,description,pubDate,durationMs,sizeBytes) "
                f"VALUES ('{key}','{url}','{key}','{enclosure}','{name}',"
                f"'<p>Schöne Grüße aus dem Silo.</p>',{now - e_index * day},{1800000 + e_index},{12_000_000});"
            )
    return "\n".join(lines) + "\n"


def cmd_seed(args) -> None:
    """Put the app into a state worth screenshotting: connected, with feeds and episodes.

    `configured` in PodcastListViewModel is "an account exists", and observeNextcloudAccount() only
    needs the server URL and username — the encrypted app password is read separately and its
    absence degrades to a null credential rather than an unconfigured app. So the podcast list, the
    episode list, the detail sheet and every settings screen are reachable with no server at all.
    Anything that actually talks to Nextcloud (sync, download) will fail, by design.
    """
    # The Room file only exists once the app has opened it: seeding straight after `pm clear` (or a
    # fresh install) would have sqlite3 create an empty database and every INSERT fail on a missing
    # table. So launch once, let it create the schema, then stop and write into it.
    if "No such file" in shell(f"run-as {PKG} ls {DB}", check=False) or not shell(
        f"run-as {PKG} ls {DB}", check=False
    ).strip():
        shell(f"am start -n {ACTIVITY}")
        time.sleep(6)
    shell(f"am force-stop {PKG}")
    time.sleep(1)

    if args.connected:
        merged = read_preferences()
        merged.update(
            {
                "nextcloud_server_url": "https://nextcloud.example.org",
                "nextcloud_username": "seed-user",
            }
        )
        prefs = preferences_pb(merged)
        local = SHOTS / "podsilo_settings.preferences_pb"
        SHOTS.mkdir(parents=True, exist_ok=True)
        local.write_bytes(prefs)
        push_private(local, DATASTORE)

    sql = SHOTS / "seed.sql"
    SHOTS.mkdir(parents=True, exist_ok=True)
    sql.write_text(seed_sql(args.feeds, args.episodes, args.audio_url))
    adb("push", str(sql), "/sdcard/podsilo-seed.sql")
    # Must be a pipe. `run-as ... sqlite3 db < /sdcard/file` exits 0 and does *nothing* — the
    # redirected stdin does not survive into the run-as'd process, so sqlite3 reads EOF and quits
    # happily. The pipe does survive.
    shell(f"cat /sdcard/podsilo-seed.sql | run-as {PKG} sqlite3 {DB}")
    shell("rm /sdcard/podsilo-seed.sql")
    feeds = shell(f"run-as {PKG} sqlite3 {DB} 'SELECT count(*) FROM feeds'").strip()
    eps = shell(f"run-as {PKG} sqlite3 {DB} 'SELECT count(*) FROM episodes'").strip()
    print(f"seeded: {feeds} feeds, {eps} episodes")


def tap_label(text: str, exact: bool = False, settle: float = 1.5) -> None:
    cmd_tap(argparse.Namespace(text=text, exact=exact, settle=settle))


def cmd_grant_folder(_args) -> None:
    """Drive the *real* SAF picker — DocumentsUI, not a stub — from the setup card.

    The grant cannot be faked: it is a persisted URI permission held by the system, not a
    preference. Without it the app shows "Downloads paused" and every download refuses to start,
    so this is the one manual-looking step that has to be scripted.
    """
    tap_label("Choose folder", settle=3)
    tap_label("USE THIS FOLDER", settle=2)
    tap_label("ALLOW", exact=True, settle=3)
    folder = read_preferences().get("download_folder_uri")
    if not folder:
        raise SystemExit("folder grant did not stick — `driver.py dump` to see where the picker is")
    print(f"granted: {folder}")


def cmd_smoke(args) -> None:
    """One command that proves the app works: seed, triage, download, check the ledger.

    Deliberately asserts against the database rather than the screen — the ledger row *is* the
    feature (CLAUDE.md §11), and a screenshot cannot tell a written row from a rendered one.
    """
    cmd_seed(args)
    cmd_launch(args)

    if not read_preferences().get("download_folder_uri"):
        cmd_grant_folder(args)

    # S2 → S3: triage one episode as played.
    tap_label(SEED_FEEDS[0][1])
    tap_label("Actions for Folge 1")
    tap_label("Mark as played")
    time.sleep(2)
    skipped = shell(
        f"run-as {PKG} sqlite3 {DB} \"SELECT state FROM episode_ledger WHERE episodeKey='seed-0-0'\""
    ).strip()
    assert skipped == "SKIPPED", f"expected SKIPPED, got {skipped!r}"

    # The other feed's first episode has a real enclosure: download it for real.
    cmd_key(argparse.Namespace(key="BACK"))
    tap_label(SEED_FEEDS[1][1])
    tap_label("Actions for Folge 1")
    tap_label("Download", exact=True)
    for _ in range(30):
        state = shell(
            f"run-as {PKG} sqlite3 {DB} \"SELECT state FROM episode_ledger WHERE episodeKey='seed-1-0'\""
        ).strip()
        if state in ("DOWNLOADED", "ERROR"):
            break
        time.sleep(2)
    assert state == "DOWNLOADED", f"download ended in {state!r} — `driver.py logs` for why"

    written = shell(
        f"run-as {PKG} sqlite3 {DB} \"SELECT writtenFileName FROM episode_ledger WHERE episodeKey='seed-1-0'\""
    ).strip()
    listing = shell(f"ls '/sdcard/Podcasts/{SEED_FEEDS[1][1]}'", check=False)
    assert written and written in listing, f"{written!r} not delivered into the folder: {listing!r}"

    cmd_shot(argparse.Namespace(name="smoke"))
    print(f"OK — skipped seed-0-0, downloaded {written!r}, both ledger rows written")


def cmd_up(args) -> None:
    cmd_boot(args)
    cmd_build(args)
    cmd_install(args)
    cmd_seed(args)
    cmd_launch(args)
    cmd_shot(argparse.Namespace(name="up"))


# ------------------------------------------------------------------------------------------ cli


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    sub = parser.add_subparsers(dest="cmd", required=True)

    def add(name: str, fn, help_text: str):
        p = sub.add_parser(name, help=help_text)
        p.set_defaults(func=fn)
        return p

    add("boot", cmd_boot, "boot the headless AVD (idempotent)")
    add("build", cmd_build, "./gradlew :app:assembleDebug")
    add("install", cmd_install, "install the debug APK")
    add("launch", cmd_launch, "start MainActivity")
    add("stop", cmd_stop, "force-stop the app")
    add("restart", cmd_restart, "force-stop then start (required after `seed`)")
    add("reset", cmd_reset, "pm clear — wipes app data including the ledger")

    p = add("shot", cmd_shot, "screenshot into build/run-podsilo/<name>.png")
    p.add_argument("name", nargs="?", default="screen")

    p = add("dump", cmd_dump, "print every labelled UI node with its bounds")
    p.add_argument("grep", nargs="?", default=None)

    p = add("tap", cmd_tap, "tap the node whose label matches TEXT")
    p.add_argument("text")
    p.add_argument("--exact", action="store_true")
    p.add_argument("--settle", type=float, default=1.5)

    p = add("tapxy", cmd_tapxy, "tap raw coordinates")
    p.add_argument("x", type=int)
    p.add_argument("y", type=int)
    p.add_argument("--settle", type=float, default=1.5)

    p = add("type", cmd_type, "type into the focused field")
    p.add_argument("text")

    p = add("key", cmd_key, "send a keyevent, e.g. BACK / ENTER / 4")
    p.add_argument("key")

    p = add("swipe", cmd_swipe, "swipe (scrolling, and the swipe-to-triage gestures)")
    for arg in ("x1", "y1", "x2", "y2"):
        p.add_argument(arg, type=int)
    p.add_argument("ms", nargs="?", type=int, default=300)

    p = add("logs", cmd_logs, "tail logcat for the app's pid")
    p.add_argument("lines", nargs="?", type=int, default=120)

    p = add("sql", cmd_sql, "run SQL against the app's Room database")
    p.add_argument("query")

    p = add("seed", cmd_seed, "seed an account + feeds + episodes, no server needed")
    p.add_argument("--feeds", type=int, default=2)
    p.add_argument("--episodes", type=int, default=8)
    p.add_argument("--no-connected", dest="connected", action="store_false")
    p.add_argument("--audio-url", default=REAL_AUDIO_URL)
    p.set_defaults(connected=True)

    add("grant-folder", cmd_grant_folder, "tap through the real SAF picker and take the grant")

    p = add("smoke", cmd_smoke, "seed + triage + a real download, asserted against the ledger")
    p.add_argument("--feeds", type=int, default=2)
    p.add_argument("--episodes", type=int, default=8)
    p.add_argument("--no-connected", dest="connected", action="store_false")
    p.add_argument("--audio-url", default=REAL_AUDIO_URL)
    p.set_defaults(connected=True)

    p = add("up", cmd_up, "boot + build + install + seed + launch + screenshot")
    p.add_argument("--feeds", type=int, default=2)
    p.add_argument("--episodes", type=int, default=8)
    p.add_argument("--no-connected", dest="connected", action="store_false")
    p.add_argument("--audio-url", default=REAL_AUDIO_URL)
    p.set_defaults(connected=True)

    args = parser.parse_args()
    args.func(args)


if __name__ == "__main__":
    main()
