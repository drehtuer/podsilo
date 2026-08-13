# 0014 — Bulk *user-initiated* download is allowed; "no download all" is narrowed to "no download rules"

## Status

**Accepted 2026-08-01.** Resolves the ADR `docs/UI.md` §14.3 asks for. Amends the wording of
**README** and **CLAUDE.md §1**, both of which said "no download all" without qualification.

## Context

CLAUDE.md §1's non-goals and README both state plainly:

> **No automatic or bulk downloading.** No "auto-download new episodes" setting, no per-feed
> auto-download rules, no "download all". Disk space on a phone is finite and the author wants to
> decide episode by episode. A "download all visible" button is the kind of thing that looks helpful
> and isn't.

The UI design adds two bulk affordances at the author's request: a per-podcast **Download all (n)**
overflow item, and a selection mode where several rows are picked and acted on together
(`docs/UI.md` §5). Left unrecorded, a future reader finds a stated non-goal and a shipped feature
that violates it, and cannot tell which one is current.

## Decision

The rule is **not** "never download more than one episode at a time". The rule is **"nothing is
downloaded that the author did not ask for"** — and the line falls between a *rule* and a *command*:

| Forbidden (unchanged) | Allowed (this ADR) |
|---|---|
| A **rule** that downloads episodes without being asked | A **command** the user issues now, to a set they can see |
| Anything triggered by sync, feed refresh, or app start | Only a tap, followed by a confirmation naming the count |
| Global scope | Scoped to one podcast's current *To decide* filter, or to an explicit selection |
| Invisible | Every queued episode appears in S7 and can be cancelled individually |

Concretely, what is permitted:

- **Download all (n)** in S2's app-bar overflow — deliberately in the overflow, not a prominent
  button — queueing every episode in the current *To decide* filter. Per-podcast only; there is no
  global "download everything" anywhere.
- **Selection mode** (long-press, or a checkbox when a touch-exploration service is active), acting
  on an explicit set.
- Both behind a confirmation dialog naming the count, and — when durations are known — an
  approximate total size.

What remains forbidden, unchanged: an auto-download setting, per-feed auto-download rules, any
download triggered by a worker, and any global bulk scope.

**No count cap.** A bulk download is never refused or warned about for being *large*, only for not
*fitting*: the dialog gains a warning line when the estimated total exceeds free space on the
download volume, and the action stays enabled even then, because the estimate comes from
`itunes:duration`, which is notoriously unreliable and must never block a decision.

## Consequences

- **README**'s "Not automatic" bullet and **CLAUDE.md §1**'s bulk-download non-goal are reworded to
  the rule/command distinction. CLAUDE.md's advice — that a "download all visible" button usually
  looks more helpful than it is — is kept as guidance, since it is still true; what changes is that
  the author has weighed it for this specific case.
- **`NoAutoDownloadInvariantTest` is unaffected and stays exactly as strict.** It asserts that sync
  and feed parsing create zero ledger rows and post zero actions. Bulk rows originate only from a UI
  event, so nothing it covers changes. It is worth adding one assertion in the opposite direction:
  that no worker or sync path can reach the bulk enqueue API at all.
- `WorkScheduler` enqueues one work request per episode, as a normal download would — bulk is a loop
  at the call site, not a new work type. Each row is therefore individually cancellable in S7, which
  is what makes the "visible" column of the table above true rather than aspirational.
- The ledger writes go through `upsertAll` in one transaction (`docs/UI.md` §B8.6), not one
  `upsert` per episode.
