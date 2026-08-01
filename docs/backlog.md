<!-- SPDX-License-Identifier: GPL-3.0-or-later -->

# Backlog

Where ideas go instead of into the code (CLAUDE.md §1/§9: "if you spot something worth doing that
isn't asked for, note it here and move on"). Nothing in this file is committed to, scheduled, or
implied. Anything that would touch a §1 non-goal stays a note here permanently unless the author
says otherwise.

This file was created empty-ish on 2026-08-01 during a documentation consistency pass — CLAUDE.md
had referenced it since the beginning and it had never existed, so anything that *should* have been
noted here before that date was instead either built, declined in conversation, or lost.

## Open items

- **A `scripts/adb-connect-host.sh` helper** for Tier 3 (emulator on the Windows host, driven from
  the container). CLAUDE.md §4 asks for it explicitly; `docs/dev-environment.md` §6 records that
  neither it nor Tier 3 exists yet. Worth writing the first time someone actually needs a device.
- **Instrumented tests for the two device-only pieces** — `KeystoreAppPasswordCipher` (ADR 0010) and
  `SafDownloadTarget` (ADR 0011). Both are currently verified only by running the app, which has
  never happened. This is the highest-value item here.
- **Paging 3 for the episode list.** CLAUDE.md §3/§5 mandate it for long lists; the UI contract
  currently says "paging or a keyed `LazyColumn`" (`docs/UI_interface.md` §14.3). A 500-episode feed
  under the `All` filter is the case that decides it — measure before adding the dependency.
- **Full Nextcloud + `gpoddersync` as an opt-in compose profile.** CLAUDE.md §4 offers it as an
  option; `docs/dev-environment.md` §7 records the deliberate decision not to build it. The cost is
  that ADR 0008 stays source-read-only, permanently.

## Declined, with reasons

- **"Download all visible" as a prominent button.** CLAUDE.md §1 names this specifically as the kind
  of thing that looks helpful and isn't. The UI design's per-podcast *Download all (n)* overflow item
  is a narrower version, and was accepted as a *command* rather than a rule — ADR 0014.
- **A two-pane tablet layout.** Not an omission: `docs/UI.md` §19 explains why the triage model makes
  it the wrong shape. The content-width cap is the part of it worth keeping.
- **Anything that writes to the subscription list.** Permanently out of scope (CLAUDE.md §1).
