# 0019 — The Nextcloud account is confirmed before it is stored

Date: 2026-08-03
Status: accepted

## Context

The author reported: *"the nextcloud login always sets the username podsilo. no name should be
assumed, the user should be able to select the user."*

The first half of that turned out not to be true of the code, and the investigation is worth
recording because the conclusion is counter-intuitive.

**Podsilo assumes nothing.** There is no `"podsilo"` literal anywhere outside
`settings.gradle.kts`'s project name. The stored username is `LoginPollDto.loginName` — whatever the
server returns — and `setNextcloudCredentials` overwrites all three fields together, so no stale
value can survive a reconnect. Verified by probing the real server: the flow URL redirects to
`/login/v2/flow?user=&direct=0`, and the `user` parameter comes back **empty** for a client with no
cookies. The app sends no name.

**The browser decides.** Reproduced on the device: opening the flow URL showed *"Please log in before
granting Podsilo access"*, and tapping **Log in** did not show a login form. It went straight to
*"Account access — Currently logged in as podsilo (podsilo)"* with one **Grant access** button.
Chrome held a Nextcloud session from an earlier login, and Login Flow v2 offers **no account chooser
and no way to switch** once a session exists.

So the account is chosen by the browser's cookie jar, and the app has no say in it. That is a
Nextcloud design decision, not something a query parameter can override.

### Why this is worth fixing rather than documenting

Because the app was making it silent. The flow completed, credentials were persisted, and the name
was never shown. From then on every triage decision writes `DOWNLOAD` and `PLAY` actions into that
account's log — and CLAUDE.md §5's whole point is that those actions are how other clients learn an
episode is handled. Marking someone's episodes played is not retractable through this API.

The author has exactly this hazard: a `podsilo` test account and a personal account on the same
server, with a standing instruction that the personal one must never have episodes marked played.

## Decision

**A granted flow no longer connects. It names the account and waits.**

`ConnectUiState.Phase.ConfirmingAccount(loginName)` sits between the verified `GET /subscriptions`
and `setNextcloudCredentials`. **Connect** stores; **Use a different account** does not.

Three details that carry the weight:

1. **The credentials are held in a private field on the view model, not in the UI state.**
   `ConnectUiState` is a data class whose `toString` a crash reporter, a log line or a Compose state
   inspector will print without being asked, and the app password must never be printed (CLAUDE.md
   §5). The UI is given the login name and nothing else.

2. ***Use a different account* opens the server root, not the flow URL.** Retrying the flow against a
   live session returns the same account every time, so a plain "try again" would be a loop with no
   exit. The session is the thing that has to change, and only the browser can change it. The dialog
   says that in words next to the address field afterwards, because the useful instruction is about
   the browser tab that just opened.

3. **Rejecting and cancelling both clear the pending credentials.** Otherwise *Use a different
   account* followed by a confirmation would store the very account that was just refused. Both paths
   are tested; that test is the reason the field is cleared in three places rather than one.

## Consequences

- One extra tap on every connect. Accepted: connecting is rare and the failure it prevents is
  permanent.
- **The rejected app password stays live on the server.** Nextcloud lists it under *Security* and the
  user can revoke it. Revoking it automatically (`DELETE /ocs/v2.php/core/apppassword` with the
  credentials in hand) is a real improvement and is filed in `docs/backlog.md` rather than built
  here — it is a new endpoint with its own failure modes, on a path whose whole job is to not store
  things.
- The app still cannot offer an account chooser, and no amount of app-side work will change that.
  What it can do is refuse to act on an account the user has not looked at.

## Alternatives rejected

- **A WebView with a cleared cookie jar**, as the official Nextcloud Android app does. It would
  genuinely force a fresh login and a real account choice — and it would put a password field inside
  Podsilo's own process. Login Flow v2 exists precisely so the app never handles the account
  password (ADR 0010); adopting a WebView would hand that property back for a convenience.
- **An incognito Custom Tab.** Not a public API; Chrome restricts the incognito extra to first-party
  callers. Would fail silently on the devices that matter.
- **A username/password form** using an app password created by hand in Nextcloud settings. Nextcloud
  itself offers this as *"Alternative log in using app password"* on the flow page, and it would let
  the user name the account directly. Rejected here because it reverses ADR 0010 and CLAUDE.md's
  "there is no username/password form in this module and there must never be one" — that is the
  author's call to make, not a detail to slip into a bug fix.
