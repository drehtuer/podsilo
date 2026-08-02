# 0018 — The database backup is a zipped SQLite file, restored row by row

Date: 2026-08-02
Status: accepted

## Context

The author asked for "an import/export option in settings — just dump the database as zip and load
as zip should be fine."

The reason this is worth building is narrower than "backups are nice". Most of the database is
disposable: `feeds` is a read-only mirror of Nextcloud, `episodes` is a parsed-RSS cache that can be
rebuilt at any time. `episode_ledger` is not. CLAUDE.md §5 calls it "the ONE table that must never be
lost", and the parts of it the server has never seen exist on the phone and nowhere else:

- **`DOWNLOAD` actions.** Nextcloud's gpoddersync filters posted actions down to `play` and discards
  the rest (`docs/decisions/0008`, confirmed against a real server). Every "I have this episode"
  record is local-only, permanently.
- **Anything still in the outbox** (`syncedToServer = false`).
- **`writtenFileName`**, which is what stops a retry writing a second copy of a file (CLAUDE.md §6).

A lost phone takes all of that with it, and a re-install would re-offer hundreds of already-handled
episodes as new.

## Decision

### The archive is the raw `podsilo.db`, zipped, plus a `.properties` manifest

Taken literally from the request, and it buys schema evolution for nothing: an archive written by an
older build is opened through Room's own `PODSILO_MIGRATIONS`. A hand-written JSON dump would need a
second serialisation format, versioned and migrated in parallel with the real schema, to achieve
exactly the same thing — precisely the kind of bespoke machinery CLAUDE.md §3 rules out.

The manifest carries the archive format version, the schema version, the export timestamp, and the
row counts.

### The restore copies rows into the live database rather than swapping the file

Replacing `podsilo.db` under a running Room instance means closing and rebuilding the singleton, and
every `Flow` the UI is collecting is bound to that instance — so it would need an app restart to be
safe. Instead the archive is opened as a *second* Room instance (which is what runs the migrations),
its tables are read, and the live database is replaced inside **one** `withTransaction`. One database
object for the app's lifetime, all-or-nothing semantics, and Room's invalidation tracker updates
every screen on its own.

### Two integrity gates, because SQLite fails open

`SQLiteOpenHelper`'s default corruption handler **deletes and recreates** a database it cannot open.
So a truncated or damaged archive does not throw — Room hands back a perfectly valid *empty*
database, and a naive restore would then faithfully replace the user's ledger with nothing. The
first Robolectric run of `a corrupt archive leaves the existing data exactly as it was` did exactly
that, which is the only reason this is written down rather than discovered on a real phone.

1. **The SQLite header** is checked before Room is allowed near the file.
2. **The manifest's row counts** are compared against what was actually read, before the transaction
   opens. This catches damage the header cannot see.

Either mismatch is `UNREADABLE`, and nothing is written.

### The failure modes are named separately

`NOT_AN_ARCHIVE`, `NEWER_SCHEMA`, `UNREADABLE`, `WRITE_FAILED`. "That backup came from a newer
Podsilo, update the app" and "that isn't a backup" are different instructions, and merging them sends
the user looking for a file that is sitting right there.

### The restore warning comes before the file picker

Same principle as the bulk-mark preview (`docs/decisions/0013`): a destructive, non-undoable
operation says what it will do, in words, before it does it. Nothing about the warning depends on
*which* file is chosen, so it costs nothing to show it first — and it means no file is ever read
until the user has agreed to the consequences.

The warning's last line is reassurance that happens to be true: the Nextcloud action log is untouched
by a restore, and because the restored `SyncState` carries the archive's older `since` cursor, the
next sync pulls everything that happened after the backup was taken and folds it back in.

## Consequences

- **The archive contains no credentials.** The Nextcloud app password lives in DataStore behind a
  Keystore-backed cipher (`docs/decisions/0010`), not in the database, so a restored install must be
  reconnected. That is the right trade for a file the user may copy to a PC or a cloud drive. The zip
  *does* contain feed URLs, episode titles and show notes — the subscription list in readable form.
- A restore does **not** touch downloaded files. Consistent with the rest of the app: once a file is
  in the user's folder it belongs to them and their player (CLAUDE.md §1).
- The export is unencrypted and uncompressed-by-default zip deflate. No key management, nothing to
  lose, and nothing secret inside.
- Not covered by tests: a *very* large database. The restore reads each table into memory before
  writing it back in batches of 500. For a personal library this is measured in megabytes; if the
  episode table ever grows to a size where that matters, the read needs chunking too.
