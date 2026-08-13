<!-- SPDX-License-Identifier: GPL-3.0-or-later -->

# 0023 — A completed download also emits `PLAY`

**Status:** Accepted (2026-08-14). **Reverses an explicit prohibition in CLAUDE.md §5**, which is
amended to match. Settles **D3** of `docs/TODO.md`.

## Context

CLAUDE.md §5 said this, in terms:

> **`PLAY` is not emitted on download.** Downloading means `DOWNLOAD` and nothing else. Because the
> author's audio player does not sync, no `PLAY` will ever be generated on their behalf by listening —
> only by an explicit skip. There is no "also mark as played when downloading" setting; that would
> assert something untrue and can trigger auto-delete in other clients.

That reasoning is sound on its own terms and was wrong about the outcome, for a fact discovered after
it was written: **Nextcloud's gpoddersync discards every non-`PLAY` action on arrival and still
answers 200** (`docs/decisions/0008`, verified at source and live). So the honest signal the rule
insisted on is, on the author's own server, no signal at all. A downloaded episode stayed *new* in
RePod and on every other client, permanently, with nothing in the log to show for it.

The author tested the round trip on 2026-08-14 and reported it as a bug: *"Downloading an episode
does not mark it as played on Nextcloud. This is a bug, the episode should also be marked as played
after a successful download."*

## Decision

A ledger row in `DOWNLOADED` emits **two** actions, in this order:

1. `DOWNLOAD` — unchanged, and still the honest record that this device fetched the file. A server
   that keeps it (`opodsync`) gets it.
2. `PLAY` with `position == total > 0` — the encoding every reader treats as *finished*
   (`docs/decisions/0022`).

The order is load-bearing. gpoddersync stores one row per episode, so on a server that keeps both,
the later action is the one that survives — and the one worth surviving is the one another client can
read.

### Why the original rule's objection no longer decides it

**"It asserts something untrue."** It asserts that the episode is finished *here*, which on this
setup is exactly true: the file leaves for a player that never reports back, and Podsilo will never
learn anything more about it. The app's own vocabulary already treats a download as terminal — the
ledger state is terminal, the row is greyed out, and the episode never returns to *To decide*. The
only place that was not true was the server.

**"It can trigger auto-delete in other clients."** Still true, and now accepted knowingly: another
client may remove its own copy of an episode this device has downloaded. Given that this device has
the file and the author's players read from that folder, that is the intended reading rather than a
side effect.

## Consequences

- **Already-downloaded episodes are not retroactively marked.** Their rows are `syncedToServer =
  true`, so no pass will re-post them. The *Send this device's state to Nextcloud* button in
  `docs/TODO.md` §7 is exactly the tool for that, and is the reason it is worth building.
- **`DOWNLOAD` is still emitted**, so nothing is lost against a server that stores it, and ADR 0008's
  finding remains recorded rather than worked around.
- **One row can now produce several actions.** `toOutboundActions()` returns a list and
  `SyncOrchestrator` keeps rows paired with their actions — the actions are what is posted, the rows
  are what is marked synced. That pairing is what stops a partially-posted batch marking a row it did
  not send.
- CLAUDE.md §1 requirement 9 and §5's mark-on-download semantics are amended in the same change, so
  the instruction file and the code do not disagree.
