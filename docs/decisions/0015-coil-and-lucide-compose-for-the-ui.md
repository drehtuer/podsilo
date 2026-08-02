# 0015 — Coil for image loading, Lucide Compose for icons

## Status

**Accepted 2026-08-01.** Two dependencies the Compose UI cannot be built without, neither
pre-approved by CLAUDE.md §3, both put to the author and accepted. Resolves two thirds of
`docs/architecture.md` §12's dependency item; the third (time types) is `docs/decisions/0016`.

## Context

CLAUDE.md §3 requires that any dependency outside its mandated table is proposed — with what it
replaces, its licence, and its release freshness — rather than added quietly.

**Nothing in the repository loads a remote image.** S1, S2 and S3 all render feed and episode
artwork from a URL (`Feed.imageUrl`, and the episode image when a feed supplies one). Without a
loader there is no artwork slot at all, only `docs/UI.md` §18's monogram fallback tile — which is
designed as the *absent-artwork* case, not as the normal one.

**Icons ship as vector drawables, not SVG.** Android renders no SVG at runtime; the platform format
is `VectorDrawable` and Compose's is `ImageVector` (`docs/UI_interface.md` §17). The 27 Lucide SVGs
in `assets/icons/` are source material, never shipped assets.

## Decision

### Coil for image loading

`io.coil-kt.coil3:coil-compose`, Apache-2.0, actively maintained.

- It is the conventional Compose choice, and it sits on the **OkHttp already pinned** in the version
  catalog rather than introducing a second HTTP stack — the deciding factor over Glide, which brings
  its own.
- Memory + disk caching, lifecycle-aware cancellation and `AsyncImage` come with it. Hand-rolling
  any of that is precisely what CLAUDE.md §3 exists to prevent.
- Feed artwork is fetched from arbitrary third-party hosts. That is the one privacy-relevant network
  call the app makes that is not to Nextcloud or a subscribed feed, and it is inherent to showing
  artwork at all — worth knowing, not worth avoiding, since the feed host already sees the feed
  fetch.

### Lucide's Compose artifact for icons

Per `docs/UI.md` §18's table, which stays the canonical icon→meaning mapping and now functions as an
**allow-list rather than a manifest of files**.

The alternative was hand-converting 27 SVGs through Vector Asset Studio, then keeping the generated
`res/drawable/ic_*.xml` names in step with §18's table by hand and re-converting whenever the set
changes. That is a hand-maintained pipeline for something a library already does — the same rule
that decided rssparser (0005) and jaudiotagger (0006).

The trade accepted: one dependency carrying ~1.7k icons where 27 are used, against 27 checked-in XML
files that would be ours to maintain. R8 removes what is not referenced.

## Consequences

- Both get pinned versions in `gradle/libs.versions.toml` and rows in `docs/third-party.md`.
- `assets/icons/` stays in the repository as design source and as the fallback path if the artifact
  ever becomes unusable; it is not wired into the build.
- Icon sizing rules are unchanged and still ours to enforce: never below ~20 dp, never scaled with
  the font scale, 24 dp glyph inside a ≥ 48 dp target (`docs/UI_interface.md` §17).
- **Artwork needs a content description in every case** — including the monogram tile, which
  announces "cover art for &lt;podcast&gt;" like real artwork, never "no image".
- Coil's cache is not the download folder and has nothing to do with it. Artwork is app-private
  cache; CLAUDE.md §1's "no file lifecycle management" non-goal is about the user's episode files
  and is untouched by this.

## Amendment (2026-08-02) — what the artifact actually ships

Written before the dependency had ever been resolved, this ADR assumed
`com.composables:icons-lucide-android` exposes `ImageVector` objects, as `Icons.Filled.*` does.

It does not. Its `classes.jar` is **empty** (22 bytes); the artifact is a pack of ~1,700
`VectorDrawable` **XML resources** under `res/drawable/lucide_ic_*.xml`, with package
`com.composables.icons.lucide`. So the call site is

```kotlin
painterResource(LucideR.drawable.lucide_ic_arrow_left)
```

and the icon "constants" are `@DrawableRes Int`s rather than `ImageVector`s.

**Every reason for the decision survives this** — it is still one dependency against 27
hand-converted files we would maintain, still ISC/MIT, still one weight everywhere, and R8 still
strips what is not referenced. Only the type at the call site differs.

Two consequences that follow from it, both real:

- **A wrong name is a runtime `0`, not a compile error**, because resource ids are looked up rather
  than referenced as objects. `PodsiloIconsTest` asserts every entry resolves to a non-zero id, and
  that the three pairs §18 calls non-interchangeable (`check`/`cloud-check`, `triangle-alert`/
  `circle-alert`) are genuinely different glyphs.
- The mapping lives in one object, `core.ui.PodsiloIcons`, which is what makes §18's "an icon not
  listed here has no call site" enforceable rather than aspirational.

The `assets/icons/` fallback path stays as written, and is now less likely to be needed.
