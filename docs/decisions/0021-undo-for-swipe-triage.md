<!-- SPDX-License-Identifier: GPL-3.0-or-later -->

# 0021 — Undo for a swipe is a *deferred write*, not a reverted one

**Status:** Accepted (2026-08-09)
**Supersedes:** `docs/UI.md` §12.3's rule *"There is no undo snackbar"*, for swipes only.

## Context

Issue #49: an accidental swipe immediately triages an episode, and there is no way back.

`docs/UI.md` §12.3 had ruled the other way, and not on style. A skip becomes a **`PLAY` action in an
append-only log** on the user's Nextcloud; other clients act on it; the GPodder API has no retraction
of any kind. §12.3's answer was therefore "correct it by acting again", and every bulk action got a
confirmation dialog instead of an undo.

The author hit the accidental swipe in practice and asked for undo anyway. That is a legitimate
reversal of a design call — but *how* it is built decides whether the app can keep its promises, so
it went back as a decision (**D1** in `TODO.md`) rather than being implemented against the document
forbidding it.

Two implementations were possible.

**Write now, revert on undo.** Keeps today's immediate write; *Undo* deletes the ledger row and
cancels any download. Durable across process death — and unable to help once a sync pass has drained
the outbox, which may happen immediately (`requestSyncNow` fires after any download lands). It also
needs a **delete on `EpisodeLedgerRepository`**, which has none by design: CLAUDE.md §11 calls the
ledger row "the only dedup authority, and it must outlive the file".

**Defer the write.** Hold the decision in memory for the undo window; write the ledger row and
enqueue the download only when it elapses. Nothing to retract, because nothing was written.

## Decision

**Defer the write.** The author chose this option.

- A swipe holds its decision for **5 s** and writes nothing — no ledger row, no outbox entry, no
  work request. *Undo* discards it.
- The **view model owns the window**, not the snackbar. The host shows a snackbar with *Undo* and
  reports a tap back as an event; an undo arriving after the window has closed finds nothing to
  discard rather than racing the write.
- **One pending decision at a time.** A second swipe commits the first. Two live windows would need
  two snackbars and an answer to "which one does *Undo* mean".
- **Leaving the screen commits.** Silently dropping a decision the user made and watched take effect
  is worse than committing one they might have wanted back — they can act again, and the row shows
  what happened. This is the one place `EpisodeListViewModel` reaches outside its own lifecycle: the
  write goes to an injected scope that outlives it, because `viewModelScope` is already cancelled by
  the time `onCleared` runs.
- **The row renders the decision immediately.** A swipe that appeared to do nothing for five seconds
  would read as the app ignoring it, and the user would swipe again. The row shows the state the
  decision *will* produce, so it does not change appearance a second time when the write lands.
  This is presentation only — `EpisodeUi.asPending`, nothing stored.

### Scope

**Swipes only.** The row's explicit action buttons and S3's action bar commit immediately, as
before: they are deliberate presses on a named affordance, not a gesture that can be started by
trying to scroll.

**Bulk actions are untouched** (decision **D2**). Selection mode, *Download all* and *Mark all as
played* keep their confirmation dialogs and gain no undo. ADR 0013 and 0014 made those dialogs the
safeguard that replaced "don't write at all"; a five-second undo is a weaker guarantee for an action
covering hundreds of rows, and the two do not stack usefully.

**The ledger gains no delete** (decision **D4**). It stays append-only.

## Consequences

- Undo cannot post something it must later retract, because it never posts anything. That is the
  whole reason for the choice.
- **A decision made and then immediately killed is lost.** If the process dies inside the window,
  the episode is simply still undecided. Nothing *wrong* is written — this is the cost the design
  accepts, and it is stated here rather than discovered later.
- The visible state and the stored state disagree for up to five seconds, by design. Every screen
  other than the one holding the decision reads storage, so S1's badge and S7 will lag the row by
  the window. That is acceptable: the decision genuinely has not been made yet.
- `docs/UI.md` §12.3 keeps everything it says about *Download again*, the duplicate-file guard and
  bulk confirmations. Only its "no undo" rule narrows, to "no undo except a swipe's own window".
