# 0012 — Terminal ledger states are re-openable by explicit user action

## Status

**DRAFT — not accepted.** Written from the UI design work (see `UI_interface.md` §8.2 and
`docs/UI.md` §12.3/§14.1); the author has not reviewed it. Everything under
[Decision](#decision) is a *proposal* and the open points in
[Still to settle](#still-to-settle) are genuinely undecided — do not treat any of it as agreed. The
next agent should resolve those points with the author, delete this paragraph, and set the status to
`Accepted` (recording the date) before writing code against it.

Resolves `docs/architecture.md` §12 open decision #15, and the ADR `docs/UI.md` §14.1 asks for.

## Context

`docs/architecture.md` §9 originally stated that `DOWNLOADED`, `SKIPPED` and `HANDLED_REMOTELY` are
terminal and "never automatically revisited". The UI design needs three of the four edges out of
those states anyway, because the alternative is worse:

- **There is no undo** (`docs/UI.md` §12.3). A wrong decision is corrected by acting again, not by
  racing a snackbar timer. If terminal states cannot be re-opened, a mis-swipe is permanent.
- `HANDLED_REMOTELY` means *another client* decided. The user must be able to override that — the
  episode was never theirs to decide on until now.
- `SKIPPED` covers the bulk "mark old episodes as played" write (`docs/UI.md` §7), which is a
  deliberately coarse operation. Its safeguard is a counted preview *plus* the ability to still
  download any individual episode afterwards.

**This contradicts shipped code.** Tier 4b's `DownloadWorker` refuses to act on an already-terminal
ledger row, and `DownloadWorkerTest` asserts that refusal. That refusal is not incidental: together
with `FeedRefresher` having no ledger/download dependency at all, it is what makes CLAUDE.md §7
item 6's no-auto-download invariant *structural* rather than a matter of care. Removing it to make
*Download again* work would trade a proven invariant for a UI affordance.

So the question this ADR answers is not "should terminal states re-open" — the UI needs it — but
**by what mechanism, such that the invariant survives**.

## What the code does today

`:core:download`'s `DownloadWorker` reads the ledger row for the `episodeKey` it was given and
returns success without doing anything if the state is terminal. There is exactly one path to a
file: an explicit per-episode enqueue. `DownloadWorkerTest` covers both halves — the trigger fires
once on a delivery, never on a failure, and a terminal row is refused.

`DownloadTarget.existingNames(folder)` exists (ADR 0011) for collision suffixing, and its KDoc says
explicitly that it is **not** a de-duplication check — whether a file is in the folder says nothing
about whether the episode was handled. The ledger is the only authority (CLAUDE.md §11).

## Decision

### 1. An explicit `userRequested` flag on the work request

```kotlin
// :core:download
const val KEY_USER_REQUESTED = "userRequested" // Boolean, default false
```

`DownloadWorker` keeps refusing terminal rows **unless** this flag is present and true. The flag is
settable only from a UI event — an `EpisodeListEvent.Triage` or `EpisodeDetailEvent.Triage` — and
never from a worker, a sync path, `FeedRefresher`, or `SyncOrchestrator`. Enqueueing still goes
through `WorkScheduler`, so there is one place to audit.

Why a flag rather than relaxing the refusal: the invariant becomes "only a UI event can create a
file from a terminal row", which is one grep to verify and one test to pin, instead of a property
that has to be re-derived every time the worker changes.

### 2. A re-download re-posts `DOWNLOAD`

The new ledger row is written with `syncedToServer = false`, so the outbox pushes a fresh `DOWNLOAD`
action. Rationale: it is a true event — this device fetched this episode, again, at a new time. The
alternative (suppressing the second action as redundant) would require the sync layer to reason
about history it deliberately does not keep.

Note this is mostly moot against a real Nextcloud, which discards `DOWNLOAD` on POST and returns 200
anyway (ADR 0008). It is correct against `opodsync` and older servers, and costs one field in a
request already being sent.

### 3. `attempts` resets to 0

A re-download is a new attempt chain, not a continuation of the old one. Leaving `attempts` at 3
would make a fresh download render as *attempt 3 of 3* in S7 and immediately look exhausted.
`lastError` is cleared at the same time.

### 4. The pre-flight duplicate guard, and the one licensed use of `writtenFileName`

When — and **only** when — `userRequested` is true and the row already carries a
`writtenFileName`, `EpisodeDownloader` asks `DownloadTarget.existingNames(folder)` whether *this
episode's own previously written file* is still there. If it is, the download is **aborted, not
overwritten and not suffixed**: the ledger returns to `DOWNLOADED` unchanged and the UI reports
*"Already in your folder — <name>"* as a snackbar and in the row/detail status line. This is an
informational outcome, **not** an `ERROR`, and is not written to the error log. If the file is gone,
the download proceeds and produces it again.

`docs/architecture.md` §11 calls "never use `writtenFileName` as an existence check" its single most
important invariant. This ADR carves out exactly one exception and states the distinction that keeps
it honest:

> The guard runs **because the user asked for this specific file**. It never decides whether an
> episode is new, whether it was handled, or whether it should be downloaded. Those stay the ledger,
> unconditionally.

A first-time download (no `writtenFileName`) performs **no** existence check at all. Collision
suffixing is untouched and keeps doing what ADR 0011 says it is for — stopping two *different*
episodes fighting over one name.

### 5. Not a state-machine relaxation for sync

`SyncOrchestrator` is unchanged. Inbound remote actions for terminal rows remain idempotent no-ops.
The four new edges in §9's diagram are all user-initiated; none are reachable from a sync pass.

## Alternatives considered

- **Relax `DownloadWorker`'s terminal-row refusal outright.** Simplest change, and the reason it is
  rejected: it deletes the structural half of the no-auto-download invariant. Any future bug that
  enqueues a download for an already-handled episode would then silently produce a file.
- **Delete the ledger row instead of transitioning it.** Makes the episode "new" again, so the
  existing code path works untouched. Rejected: it loses `actionedAt`, the sync history and the
  reason the row existed, and an episode that becomes new again would reappear in *To decide* on
  every other client's next reconciliation — the exact thing the ledger exists to prevent.
- **A separate `RedownloadWorker`.** Duplicates the entire cache→verify→name→tag→deliver pipeline,
  or wraps it in a second entry point that must be kept in step. Rejected: CLAUDE.md §3's "don't
  invent replacements" applies to our own code too.
- **Suppress the second `DOWNLOAD` action.** See §2 — would require history the sync layer does not
  keep.

## Consequences

- `DownloadWorkerTest` needs the mirror of its existing case: a terminal row **with** the flag
  proceeds, and one **without** it still refuses. Both matter; the second is the invariant.
- `NoAutoDownloadInvariantTest` should gain an assertion that nothing outside a UI event ever sets
  `KEY_USER_REQUESTED` — realistically a test that `SyncOrchestrator` and `FeedRefresher` enqueue no
  download work at all, which is already close to what it proves.
- `EpisodeDownloader` gains one branch and one new outcome value (the informational "already
  present" case, distinct from both success and failure). Its 11 existing cases stay valid.
- The UI must render that third outcome. `UI_interface.md`'s `SnackbarText.AlreadyInFolder(fileName)`
  exists for it.
- ADR 0011's KDoc on `existingNames` should be amended to name this one licensed caller, so a future
  reader does not find the guard and conclude the rule was abandoned.

## Still to settle

Open points for the next agent — each needs the author, not a judgement call:

1. **Is §2 right?** Re-posting `DOWNLOAD` is defensible but invisible against real Nextcloud
   (ADR 0008). Worth confirming the author wants the honest-but-inert action rather than nothing.
2. **Does *Mark as played* on a `DOWNLOADED` episode need the same flag?** `docs/UI.md` §12.3 allows
   it and it touches only the ledger — no file is written, so `DownloadWorker` is not involved and
   the flag looks unnecessary. Confirm there is no path where a `SKIPPED` row over a `DOWNLOADED`
   one loses the `writtenFileName` that the guard later depends on.
3. **What does S7's *Retry* on an `ERROR` row use?** `ERROR → QUEUED` was always a legal edge, so it
   arguably needs no flag. But if `attempts` resets per §3, *Retry* and *Download again* stop being
   distinguishable in the ledger — decide whether that matters for diagnostics.
4. **Should the "already in your folder" outcome be counted anywhere?** It is deliberately not an
   error and not logged. If it turns out to happen often, silence makes it invisible; a counter in
   S7 would be the cheapest fix. Left out for now on the grounds that it should be rare.
