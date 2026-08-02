# 0006 — `com.github.Adonai:jaudiotagger` (JitPack fork) for tag writing

## Status

Accepted.

## Context

CLAUDE.md §3/§6 names jaudiotagger for audio tag writing ("mature, covers MP3/MP4/OGG/FLAC, LGPL —
compatible with our GPLv3... verify licence + Android support"), with the explicit instruction to
confirm the licence and Android compatibility before committing to it, and to record the choice
here.

## What was found

- **Canonical artifact** (`net.jthink:jaudiotagger`, Maven Central): latest version `3.0.1`,
  published **2021-10-13** — no release in five years, same staleness pattern as Stalla
  (`docs/decisions/0005`). Licence LGPL (confirmed via upstream `license.txt`: LGPL 2.1-or-later,
  Paul Taylor). Android compatibility is **not** confirmed for this artifact — jaudiotagger has a
  known history of referencing desktop-only JDK APIs (`javax.sound`, AWT) that don't exist on
  Android, which is exactly why Android-focused forks exist at all.
- **AntennaPod does not use jaudiotagger.** Checked AntennaPod's actual current build files
  (`app/build.gradle`, `parser/media/build.gradle` on `develop`) directly — AntennaPod hand-rolls
  its own ID3-chapter reader and depends only on `commons-io`; it never writes tags. There is no
  AntennaPod reference implementation to check tag-writing details against, unlike the gpodder-sync
  conventions in `docs/decisions/0002`.
- **`com.github.Adonai:jaudiotagger`** (GitHub: `Kaned1as/jaudiotagger`, published via JitPack): a
  fork explicitly targeting an Android-compatible branch, adding mp4-dash/opus support. Tag
  `2.3.15` exists and JitPack has already built it successfully (`"2.3.15": "ok"` per JitPack's own
  build-status API). Licence confirmed via the fork's own `license.txt`: **LGPL 2.1-or-later**,
  original copyright retained — same licence as upstream, compatible with GPLv3 per CLAUDE.md §2.
  Last commit 2023-07 — aging, but the only option here that specifically targets Android.
- **A more actively maintained fork exists** (`RouHim/jaudiotagger`, pushed as recently as
  2026-07-29) but its own README states it **removed Android compatibility** while modernising to
  newer Java targets — disqualifying for this project regardless of freshness.

## Decision

Use `com.github.Adonai:jaudiotagger:2.3.15` via JitPack. Put to the author as a question — given
both the staleness of the canonical artifact and the introduction of JitPack as a new dependency
source with a different trust model than Maven Central (it builds directly from GitHub source, not
a registry-reviewed release) — and accepted as recommended.

## Consequences

- `settings.gradle.kts`'s `dependencyResolutionManagement.repositories` needs
  `maven { url = uri("https://jitpack.io") }` added, in addition to `google()`/`mavenCentral()`.
  This is a new *kind* of dependency source for this project, worth knowing about even though the
  library itself was pre-approved in principle by CLAUDE.md §3.
- The exact `javax.sound`/AWT incompatibility this fork works around was not independently
  line-by-line verified against upstream's diff — if tag writing fails at runtime on-device for a
  container format this fork claims to support, re-check that specific code path first.
- Tag writing remains **best-effort** per CLAUDE.md §6: a failure here must never block delivering
  the downloaded file or block marking the episode `DOWNLOADED`.

## Which formats it actually handles (2026-08-02)

Read off `SupportedFileFormat` in the 2.3.15 fork, prompted by the question *"are there formats that
only support a subset of features, and is that handled?"*

| Container | Readable | Accepts artwork | Notes |
|---|---|---|---|
| MP3 | ✅ | ✅ ID3v2 `APIC` | The overwhelming majority of podcast enclosures |
| M4A / MP4 / M4B | ✅ | ✅ `covr` atom | The usual AAC-in-MP4 delivery |
| OGG (Vorbis) | ✅ | ✅ `METADATA_BLOCK_PICTURE` | |
| Opus | ✅ | ✅ (Vorbis comments) | |
| FLAC | ✅ | ✅ `PICTURE` | |
| WAV, AIFF | ✅ | ✅ via an ID3 chunk | Rare for podcasts |
| WMA, DSF, RA/RM | ✅ | varies | Not seen in podcast feeds |
| **Raw `.aac` (ADTS)** | ❌ | ❌ | **Not a supported format at all** |

**The one real gap is raw `.aac`,** which CLAUDE.md §6 explicitly lists among the extensions to
expect ("expect `m4a`, `aac`, `ogg`, `opus`"). A raw ADTS stream has no tag container to write into,
so `AudioFileIO.read()` throws and the episode is delivered **untagged and without a cover**.

That is the documented behaviour rather than a bug — §6 requires tag writing to be best-effort and
forbids losing a download over it — but it was previously undocumented, so "why has this one file no
tags?" had no answer. Note that `.aac` in podcast feeds is almost always *AAC in an MP4 container*
served as `.m4a`, which works; genuinely raw ADTS is rare.

### How partial support is reported

- **Per field**: `writeFields` sets each key independently and collects the ones the container
  refuses into `TagWriteOutcome.PartialSuccess.skippedFields`.
- **Artwork**: reported the same way, via `PartialSuccess.artworkSkipped`. This was added after the
  cover-art feature shipped with artwork failures *silent* — every other field's failure was visible
  and artwork's was not, which made "why has this episode no cover?" unanswerable.
- **Whole container unreadable**: `TagWriteOutcome.Failure`, and the episode is still delivered.

"The file already had its own cover" is deliberately **not** a skip: that is the intended outcome of
the feature, not a limitation, and conflating the two would make the flag useless for spotting real
container problems.

### Still untested

There are no non-MP3 fixtures in the suite — `audio/silence.mp3` is the only one — so M4A, OGG and
Opus tagging is supported *by the library* but unexercised *by us*. Generating those fixtures needs
an encoder the dev container does not have. Recorded in `docs/backlog.md`.
