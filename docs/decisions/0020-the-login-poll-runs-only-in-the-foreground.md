# 0020 — The Login Flow v2 poll runs only while the app is in the foreground

Date: 2026-08-09
Status: accepted

## Context

Connecting to Nextcloud was **impossible** on a Pixel 10a running Android 17 (SDK 37). Every attempt
ended with S5 showing *"Can't reach that address. Check the spelling and your network."* while the
browser plainly said access had been granted. Reproducible on Wi-Fi and on mobile data alike.

`docs/decisions/0019`'s error-log write point is what made it diagnosable. The entry read:

```
AUTH ×3
Connecting to Nextcloud failed: UNREACHABLE
Unable to resolve host "cloud.drehtuer.net": No address associated with hostname
```

### Everything that was not the cause

Ruled out on the device, each with evidence, because the plausible explanations were all wrong:

- **Permissions.** `INTERNET` and `ACCESS_NETWORK_STATE` both `granted=true`.
- **System network policy.** The app's uid appears nowhere in `dumpsys netpolicy`; Data Saver is
  disabled; the App Standby bucket is **10 (ACTIVE)**; `RUN_IN_BACKGROUND` is `allow`.
- **DNS.** The phone resolves the host on Wi-Fi *and* on mobile-only. The mobile network is IPv6-only
  with 464XLAT (`rmnet16` has no IPv4; `v4-rmnet16` is the CLAT), and both the IPv4-via-CLAT and IPv6
  paths reach the server. The host is dual-stack, so no record is missing.
- **A stray poll hostname.** The server returns `https://cloud.drehtuer.net/login/v2/poll` — the same
  host the user typed.
- **Sideloading or signing.** The app installs, runs, and `start()` reaches the server **every time**:
  the browser opens on the grant page. An app that could not use the network could not do that once.

### What it actually was

`start()` succeeds, so the app resolves the host fine — and then hands the user to their browser,
which **backgrounds this app**. `poll()` then runs from a backgrounded process, and on this device
that process cannot resolve the host.

One `UnknownHostException` was enough to end everything, because the whole `repeat(maxPollAttempts)`
loop sat inside a single `runCatchingRequest`: a failure on attempt 1 abandoned all 200. The user
completed the grant in the browser and returned to an app that had already given up, blaming an
address that was correct.

## Decision

**The poll runs only while the connection UI is on screen**, and the app makes no network call at all
while backgrounded during a login.

The author's framing settled it: *"Is there really a need to poll in background? After all, a user
would bring back the app to the foreground once he completed granting access."* No, there is not.
Login Flow v2 is a loop watching for something the **user** does in a browser, and its result is only
usable once they come back. Polling behind their back buys nothing and costs the failure above.

This removes the failing condition rather than working around it. No retry policy, no backoff, no
foreground service, no permission: the call that could not succeed is simply not made.

### How

- `ConnectEvent.ForegroundChanged(inForeground)` is emitted by `ConnectDialog` from
  `LifecycleStartEffect` — **`ON_START`/`ON_STOP`, not resume/pause.** The browser covering the app is
  a stop; a system dialog or the notification shade is only a pause, and dropping the poll for a shade
  pull-down would be a bug of its own.
- `ConnectViewModel` splits the flow in two: `connect()` starts it and opens the browser; `awaitGrant()`
  polls, verifies and confirms. The started `LoginFlow` is held in `pendingFlow` across the trip to the
  browser, so returning resumes rather than restarts.
- **`isForeground` starts `false`.** A host that forgets to wire the lifecycle then polls *never*,
  which is loud, instead of polling in the background, which is the bug this record exists to prevent.
- The lifecycle is observed by the **dialog**, not the view model — a view model has no business
  watching a lifecycle, and the activity does not know whether this dialog is on screen.

### And, as a second layer, a blip no longer ends the flow

Independently of the above, `poll()` now guards `execute()` per attempt: a network failure on one
attempt records itself and the loop continues. Only `execute()` is guarded — a malformed body or an
unexpected status still fails at once, because those do not fix themselves by asking again. An
exhausted poll that never reached the server reports the **network** failure rather than `ABANDONED`,
which blamed the user for not completing an authorization they may well have completed.

Foreground-gating is the fix; this is the belt to its braces, for a blip while the user is watching.

## Consequences

- A user who grants access and never returns to the app is not connected. Correct: they have to come
  back to use it, and the flow completes the instant they do.
- The poll's wall-clock lifetime is now the user's attention span rather than 200 × 3 s of continuous
  asking. Nextcloud's flow token outlives a normal trip to the browser, so this is not a new limit.
- Battery and data: a login now costs the handful of requests it actually needs.
- **Verified on the device that could not connect at all.** With the fix installed, returning to the
  app after granting reached *"Connect as drehtuer?"* on the first attempt — the dialog that only
  appears after `poll()` returns credentials *and* `verifyGpodderSync()` returns 200.

## Alternatives rejected

- **A foreground service for the duration of the login.** It would keep the background poll working,
  at the cost of a notification and `FOREGROUND_SERVICE_DATA_SYNC` for something that needs neither.
  Making an unnecessary call possible is worse than not making it.
- **Retry/backoff around the background poll.** Retrying a call that cannot succeed while the process
  is backgrounded burns battery to arrive at the same failure more slowly.
- **Treating it as a network-security or DNS problem.** Investigated first and disproven — see the
  list above. An early theory that it was a Wi-Fi/mobile transition was reproduced by *toggling Wi-Fi
  during the test*, i.e. by the investigator's own interference, and the author's "reproducible with
  Wi-Fi on and mobile-only" is what killed it. Reproducing a symptom is not the same as reproducing
  the bug.
