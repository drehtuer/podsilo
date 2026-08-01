# Handover — Tier 4c Compose UI

For the agent implementing the screens in Kotlin/Jetpack Compose and wiring them to the app logic.
Written 2026-08-01 after the UI design pass; reviewed for consistency the same day.

This file is a reading order and a trap list — it holds no decisions of its own. Every claim below
lives somewhere canonical: `docs/UI.md` for UX, `docs/UI_interface.md` for the code seam,
`docs/architecture.md` §12 for the decision record. If this file and one of those
disagree, they win.

## Read in this order

1. **`docs/UI.md`** — what the screens are, what every gesture does, and every state each screen has.
   §§4–11 are the eight screens; §12 the cross-cutting rules; §16 motion; §17 spacing; §18 icons;
   §19 orientation. This is the canonical UX document — when it and anything else disagree, it wins.
2. **`docs/UI_interface.md`** — the code seam: per-screen `UiState`/`UiEvent`/`UiEffect`, the ports
   the screens bind to (§8, all built), corner cases (§14), notifications (§15), accessibility (§16).
   Start at §0's seven rules; they are what the rest is derived from.
3. **`docs/architecture.md`** §3 (data flow), §5 (ports), §9 (ledger state machine) — the contracts you
   are binding to. §12 is the decision index — ADRs 0012–0016 are the ones this design produced.
4. **`TODO.md`** Tier 4c — the task order, which is dependency-ordered rather than screen-ordered on
   purpose.
5. The visual reference lives in the design project, not the repo: `Podsilo Screens.dc.html` (all
   eight screens, every state, light and dark) and `Podsilo Prototype.dc.html` (tap-through).

## Start here

**Nothing is blocked, on a decision or on a port.** The four open decisions were settled on
2026-08-01 as ADRs 0012–0016, and every port those ADRs and `docs/UI_interface.md` §8 called for is
now declared *and* implemented — error log, `KEY_USER_REQUESTED`, connectivity, Login Flow v2, free
space, bulk writes, per-feed refresh, `Episode.link`, the four settings, Coil and Lucide.

What is left is **the screens themselves**, and only them:

1. `:feature:episodes` — S1 (podcast list), S2 (episode list), S3 (detail sheet).
2. `:feature:settings` — S4, S5, S6.
3. `:app` — the `NavHost`, S7 (activity), S8 (error log).
4. The error-log **write points** outside `FeedRefresher`, plus the test that no entry ever contains
   a credential.

The theme is already applied at the root from the persisted preference, and `sanitizeEpisodeHtml`
(the one thing in `:feature:episodes` that exists) is table-tested, so S3 can render show notes on
day one.

## Decisions already made — do not re-litigate

These were settled with reasons recorded; changing one means editing the doc that holds it, not
working around it in code.

- **S1 lives in `:feature:episodes`, not `:app`.** It shares the ledger query and the `EpisodeUi`
  projection with S2; a badge that disagrees with the list it opens is the bug co-location prevents.
  S7 and S8 go in `:app` — they are cross-cutting (workers, sync, the log).
- **One `StateFlow<UiState>` per screen, sealed content variants** — never an `isLoading` flag beside a
  nullable payload. One-shot effects are a `Channel<UiEffect>` so snackbars and navigation cannot
  replay on rotation.
- **ViewModels call `WorkScheduler`, never `WorkManager`.** They also never call an HTTP client.
- **No undo anywhere.** Decisions commit immediately and are corrected by acting again. Do not add a
  snackbar action; the bulk operations' counted confirmations are the safeguard instead.
- **"New" is the absence of a ledger row**, not a stored state. There is no `NEW` in `LedgerState`.
- **The ledger is the only authority on whether an episode was handled.** The single licensed
  existence check is the pre-flight duplicate guard behind `KEY_USER_REQUESTED` (ADR 0012 §4).
- **Portrait-first, orientation unlocked, single scrolling column in landscape.** No two-pane layout —
  `docs/UI.md` §19 explains why that is a decision and not an omission.
