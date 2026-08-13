<!-- SPDX-License-Identifier: GPL-3.0-or-later -->

# TODO — sync to Nextcloud

Working plan for [#60](https://github.com/drehtuer/podsilo/issues/60) — *"The episodes marked as
played or downloaded are not shown as played in Nextcloud. If I mark an episode in Nextcloud's
Podcasts app, it does not show up in podsilo"* — and for the **two directional sync buttons** the
author asked for on top of it (§7).

This file is temporary and dies when the work lands. `docs/backlog.md` is for what we are *not*
building; this is what we are. Written 2026-08-13; **no code changed yet.**

Everything below was read out of source — `thrillfall/nextcloud-gpodder` at `main`, RePod at
`git.crystalyx.net/Xefir/RePod`, and this repository — not inferred from the symptom.

---

## 1. What the report actually is

**Both halves are the same fault, and it is ours.** Nothing is wrong with the wire format, the
authentication, the endpoint paths or the JSON shape; a `PLAY` that leaves this app is accepted and
stored correctly. The problem is that **almost nothing makes one leave.**

`WorkScheduler.requestSyncNow()` exists and works. It has exactly four callers:

| Caller | When |
|---|---|
| `DownloadWorker` | a download completed |
| `ConnectViewModel` | an account was just connected |
| `ActivityViewModel` | the user tapped **Sync now** on S7 |
| `PodsiloApplication` (periodic) | every `DEFAULT_SYNC_INTERVAL_MINUTES` = **240 minutes** |

Not in that list: **marking an episode as played.** `TriageWriter` writes the `SKIPPED` row with
`syncedToServer = false` and returns; `EpisodeScheduler` — the port the triage view models hold —
has `enqueueDownload`, `cancelDownload` and `requestFeedRefresh`, and **no sync method at all**. The
same is true of the bulk paths (`SettingsViewModel`'s *Mark old / all as played*) and of
`MarkOldEpisodesRule` inside `FeedRefresher`.

Also not in that list: **pull-to-refresh**, on either screen. `PodcastListViewModel.refresh()` calls
`scheduler.requestFeedRefresh(null)` and nothing else; `EpisodeListViewModel.refresh()` does the
same for one feed. That fetches RSS. It does not pull subscriptions, push the outbox, or pull
episode actions. `docs/UI.md` §4 specifies the opposite in as many words — *"enqueues an expedited
`SyncWorker` … **and** a `FeedRefreshWorker` pass"* — so this is a spec that was written, agreed and
never wired. **The fifth time this project has hit "specified, wired at one end, never connected in
the middle."**

So, from the author's chair: mark five episodes played, pull to refresh, open Nextcloud → nothing.
Mark one in RePod, pull to refresh in Podsilo → nothing. Up to four hours later, and only if Doze
allows the periodic job to run, both directions silently work. That is indistinguishable from
"sync is broken", and for every purpose that matters it *is* broken.

Underneath that there are two real interop defects (§3, §4) that would still bite after the
triggers are fixed, and one thing that is not fixable at all (§5).

---

## 2. How Nextcloud decides an episode is "played"

Read this before touching the encoding — it is the contract, and it is stricter than ours.

**gpoddersync stores; RePod interprets.** The server keeps one row per episode per user
(`gpodder_episode_action`, unique on the episode identity) and does no interpretation whatsoever.
Everything about "played" is RePod's, in `src/utils/status.ts`:

```js
export function hasEnded(action) {
	return action
		&& (action.action.toLowerCase() === 'delete'
			|| (action.position > 0 && action.total > 0 && action.position >= action.total))
}
```

**`position > 0 && total > 0`.** Not `>=`. An action with `position = 0, total = 0` is stored, is
returned by the API, and renders as **unplayed** — there is no code path in RePod by which a
zero-duration `PLAY` ever shows as read.

RePod's own *mark as read* button (`markAs`) writes exactly what we write, with one difference:

```js
position: read ? action?.total || durationToSeconds(episode.duration || '') : 0,
total: action?.total || durationToSeconds(episode.duration || ''),
```

— it falls back to the **feed's own `itunes:duration`**, parsed at that moment, when it has no
stored total. It always has *something* non-zero to put there for a feed that declares a duration,
and for one that does not, RePod's own button is equally broken. That is the convention we have to
meet.

Episode identity, from RePod's `EpisodeActionReader::parseRssXml`:

```php
$action = $this->episodeActionRepository->findByGuid($guid, $userId);   // raw <guid> text
if ($action) { $url = $action->getEpisode(); }
else { $action = $this->episodeActionRepository->findByEpisodeUrl($url, $userId); }  // <enclosure url>
```

**guid first, enclosure URL as fallback** — the same rule as ours (`guid ?: enclosureUrl`), which is
why identity is a thing to *verify* rather than a thing to fix (§6).

Three server facts that constrain everything else, all from `nextcloud-gpodder`:

- `EpisodeActionController::filterOnlyPlays` — `strtolower($ep['action']) === 'play'`. Our
  upper-case `PLAY` passes. **`DOWNLOAD` is dropped and the response is still 200** (§5).
- `EpisodeActionReader::fromArray` requires `podcast`, `episode`, `action`, `timestamp` and throws
  otherwise. We send all four. `guid`, `started`, `position`, `total` default to `-1` when omitted.
- `EpisodeActionMapper::findAll` — `WHERE timestamp_epoch > :since`, where `timestamp_epoch` is the
  **client-authored** action timestamp, while the `timestamp` the endpoint *returns* is
  `time()`, the **server's wall clock**. Those are two different clocks and we treat them as one
  (§4).

---

## 3. The plan, in order

Each step is a branch and a PR (CLAUDE.md §9), `./gradlew ktlintCheck detekt test` green before it
is called done, and every fix carries a regression test that **fails against the current code**
(CLAUDE.md §7).

### Step 0 — Make the next failure visible *before* fixing anything

**Done 2026-08-13.** 697 tests, 0 failures, 3 skipped; `ktlintCheck detekt test` green.

- [x] **Wire the two missing error-log write points**: `SyncOrchestrator` records every failed pass
      (unreachable server, unexpected failure, and a failed push with its own sentence naming what is
      still waiting), and `DownloadWorker` records every failed attempt with the episode it belongs
      to. A download that fails because the folder is gone or the disk is full is filed under
      `STORAGE` rather than `DOWNLOAD`, and its sentence names the fix instead of offering a retry
      that cannot work.
- [x] **The test that no entry ever contains a credential.** It turned into something better than a
      test: redaction is now applied by the **store**, in `LogRepositoryImpl.record`, so a sixth write
      point cannot forget it. `redactSecrets` (`:core:model`) strips `Authorization:` headers, bare
      `Basic`/`Bearer` tokens, `user:pw@host` userinfo and `?token=`-style parameters; identifiers
      are deliberately left alone (the UI navigates by them, and a feed URL is already on screen).
      - Two things fell out of writing it. The first regex left the credential in place — `\S+`
        stops at the space after `Basic` — which the table caught and review would not have. And the
        existing test asserting this rule was **weaker than its own comment claimed**: it said the
        invariant was "enforced at the write points (asserted in `:app`)", where nothing asserted it.
- [x] **Read what the server actually holds.** Done twice on 2026-08-13 against `cloud.drehtuer.net`
      on the `podsilo` account: a read+write pass, then a read-only pass after the author marked
      episodes in RePod. It confirmed ADR 0008 live (a posted `DOWNLOAD` is discarded, the `PLAY`
      beside it kept; all 72 stored actions are `PLAY`), confirmed the full round trip, and turned up
      **step 3b**, which no amount of source reading had suggested.

      The probe grew a read-only `-Precent=N` dump that prints RePod's reading of each action beside
      ours, flagged where they disagree. That is what made step 3b visible in one line rather than in
      an argument about two source files.

**Step 0 earned its place.** It was written as "make the failure visible before fixing anything",
and what it actually did was find a bug the plan did not contain — and disprove nothing, which is the
other half of why it goes first.

### Step 1 — A decision must reach the server when it is made

The whole of the reported symptom, and the only step that is unambiguously required.

- [ ] Add `requestSyncNow()` to `EpisodeScheduler` (`:feature:episodes`) — `WorkScheduler` already
      implements it via `SyncTrigger`, so this is wiring, not new machinery.
- [ ] Call it after **every** path that writes an outbox row: S2's swipes (at the point the deferred
      write commits, **not** when the gesture starts — `docs/UI.md` §12.3), S2's row overflow, S3's
      action bar, selection-mode bulk actions, and S4's *Mark old / all episodes as played*.
- [ ] `FeedRefresher`'s `MarkOldEpisodesRule` writes `SKIPPED` rows during a refresh and must trigger
      a pass too — it is the one non-UI writer, and its rows are exactly the ones the author will
      never think to look for.
- [ ] **The trigger is not the durability mechanism.** The row is written first and the flag only
      flips on a confirmed 2xx (CLAUDE.md §5); this step only shortens "eventually" to "now". A test
      must pin that a failed sync leaves the row unsynced and the next pass still drains it.

Cost of getting this wrong: a bulk *mark 412 as played* must enqueue **one** pass, not 412. Unique
work by name already gives that, but assert it.

### Step 2 — Pull-to-refresh means refresh, on both screens

- [ ] S1: `PullToRefresh` enqueues an expedited `SyncWorker` **and** the feed refresh, per
      `docs/UI.md` §4, and the indicator stays up for the whole chain rather than for the RSS half.
- [ ] S2: the same, scoped to its feed for the RSS half; the sync pass is global by nature.
- [ ] Offline still short-circuits before either (`docs/UI.md` §12.10) — unchanged.

After steps 1 and 2 the author's next action produces a visible result, which is the actual bug
report. Everything below is what stays broken afterwards.

### Step 3 — `position`/`total` must be non-zero or Nextcloud will not show it

- [ ] Today: `total = durationSeconds ?: 0` and `position = total`. For any feed without a usable
      `itunes:duration` we post `0/0`, which §2 shows renders as **unplayed in RePod forever**. The
      action is stored; it is simply never read as "ended".
- [ ] This needs **decision D2** below before it can be implemented — CLAUDE.md §6 and ADR 0002 say
      in terms *"do not invent a plausible-looking duration"*, and the fix is a value in that field.
- [ ] Whatever is chosen, add a table-driven test over the encoding and state the choice in
      `docs/architecture.md` §6's outbound-mapping table, which currently documents `0`.
- [ ] Check how many of the author's four feeds actually declare `itunes:duration` before sizing
      this — it may be the whole of the remaining symptom or none of it. `ManualNextcloudProbe` and
      the parsed episode rows answer it.

### Step 3b — A remote *mark as unread* is currently read as "handled elsewhere"

**Found on 2026-08-13, live, and it is the mirror image of step 3.** Step 3 is about what our
*outbound* encoding puts in `position`/`total`. This is about the fact that our *inbound*
reconciliation ignores those fields entirely.

RePod's *mark as unread* does not delete an action and does not send a different type. It writes a
`PLAY` with `position = 0` (`markAs` in `src/utils/status.ts`), and reads it back as unplayed because
`hasEnded` requires `position > 0`. `reconcile` looks at the action **type** alone —
`TERMINAL_ACTION_TYPES = {DOWNLOAD, PLAY, DELETE}` — so it files that as `HANDLED_REMOTELY`.

Probed against the author's own server, with the two readings side by side. The second probe — five
episodes flipped from played back to unread — is the one that settles the shape:

```
2026-08-13T23:33:15  PLAY  guid=68e584b0…  position=0 total=2838   RePod: NOT played  ← DISAGREE
2026-08-13T23:33:13  PLAY  guid=690f68fc…  position=0 total=2398   RePod: NOT played  ← DISAGREE
2026-08-13T23:33:12  PLAY  guid=691a4895…  position=0 total=2766   RePod: NOT played  ← DISAGREE
2026-08-13T23:33:10  PLAY  guid=694c09de…  position=0 total=3082   RePod: NOT played  ← DISAGREE
2026-08-13T23:33:09  PLAY  guid=699d915a…  position=0 total=1854   RePod: NOT played  ← DISAGREE
2026-08-13T23:33:05  PLAY  guid=69af61b1…  position=1800 total=1800  RePod: played
```

**`total` is real and non-zero on every one of them.** Marking something unread that has ever been
played keeps the stored duration and zeroes only `position` (`markAs` reuses `action?.total`), so
this is not a corner case near an unset duration — it is *the* representation of "unread" for any
episode with a history, and five of the six actions in that window disagree with us.

It also means the two shapes are **distinguishable on the wire**, which the first probe could not
show: an explicit unread is `position = 0, total > 0`, while our own duration-less skip is
`position = 0, total = 0`.

**The consequence is the worst kind: it is silent and it is backwards.** An episode the user
deliberately marked *unread* in Nextcloud is the one Podsilo removes from *To decide* and files as
already handled — and because `HANDLED_REMOTELY` is terminal, no later sync ever revisits it.

- [ ] `reconcile` must read `position`/`total` for a `PLAY`, using RePod's rule, so a `PLAY` that is
      not *ended* is **not** terminal. `DOWNLOAD` and `DELETE` are unaffected.
- [ ] **How it interacts with D2 is now a choice rather than a knot.** Our own skip encoding sends
      `position = total = 0` when a feed declares no duration, and RePod's rule reads that as *not*
      handled — so a second Podsilo device would not see the skip. Two ways out, and the measurement
      above says both work:
      - **Settle D2 first** (give a duration-less skip a non-zero encoding), then adopt RePod's rule
        **verbatim**, with no special case on either side. Preferred.
      - **Or special-case the ambiguous shape**: `total == 0` means "handled, duration unknown" —
        ours — and stays terminal, while `position == 0 && total > 0` is an explicit unread and is
        not. Works today without touching the outbound encoding, at the cost of a branch that exists
        only because of our own past output, and which D2 would later make dead.
- [ ] Test both directions with the real shapes: `position=0 total=1800` (RePod unread),
      `position=2838 total=2838` (RePod played), `position=0 total=0` (our own duration-less skip).

### Step 4 — The `since` cursor compares two different clocks

- [ ] The server selects `timestamp_epoch > since` on **client-authored** timestamps; we persist the
      **server's** `time()` as the next `since`. Any action authored before our last pass is
      invisible to us permanently. This is not theoretical: RePod's `formatEpisodeTimestamp` emits
      **local time with no offset** (`getHours()`, not `getUTCHours()`) and gpoddersync parses it as
      UTC, so on the author's UTC+2 instance every RePod action is stored two hours *ahead* — which
      happens to save us, and would silently lose every action from a client whose clock is behind
      the server's.
- [ ] Proposed: **rewind the cursor by a fixed overlap** (a few minutes at least; a day is not
      expensive) when sending `since`, and rely on reconciliation being idempotent — which it already
      is, because a terminal local state ignores an incoming action. Re-delivery costs a few rows;
      a missed action costs a re-download.
- [ ] Test the case directly: an action whose authored timestamp is *older* than the stored cursor
      must still be reconciled.
- [ ] Do **not** start computing `since` from local device time. CLAUDE.md §11 forbids it and clock
      skew is the disease, not the cure.

### Step 5 — Verify identity end to end, then close

- [ ] Assert that the `guid` we post is byte-identical to the feed's raw `<guid>` text — rssparser
      gives us the parsed value and we store it untrimmed; RePod matches on `(string) $item->guid`.
      A divergence would put a phantom ledger row under a key no episode has, which looks exactly
      like "it did not show up in Podsilo". Cheap to assert with a fixture, and the probe from step 0
      confirms it against the real instance.
- [ ] Re-run the device test set and check the round trip by hand on the phone: mark in Podsilo →
      appears in RePod; mark in RePod → the episode leaves *To decide* in Podsilo. **Nothing in the
      JVM suite can prove this**, and #60 is a bug that only exists on the wire.

---

## 4. Decisions needed from the author

Seven. Steps 3, 3b and 5 are blocked on D2/D7; §7 is blocked on nothing, since D4 and D5 are settled.

| # | Question | Why it is not mine to decide |
|---|---|---|
| **D1** | Should the default sync interval stay at **4 hours**? | It is the difference between "eventually" and "in the background, usefully". 15 minutes is WorkManager's floor and Doze will stretch it anyway. But it is battery and traffic on the author's phone, and steps 1–2 already remove the *user-visible* delay, so this may be fine as it is. |
| **D2** | What do we post as `position`/`total` when the feed declares no duration? | CLAUDE.md §6 and ADR 0002 forbid inventing a duration; RePod requires both to be `> 0`. The narrow reading is that `position = total = 1` is not a *duration claim* but an "ended" marker, and is honest in a way that a fabricated 45 minutes is not. The alternative is accepting that duration-less episodes never show as played in Nextcloud. Either way it wants an ADR, because it is a change to a documented encoding. |
| **D3** | Do we want *downloaded* to be visible in Nextcloud at all? | We cannot have it as `DOWNLOAD` — see §5. The only mechanism that exists is emitting `PLAY` on download, which CLAUDE.md §5 forbids explicitly and for a good reason ("would assert something untrue and can trigger auto-delete in other clients"). Half of #60's title is this, and the honest answer may be to close that half as *won't fix* and say so in the issue. |
### Settled 2026-08-13

| # | Question | Settled as | Consequence |
|---|---|---|---|
| **D4** | Does *"apply Nextcloud's state"* ever **un**-mark an episode here? | **No.** It only ever marks episodes as played; it never marks one available again. | The ledger stays append-only — no delete, for the third time. The row's subtitle has to say so, because the button's name promises more. |
| **D5** | Does *"apply Nextcloud's state"* overwrite a local **`DOWNLOADED`** row? | **No.** The author's rule: *a remote play means the episode was played on another device, so no additional download is necessary here.* A `DOWNLOADED` row already guarantees that, so there is nothing for the remote action to add — and the server cannot restore what overwriting would destroy (§5). | The download record survives. See the note below: this is the reading of the author's rule, not a quote of it. |
| **D6** | Is a *force push* allowed to re-assert decisions Nextcloud has already seen? | **Yes**, chunked, behind the counted confirmation ADR 0013 established. | §7.2 trap 3 and 4 stand as written. |

| **D7** | What is a `PLAY` with `position = 0, total = 0` — ours, sent when a feed declares no duration? | It is the same field pair from both sides, and the two answers have to agree. If it means *not handled* (RePod's reading), step 3b is a clean rule and our duration-less skips stop propagating to a second device until D2 gives them a non-zero encoding. If it means *handled* (our current reading), an explicit **unread** from RePod keeps being swallowed unless the rule special-cases `total > 0`. Recommendation: settle D2 first — give a duration-less skip a non-zero encoding — and then RePod's rule can be adopted verbatim, with no special case on either side. |

**One line of interpretation, flagged rather than buried.** D5's answer states what a remote `PLAY`
*means* — no download needed here — rather than what it does to a `DOWNLOADED` row. Both states are
already terminal and neither downloads anything, so the rule is satisfied either way; the tie is
broken by keeping the fact the server structurally cannot hold. If the intent was the literal
overwrite, it is one line in `reconcile` and one line in the ADR.

---

## 5. The half of #60 that is not fixable here

> "The episodes marked as **downloaded** … are not shown as played in Nextcloud."

`EpisodeActionController::create` calls `filterOnlyPlays()` before saving **anything**:

```php
$episodeActionsArray = $this->filterOnlyPlays($episodeActionsArray);
```

A `DOWNLOAD` action is dropped and the endpoint returns `200 {"timestamp": …}` — the client cannot
even tell. This is ADR 0008, verified at source in Tier 3 and now re-verified against `main` on
2026-08-10. It is a property of the server, it has been true since gpoddersync 3.13.3, and no change
on this side alters it. `opodsync` *does* store `DOWNLOAD`, so a test against the CI server will
never reproduce it.

What that means in practice: Podsilo's local ledger remains the only record that this device has a
file, which is exactly what CLAUDE.md §11 says it must be, and the cross-client dedup requirement in
CLAUDE.md §1 is met by *skip* (`PLAY`) and not by *download*. Nothing is silently broken; a feature
that was assumed to exist does not. D3 decides whether we say so in the issue and leave it.

---

## 6. What is *not* wrong, so nobody re-checks it

Ruled out by reading, so the next person does not spend the afternoon there:

- **The endpoint, auth and body shape.** `index.php/apps/gpoddersync/episode_action/create`, HTTP
  Basic pre-emptive, bare JSON array. The server's `filterEpisodesFromRequestParams` keeps
  numeric-keyed params, which is what a top-level array decodes to.
- **Action-name casing.** We send `PLAY`; the filter lower-cases before comparing; the reader
  upper-cases before storing.
- **Omitted optional fields.** `explicitNulls = false`, so `guid`/`started`/`position`/`total` are
  omitted rather than sent as `null` — which matters, because the server's reader uses `??` and an
  explicit `null` is not the same as absence.
- **Timestamp format.** We emit bare ISO-8601 UTC; the server parses with an explicit UTC zone.
  Round-trips correctly. (RePod's own timestamps are the ones that are wrong — §4 — and that is not
  ours to fix.)
- **Reconciliation.** Terminal states are never revisited, duplicates within a batch resolve by
  timestamp, and an action for an unsubscribed feed is still processed. All tested.

---

## 7. Two directional sync buttons in Settings

The author's ask, verbatim: *two buttons in the settings to (1) sync Nextcloud's state to Podsilo
and (2) sync Podsilo's state to Nextcloud — it's up to the user to decide which state is correct.*

These are **not** a workaround for §3; build them after steps 1 and 2, or the app ships a manual
button for a bug it should not have. They earn their place afterwards for a different reason: a
shared append-only log with two writers has no automatic answer to "these two disagree", and a
person looking at both screens does.

### 7.1 What each button means, precisely

Normal sync is *incremental and deferential*: it pushes only rows the outbox has not sent
(`syncedToServer = false`), pulls only actions newer than the stored cursor, and **never overrides a
terminal local state**. Each button drops exactly one of those three properties.

| | **Apply Nextcloud's state here** | **Send this device's state to Nextcloud** |
|---|---|---|
| Direction | pull | push |
| Scope | `GET episode_action?since=0` — the entire log | every ledger row that maps to an action, **including already-synced ones** |
| Overrides | **nothing** — see below | nothing local; it re-asserts local state on the server |
| Writes locally | ledger rows for episodes with no decision yet | only `syncedToServer` |
| Writes remotely | nothing | `PLAY` per `SKIPPED` row (and `DOWNLOAD` per `DOWNLOADED` row, which the server drops — §5) |
| Reversible | it adds decisions, so effectively no | **no** — the log is append-only and other clients act on it |

`since=0` is the thing CLAUDE.md §5 warns about: *"returns every action from every client ever
recorded, unpaginated, growing without bound"*. That is fine **once, on a button press** — the
author's instance held ~3,022 actions at the last count — and is exactly why it must not become the
normal path.

### 7.1a What D4 and D5 collapse the pull into

Worth stating plainly, because it removes most of this section's risk. The three terminal states are
`DOWNLOADED`, `SKIPPED` and `HANDLED_REMOTELY`. D5 says leave `DOWNLOADED` alone; D4 says never
un-mark, which is exactly "leave `SKIPPED` and `HANDLED_REMOTELY` alone". So all three stay
untouched — and that is **precisely the rule `reconcile` already implements**:

```kotlin
if (existing != null && existing.state in TERMINAL_STATES) return@mapNotNull null
```

**"Apply Nextcloud's state here" is therefore the ordinary reconciliation run over the whole log
instead of over the delta since the cursor.** It is `since = 0` and nothing else. Consequences:

- **No new conflict rule exists to get wrong**, so CLAUDE.md §9's ask-before-changing-conflict-rules
  clause is not triggered by the pull at all — only by the push, which re-sends what the server has
  already seen. The ADR shrinks accordingly.
- The resulting behaviour is provable rather than argued:

  | Local row | Remote `PLAY`/`DOWNLOAD`/`DELETE` | Result |
  |---|---|---|
  | none (undecided) | → | `HANDLED_REMOTELY` — leaves *To decide* |
  | `QUEUED` / `DOWNLOADING` / `ERROR` | → | `HANDLED_REMOTELY`, in-flight work cancelled (`docs/UI.md` §B14.1) — which is the author's rule exactly: no additional download is necessary |
  | `DOWNLOADED` | → | unchanged (D5) |
  | `SKIPPED` / `HANDLED_REMOTELY` | → | unchanged (D4) |

- The button can only ever *reduce* the To-decide list. It cannot re-open a decision, cannot delete a
  row, and cannot lose a download record. That is a small enough promise to put in the subtitle.

### 7.2 The traps

The first two are **settled** (D4, D5) and are kept here as the reasoning behind §7.1a, so that a
later reader does not "improve" the button back into the dangerous version.

1. **"Apply Nextcloud's state" must not mean "delete what Nextcloud has not got".** The obvious
   reading of the button is *make Podsilo look like Nextcloud*, and the honest implementation of
   that includes un-marking episodes the server has no action for. That needs a ledger delete, which
   the project has now refused three times on the same grounds: the row is the only thing standing
   between the user and a second download of a file they already have (CLAUDE.md §11). The button's
   subtitle says so — *"Nothing is unmarked"* — because the name promises more than it does.
2. **A remote `PLAY` is not evidence that a local download did not happen.** gpoddersync discards
   `DOWNLOAD` (§5), so the server is *incapable* of representing the one state this device knows
   best. Applying the server's state naively would turn every `DOWNLOADED` row into
   `HANDLED_REMOTELY` on the first press — a total, silent loss of the download history, caused by a
   limitation rather than by a disagreement. `writtenFileName` survives either way (reconcile already
   carries it forward), so the duplicate guard was never at risk; what was at risk is the author
   knowing which episodes they have.
3. **Volume.** A force push after a *mark all as played* is thousands of actions in one POST body.
   `EpisodeActionSaver` loops per action with an insert-then-update-on-conflict, and PHP has
   `post_max_size` (8 MB by default) between us and it. **Chunk the push** — a few hundred actions
   per request — mark each chunk synced only on its own 2xx, and report progress. Worth noting that
   **the normal outbox push has the same latent problem today** (one POST for everything unsynced),
   so the chunking belongs in the shared push path and fixes both.
4. **The push is not undoable and reaches other devices.** Same class as the bulk *mark as played*
   in `docs/UI.md` §7, and it gets the same safeguard: a dialog naming the count, saying in words
   that the state goes to Nextcloud and to the author's other clients, before anything is sent.

### 7.3 Where they go, and what they say

S4's **NEXTCLOUD** group, below *Last sync* — that row already answers "when did this last happen",
so "make it happen, in this direction" belongs next to it, not in a new group. Not on S7: that
screen's *Sync now* is the ordinary pass, and putting three sync buttons on one screen invites the
wrong one.

```
NEXTCLOUD
  Instance            https://cloud.example.org
  Account             user · connected 31 Jul 2026
  Last sync           10 min ago · 3 actions pending      ›
  ─────────────────────────────────────────────────────
  Apply Nextcloud's state here                           ›
    Marks episodes played here if they are played in
    Nextcloud. Nothing is unmarked.
  Send this device's state to Nextcloud                  ›
    Re-sends every decision made here, including ones
    already sent.
```

Rules, all of which the screen already has a pattern for:

- **Both rows are disabled until an account is connected**, exactly as *Restore from backup* is
  (`docs/UI.md` §7), and re-checked in the view model rather than only in the row.
- **Both go dead while either runs**, reusing the `archiveBusy` shape rather than inventing a second
  one — a second tap must not start a second pass.
- **Each opens a counted confirmation first.** The push's count is a local query and is instant. The
  pull's is not: it needs the fetch to have happened, so its dialog is two-phase — fetch, then *"3,022
  actions from Nextcloud. 87 change something here."*, then apply. That is worth the extra step,
  because "87" is the number that tells the author whether they pressed the right button.
- **The outcome is a snackbar naming what happened**, and every failure is an S8 entry — which is why
  step 0 comes first.

### 7.4 Implementation order

Nothing here needs a new dependency, a schema change or a migration.

- [ ] **ADR, and a small one.** §7.1a removed the conflict-rule change from the pull, so what is left
      to record is: the two buttons are *directional* rather than one "resolve conflicts" action
      because only the user knows which side is right; the pull is `since = 0` over the unchanged
      reconciliation; the push deliberately re-asserts what the server has already seen; and D4/D5's
      bounds, so nobody widens them later. Write it with the code, not before it.
- [ ] **`:core:sync` — the two passes.** `SyncOrchestrator` grows `forcePull()` and `forcePush()`
      beside `sync()`, sharing the existing pull/push helpers. `forcePull()` is the existing pull with
      `since = 0` — **if it needs a new branch in `reconcile`, something has drifted from D4/D5.**
      Pure JVM, driven by the existing in-memory fakes: that the pull leaves all three terminal states
      alone and finalises the in-flight ones, that it decides episodes the cursor had skipped, that a
      force push includes already-synced rows, and that a chunk failing mid-way leaves the earlier
      chunks marked and the rest unmarked.
- [ ] **Chunking in the shared push path**, with a test at a few thousand rows asserting the request
      count and that a failed chunk does not mark its rows.
- [ ] **`:app` — one worker, not three.** `SyncWorker` takes a `KEY_SYNC_MODE` input
      (`NORMAL` / `FORCE_PULL` / `FORCE_PUSH`), the same shape `FeedRefreshWorker.KEY_FEED_URL`
      already uses. `WorkScheduler` gains the two enqueues; unique work by name still applies, so a
      second press joins rather than duplicates.
- [ ] **`:feature:settings` — the rows, the two dialogs, the busy state**, and the preview type for
      the pull's second phase. Compose tests: disabled without an account, dead while busy, the count
      is named before anything is written, and dismissing writes nothing.
- [ ] **`docs/UI.md` §7 and §B5** — the rows, the events and the states, in the same pass as the code.
- [ ] **Verify on the device.** Mark an episode played in RePod only, press *Apply Nextcloud's state
      here*, watch it leave *To decide*. Then mark one here, press *Send this device's state*, and
      watch RePod grey it out. Nothing in the JVM suite can prove either.
