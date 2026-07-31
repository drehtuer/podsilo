# 0011 — A `DownloadTarget` port in front of the SAF write

## Status

Accepted (author-approved before implementation). Introduced while building `:core:download`'s
pipeline (Tier 4b).

## Context

CLAUDE.md §6/§11 mandate a fixed download pipeline:

```
download to app cache (java.io.File) → verify → rewrite tags → copy into the SAF tree → delete cache
```

Everything up to the last-but-one step is plain JVM work. The SAF copy is not: `DocumentFile` and
`ContentResolver.openOutputStream` need a live `DocumentsProvider` behind a tree URI, which exists
on a device and in no headless test runner — Robolectric included.

That matters more here than it would elsewhere, because the pipeline is where the branching lives:
collision suffixing, extension resolution from `Content-Type`, reuse of `writtenFileName` on retry,
best-effort tagging, cache cleanup, and the classification of every failure into retryable or not.
Wiring it directly to `DocumentFile` would make all of that testable only under Tier 2 — and Tier 2
has never successfully booted an emulator on this project's host (`docs/dev-environment.md` §6). In
practice it would mean untested indefinitely.

CLAUDE.md §3 warns specifically against "wrapping a library in a hand-written abstraction layer 'in
case we swap it later'", so this needed to be a deliberate decision rather than a reflex.

## Decision

Introduce
[`DownloadTarget`](../../core/download/src/main/kotlin/net/drehtuer/podsilo/core/download/DownloadTarget.kt),
a two-method interface — `existingNames(folder)` and `deliver(folder, fileName, source)` — with
exactly one production implementation,
[`SafDownloadTarget`](../../core/download/src/main/kotlin/net/drehtuer/podsilo/core/download/SafDownloadTarget.kt),
and a `FakeDownloadTarget` over a temp directory in tests.

This is **a test seam, not a portability layer**. There is no second implementation planned and no
intention to swap SAF for anything; the interface exists because the alternative is an untestable
pipeline. Its shape is chosen to keep it thin: no `Uri`, no `DocumentFile`, no Android type crosses
it, and it exposes only the two questions the pipeline actually asks.

Put to the author as an explicit choice against the "call `DocumentFile` directly" alternative, and
accepted.

## Consequences

- `EpisodeDownloader` — the whole pipeline — is covered by Tier 1 tests: 11 cases including
  collision suffixing, `Content-Type` beating the URL extension, retry reusing the recorded name,
  a tag failure still delivering, and a revoked folder grant failing without a retry.
- **`SafDownloadTarget` itself is not unit-tested.** It is exercised only by running the app. Same
  honesty as ADR 0010's Keystore cipher: the seam moves the untested surface down to the smallest
  possible piece, it does not eliminate it.
- `existingNames` is *not* a de-duplication check and its KDoc says so. Whether a file is still in
  the folder says nothing about whether the episode was handled — the ledger is the only authority
  (CLAUDE.md §11's single most important invariant). It exists purely so two different episodes
  don't fight over one name.
- Both methods are `suspend`, so `SafDownloadTarget` can read the current folder URI from
  `SettingsRepository` rather than being handed a snapshot that may be stale by the time it writes.
- A lost grant (revoked, SD card removed, app data cleared) surfaces as
  `DownloadFolderUnavailableException` inside a `Result`, which the pipeline reports as a
  **non-retryable** failure — the user has to re-pick the folder, so backoff would just spin.
