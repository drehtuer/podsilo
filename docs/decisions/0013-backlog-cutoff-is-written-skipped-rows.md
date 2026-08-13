# 0013 — The backlog cutoff is written `SKIPPED` rows, not a read-time filter

## Status

**Accepted 2026-08-01.** Resolves the ADR `docs/UI.md` §14.2 asks for. **This changes a rule in
CLAUDE.md §5**, which has been amended to match — see [Consequences](#consequences).

## Context

Subscriptions arrive wholesale from the server, so on first run the author's feeds may expose
thousands of episodes with no action anywhere, most of them predating gpoddersync. Nothing downloads
automatically, so this is a list-length problem, not a bandwidth one — a "New" tab with 5,000 rows
is useless for triage.

Two mechanisms for it now exist in the repository, and **they do not compose**:

1. **A read-time filter** (CLAUDE.md §5, `docs/architecture.md` §4/§5). "New" means *no ledger row
   **and** `pubDate >= Feed.firstSeenAt`*. Implemented in Tier 4a inside
   `EpisodeLedgerDao.observeNewEpisodes`, behind an `includeBacklog` flag that lifts the restriction
   for the "show full archive" case.
2. **A written state** (`docs/UI.md` §7/§12.5/§14.2). S4's *Mark old episodes as played* upserts
   `SKIPPED` rows for undecided episodes older than a user-chosen cutoff, so they leave *To decide*
   the same way a swipe would.

If both are live, an episode can be hidden by the filter *and* unmarked in the ledger, and a reader
of either mechanism alone would draw the wrong conclusion about why a row is missing.

## Decision

**The UI mechanism is authoritative. "New" means exactly `no ledger row`, full stop.**

- `LedgerFilter`'s *To decide* predicate is a single condition. The episode-list query and the S1
  count badge both use it, and neither passes a date at all.
- Old episodes are hidden by **writing** `SKIPPED` rows — the user's own decision, applied in bulk
  behind the counted preview dialog (`docs/UI.md` §7), never silently at first sync.
- `EpisodeLedgerDao.observeNewEpisodes`'s `firstSeenAt` cutoff is **retired, not merely unused**:
  the parameter and its SQL clause are removed, so a future caller cannot re-enable a second
  mechanism by passing a flag. The `MigrationTest`-adjacent DAO tests covering it go with it.
- `Feed.firstSeenAt` **stays in the schema.** It becomes the default cutoff date offered for a
  newly-appearing feed, and it is the only sensible origin for one.
- The rule applies to **newly-parsed episodes after each feed refresh** when the *older than* setting
  is not `off`: an episode arriving already older than the cutoff is marked immediately, without a
  preview. The user consented once by setting the rule; re-asking per refresh would make the setting
  pointless.
- The resulting actions go through the **normal outbox** (`syncedToServer = false`) and are pushed in
  batches by `SyncWorker`, not as one giant POST. Writes are one transaction via `upsertAll`, not one
  per row.

## Why this, over the read-time filter

The written state is better on every axis the author actually cares about:

- **It is visible.** The episodes appear under *Played / handled* instead of vanishing with no
  explanation of where they went.
- **It is reversible per episode.** Any of them can still be downloaded individually — a read-time
  filter offers only an all-or-nothing archive toggle.
- **It is shared.** The `PLAY` actions reach Nextcloud, so AntennaPod and RePod stop showing those
  episodes as new too. Sharing triage state across clients is the point of the product (README); a
  local filter achieves none of it.
- **It keeps one predicate.** Counts and lists come from one SQL condition, so a badge cannot
  disagree with the list it opens.

## What this costs, stated plainly

**CLAUDE.md §5 said the opposite**, and said why:

> Do **not** auto-write ledger rows for the backlog, and do **not** emit any action for it.
> Untouched episodes stay genuinely untouched; the author's other clients see nothing from us.
> Hiding by filter is reversible, writing to the shared action log is not.

That reasoning is sound and the trade is real: **this is not undoable in bulk.** A `PLAY` action for
400 episodes cannot be retracted from the shared log, and other clients will act on it. The
safeguards that make it acceptable are the ones the UI design already specifies, and they are not
optional decoration:

- the preview dialog names the exact count and the per-feed breakdown **before** anything is written;
- it states in words that the state is sent to Nextcloud and other clients will see it;
- every affected episode remains individually downloadable afterwards;
- the rule is opt-in — the *older than* setting defaults to `off`, and the one-shot
  *Mark ALL episodes as played* is a button, never a default.

The author accepted this trade explicitly. CLAUDE.md §5's backlog section is amended to match rather
than left to contradict the built behaviour.

## Consequences

- **CLAUDE.md §5** ("The backlog is a UI problem, not a download problem") is rewritten to describe
  the written-`SKIPPED` mechanism and to keep the *reason* the old rule existed — that a bulk write
  to a shared log is irreversible — as the justification for the preview being mandatory.
- `docs/architecture.md` §4/§5's description of the "New" filter is amended; `firstSeenAt` keeps its
  column and its KDoc changes to say what it is now for.
- `:core:database` loses the cutoff clause and gains `upsertAll`/`previewUndecided`
  (`docs/UI.md` §B8.6).
- `FeedRefresher` gains the after-refresh application of the rule, which makes it — for the first
  time — a component that writes ledger rows. **The no-auto-download invariant is unaffected**:
  these rows are `SKIPPED`, never `QUEUED`, and `NoAutoDownloadInvariantTest` should be extended to
  assert exactly that (a refresh with the rule active writes only `SKIPPED` rows, enqueues no
  download work, and posts no `DOWNLOAD` action).
- The one genuinely new risk is a **first-run stampede of `PLAY` actions** if the rule is set before
  the first full sync. Batching bounds the request size but not the total; if that turns out to be
  slow in practice, the fix is a batch size, not a change to this decision.
