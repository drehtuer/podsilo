<!-- SPDX-License-Identifier: GPL-3.0-or-later -->

# 0025 — Two directional sync passes, and why the pull needs no new rule

**Status:** Accepted (2026-08-14). Implements `docs/TODO.md` §7, settled by **D4**, **D5** and **D6**.

## Context

The author asked for *two buttons in the settings to (1) sync Nextcloud's state to Podsilo and (2)
sync Podsilo's state to Nextcloud — it's up to the user to decide which state is correct.*

The ordinary pass is **incremental and deferential**: it pushes only rows the outbox has not sent,
pulls only actions newer than the cursor, and never overrides a terminal local state. A shared,
append-only log with two writers has no automatic answer to "these disagree", and a person looking at
both screens does.

There is now a second, sharper reason. A download recorded before `docs/decisions/0023` emitted
`DOWNLOAD` alone, which Nextcloud discards — and its row is already `syncedToServer = true`, so **no
ordinary pass will ever retry it**. Without a force push those episodes are permanently invisible to
every other client. The author has such rows on their phone.

## Decision

Two passes on `SyncOrchestrator`, one worker mode each, two rows on S4.

### The pull is `since = 0` and nothing else

**It overrides no rule**, which is the most important sentence in this document. D4 says never
un-mark; D5 says never overwrite a `DOWNLOADED` row. Together those are exactly "leave all three
terminal states alone" — which is precisely what `reconcile` already does:

```kotlin
if (existing != null && existing.state in TERMINAL_STATES) return@mapNotNull null
```

So *apply Nextcloud's state here* is the ordinary reconciliation run over the whole log instead of
the delta. It can only ever **shorten** the To-decide list: it decides episodes this device has not
decided, and touches nothing else. **If `forcePull` ever needs a branch inside `reconcile`, it has
drifted into the version the author ruled out**, and the tests are written to fail if it does.

`since = 0` is the thing CLAUDE.md §5 warns about — unbounded, and not for the normal path. Once, on
a button press, is exactly the case that warning leaves open.

### The push re-asserts, and is chunked

*Send this device's state* posts every ledger row that maps to an action, **including rows already
marked synced**. That is the point: those are the ones the server never received.

Chunking at 200 actions per request lives in the **shared** push path, so the ordinary outbox drain
gets it too. A *mark all as played* over ~9,500 episodes was already one POST of thousands of actions
against a PHP endpoint whose `post_max_size` defaults to 8 MB; that was a latent bug, not a new one.
A failed chunk stops the run: earlier chunks stay marked because they really were accepted, and the
rest stay unsynced for the next pass.

### One worker, three modes

`SyncWorker` takes `KEY_SYNC_MODE`, the same shape `FeedRefreshWorker.KEY_FEED_URL` already uses.
Three workers would be three copies of the credential check, the outcome mapping and the retry
policy, differing only in which method they call.

`DirectionalSync` is a **separate port** from `SyncTrigger` rather than a parameter on it: "sync now"
and "overwrite the server with my state" are different requests, and one interface with a mode
argument is one typo away from confusing them.

### Confirmations, and one honest gap

Both rows confirm first, for different reasons.

- **The push names its count.** It writes to a shared log other clients act on and the API cannot
  retract — the safeguard `docs/decisions/0013` established for every bulk write here. The count is a
  ledger query, so it costs nothing.
- **The pull names none.** `docs/TODO.md` §7.3 asked for a two-phase dialog — *"3,022 actions from
  Nextcloud. 87 change something here."* — and that number cannot be produced without fetching,
  while a view model may not touch the network (`docs/UI.md` §B0.3). Rather than smuggle a fetch into
  a view model or invent a plausible figure, the dialog says what the operation can and cannot do.
  It is a shorter promise than the push's, and it is true.

Both rows are **absent** unless an account is connected, and the view model re-checks, so the rule
holds however the event arrives.

## Consequences

- **The author's stranded downloads become fixable.** One press of *Send this device's state* re-posts
  them with the `PLAY` that `docs/decisions/0023` added.
- **`docs/TODO.md` §7 is complete**, and with it every step of the #60 plan except the device
  verification of these two buttons.
- The NEXTCLOUD group on S4 is now long enough that its buttons sit below the fold in a test
  viewport. That is a real consequence of the placement §7.3 chose, and the tests scroll rather than
  the rows moving.
