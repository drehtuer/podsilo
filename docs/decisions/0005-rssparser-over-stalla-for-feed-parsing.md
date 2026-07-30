# 0005 — `com.prof18.rssparser` over Stalla for feed parsing

## Status

Accepted.

## Context

CLAUDE.md §3's dependency table names `dev.stalla:stalla` as the primary pick for podcast feed
parsing ("podcast-namespace aware"), with `com.prof18.rssparser:rssparser` to "evaluate... as
fallback." Before adding either as a pinned dependency, checked both against CLAUDE.md §3's actual
bar: "prefer the boring, widely-used option," implicitly meaning *maintained*, not merely
historically well-regarded.

## What was found

- **Stalla** (`dev.stalla:stalla`): latest Maven Central release `1.1.0`, published **2021-05-28**
  — no release in over five years. The GitHub repo (`mpgirro/stalla`) isn't formally archived and
  has occasional post-release commits (last push 2024-05-07), but nothing has been cut as a new
  version since 2021, and it carries 39 open issues. Licence BSD-3-Clause. Pure Kotlin/JVM, no
  Android dependency.
- **rssparser** (`com.prof18.rssparser:rssparser`): latest version `6.1.8`, published **the same
  day this decision was made** (2026-07-30), per Maven Central's repository metadata directly
  (`repo1.maven.org/.../maven-metadata.xml`), not just a search-index snapshot. Licence
  Apache-2.0. Full support for RSS 2.0 enclosures, GUIDs, the iTunes podcast namespace (`duration`,
  `image`, `author`, `categories`, `episode`/`season`, etc.), Atom, and CDATA-wrapped
  `content:encoded` — functionally equivalent coverage to what Stalla would have offered.

## Decision

Use `com.prof18.rssparser:rssparser:6.1.8`. Put to the author as a question rather than silently
substituted, since this reverses CLAUDE.md's stated primary-vs-fallback order — the author accepted
the recommendation.

## A real implementation consequence: Robolectric is required for `:core:feed`'s local unit tests

rssparser is a genuine Kotlin Multiplatform library with separate `android` and `jvm` compilation
targets. `:core:feed` is a `com.android.library` module (per `docs/architecture.md` §2's module
table — it will eventually need Android APIs for the HTTP-fetch layer), so Gradle resolves the
**android** target of this dependency, not the `jvm` one.

The android target's `AndroidXmlParser` (source: `androidMain/internal/AndroidXmlParser.kt` in the
library) parses via `org.xmlpull.v1.XmlPullParserFactory.newInstance()` — the generic xmlpull.org
factory-lookup mechanism, not `android.util.Xml`. On a plain JVM local unit test (no Robolectric),
no `XmlPullParserFactory` implementation is registered and this throws. Confirmed empirically: the
library's own test suite has a dedicated `androidHostTest` source set (its local-JVM-test source
set for the android target) whose `build.gradle.kts` dependencies include
`org.robolectric:robolectric` specifically to make this factory lookup succeed.

**Consequence for `TODO.md`'s Tier 2 bucketing:** `TODO.md` describes Tier 2 as "no network/Android"
and doesn't mention Robolectric. This is a simplification specific to that document, not a
CLAUDE.md rule — CLAUDE.md §4 explicitly lists Robolectric as a normal Tier 1 (JVM, no emulator)
tool for "Android-framework bits." Using it here for `:core:feed`'s parsing tests is squarely within
that definition: still headless, still no emulator, still `./gradlew test`. `TODO.md` is updated to
note this rather than silently drop the "no Android" framing.

## Consequences

- `:core:feed`'s `build.gradle.kts` needs `testImplementation(libs.robolectric)` and its parsing
  tests need `@RunWith(RobolectricTestRunner::class)`.
- Encoding detection (declared XML `encoding="..."` vs. actual bytes) is `:core:feed`'s own
  responsibility, not rssparser's — its `parse(rawRssFeed: String)` entry point takes an
  already-decoded `String`, so byte→String decoding using the declared encoding happens in
  `:core:feed` before calling it (see `FeedXmlDecoding.kt`).