- **Dynamic colour off.** One seed, two schemes, so both can actually be verified.
- **Lucide icons**, one weight, per `docs/UI.md` §18's table. Prefer the Lucide Compose artifact over
  hand-converting SVGs (`docs/UI_interface.md` §17). Approved as a dependency — ADR 0015.

## The traps

Things a reasonable implementation gets wrong. Each is a test, not a note.

1. **S1's ordering is frozen between refreshes.** Sort once per explicit refresh and on cold start,
   hold the key order, re-project updated values into it. Recomputing the sort inside the `Flow`
   combine is the bug — rows must not move under the user's finger.
2. **Never show a stale download percentage.** A percentage is only ever drawn from a progress update
   received *in this process* (`docs/UI_interface.md` §7). After process death a `DOWNLOADING` row is
   indeterminate and reads *resuming*.
3. **The 400 ms triage hold survives reduced motion.** Implement it as a `delay`, not an animation.
   With no undo it is the only feedback the decision hit the intended row.
4. **A remote action for an already-`DOWNLOADED` episode must do visibly nothing.** This is the
   "triage durability" property — the highest-value test in the project.
5. **Filter predicates belong in SQL**, resolved by `EpisodeLedgerRepository.observeEpisodes(filter)`,
   including the counts S1 shows. Two code paths means a badge that can disagree with its list.
6. **Selection is deliberately not restored after process death.** A restored set of checkboxes the
   user does not remember choosing is a bulk action waiting to happen by accident.
7. **Bulk writes are one transaction**, via `upsertAll` — not 412 `upsert` calls and 412 emissions.
8. **`FOLDER_UNAVAILABLE` failures are non-retryable** (ADR 0011). That row offers **Choose folder**,
   never a bare **Retry**.
9. **Sanitise `description` at render, never at write.** `sanitizeEpisodeHtml` is a pure function and
   the only place hostile feed HTML meets a renderer — table-test it before the screen that uses it.
10. **The error log never records a credential.** Not the app password, not the Basic-auth header, not
    a URL containing either. Assert it.

## Known gaps, stated plainly

- **`FeedRefresher` is now a ledger writer** (ADR 0013's mark-old rule, via `MarkOldEpisodesRule`).
  "The refresher has no ledger dependency at all" used to be what made *refreshing never downloads*
  structural; it writes `SKIPPED` only, and the tests now pin that rather than the reading.
- **S8 will render an honest but very quiet log.** Only `FeedRefresher` records; the sync, download
  and auth write points are not wired yet.
- **`SafDownloadTarget` and `KeystoreAppPasswordCipher` remain untested** (ADRs 0011, 0010) — device-only.
  They are also the two things most likely to be what is actually broken when a download or a login
  fails on real hardware, so check them before suspecting the UI.
- **No screen has ever run on a device.** The designs are 37 static frames plus a web prototype; the
  foreground-service notification in particular has never been displayed.
- **The prototype is not a reference implementation.** It simulates the download pipeline with timers
  and holds state in one class. Read it for behaviour and flow, never for structure.
- **Sample content is invented** (The Signal Room, State of the Nation…) and the cover art is generated
  placeholder work. Real feeds supply their own artwork; the only artwork the app draws itself is the
  monogram fallback tile (`docs/UI.md` §18).

## Ready?

Yes, and more so than last time: every state is specified, every state class is declared, every
referenced type exists in `docs/UI_interface.md` §13, the corner cases are written down, no decision
is outstanding, and — as of 2026-08-01 — every port the screens call is implemented rather than
merely declared. S8 has its `LogRepository`; S3's terminal-episode action bar has
`KEY_USER_REQUESTED`.

The remaining risk is not design and not decisions — it is that **none of this has run on a device**,
and the two pieces most likely to be broken (`SafDownloadTarget`, `KeystoreAppPasswordCipher`) are
the two that no test can reach.
