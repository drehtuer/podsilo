# Podsilo — logo and its integration

Companion to `docs/UI.md`. §18 there governs **icons** (Lucide, functional glyphs). This document
governs the **brand mark**, which is not an icon: it is never used to mean an action, never appears in
a row, and has exactly the placements listed in §4 below.

---

## 1. The mark

Red bars falling into an open black vessel. It reads as a signal being caught and stored — the app's
whole job in one figure.

**Settled 2026-08-08: the mark is the _silo_ build** — a tall, silo-proportioned vessel with two bars
still falling and one band already stored. It was chosen over the *catch* build (a wide tray with
three falling bars, emphasising the catching) because the name pays off in the figure: a silo is
where things are kept, which is the half of the app the user actually lives with.

The catch set has been deleted and the `-silo` suffix dropped from every filename, per the rule this
section used to state — no build should ever have to choose at a call site.

### Construction

A 100-unit square, 10-unit stroke, no curve and no radius anywhere. Everything sits on the same grid
as the Modernist rules used throughout the UI, so a 2 dp app-bar rule and the mark's stroke are
optically the same weight. Colours are the two brand constants only:

- Bars — accent `#ec3013`
- Vessel — ink `#201e1d`
- On the accent field or on ink, the whole mark is `#ffffff`

Never a third colour, never a gradient, never a tint. The mark is either two-colour, all-white, or
all-`currentColor` (mono).

### Clearance and minimum size

- **Clearance** — one grid unit (10 % of the mark's height) clear on all four sides. Nothing crosses
  it, including the app bar's own rule.
- **Minimum size** — mark 16 dp; horizontal lockup 96 dp wide; stacked lockup 64 dp wide. Below 16 dp
  the three bars stop separating; use nothing rather than a smaller mark.

---

## 2. Files

All under `assets/logos/`. **These are source material, not shipped assets** — Android renders no SVG
at runtime (`docs/UI_interface.md` §17), so each one that the app actually uses has a `VectorDrawable`
counterpart listed in §6.

| File | Use |
|---|---|
| `podsilo-mark.svg` | two-colour mark on a light ground |
| `podsilo-mark-inverse.svg` | all-white, for the accent field and dark surfaces |
| `podsilo-mark-mono.svg` | `currentColor` — takes the theme's `onSurface`; the themed-icon source |
| `podsilo-notification.svg` | the mono mark at 18 units inside a 24-unit canvas — the notification small icon (§3) |
| `podsilo-icon.svg` | white mark on the accent field, 144 pt, square |
| `podsilo-lockup-horizontal.svg` | mark + wordmark, one line — **reference only**, see §6 |
| `podsilo-lockup-stacked.svg` | mark over wordmark, both flush left — **reference only**, see §6 |
| `ic_launcher_foreground.xml` | Android adaptive-icon foreground layer, 108 dp, mark inside the 66 dp safe zone |

**Wordmark** — Archivo 700, all lowercase, letter-spacing −0.04 em, always flush left, never centred
and never title-cased. The lockup SVGs carry live `<text>`; convert to outlines before shipping any
asset that leaves the app (store listing, README, press), so the file does not depend on Archivo being
installed.

**Inside the app the wordmark is set as type, not imported as art** — §4.1 already required this of
the app bar, and §6 extends it to both lockups for a reason that turned out to be structural rather
than stylistic: a `VectorDrawable` cannot hold text at all, so the two lockup SVGs could only ship as
drawables if their `<text>` were outlined first, and Archivo is not in this repo. Setting the wordmark
as type also makes it scale with the user's font setting and follow the theme's `onSurface`, neither
of which baked-in art does. **Consequence, stated plainly: in-app the wordmark is the platform font,
not Archivo.** The SVGs remain the reference for everything that leaves the app.

---

## 3. Android launcher and system surfaces

| Surface | Asset | Status |
|---|---|---|
| Adaptive icon, foreground | `app/src/main/res/drawable/ic_launcher_foreground.xml` | **shipped.** Already scaled to the 66 dp safe zone; do not re-scale |
| Adaptive icon, background | `@color/ic_launcher_background` = `#EC3013` | **shipped.** A colour resource, not a drawable — no texture, no rule, no bleed |
| Themed icon (Android 13+) | `android:monochrome` in `mipmap-anydpi-v26/ic_launcher.xml`, pointing at the same foreground | **shipped.** The system tints it, so the mark must read as a single-colour silhouette — the bars and the vessel both become the tint colour and stay legible by their gaps alone |
| Notification small icon | `core/download/src/main/res/drawable/ic_podsilo_notification.xml` | **shipped.** Android renders it as an alpha mask; the mark is held at 18 of 24 units, or the system's own padding clips the top bar |

**There is no splash screen, by decision (2026-08-08).** This section originally specified one — the
mono mark on `#ec3013`, via `core-splashscreen`. The author declined it: the app opens to S1 well
inside the splash's own minimum, so it would have been a delay dressed as a brand moment, and it cost
a dependency to add. Do not reintroduce it as a "polish" item.

The notification small icon is the one place the mark carries a functional load. That is acceptable
because Android gives no alternative — every notification the download service posts (UI.md §12.9) is
stamped with it.

---

## 4. Where the logo appears inside the app

Four places. **That is the complete list.** A logo repeated on every screen stops being a brand and
starts being noise; the screens are already carrying a lot of state.

### 4.1 S1 app bar — mark + wordmark

S1 is the launcher screen and the only screen whose app-bar title is the product name (UI.md §4). Put
the **24 dp mark** as the leading element and keep `Podsilo` as live type beside it, at the app bar's
own title style. Do not import the horizontal lockup as an image here: the title has to scale with the
user's font setting, and an SVG will not.

> **Consequence for UI.md §17 — done.** That section recorded one intentional asymmetry: S2–S8 inset
> their leading icon at 14 dp so its optical edge lands on the 16 dp grid, while S1 inset at 16 dp
> because its leading element was the title itself. The leading mark removes that exception — S1 now
> insets at 14 dp like every other screen, and §17 has been amended to say so.

Gap between mark and wordmark: 8 dp.

**Correction (2026-08-08): the selection-mode rule this paragraph carried does not apply.** It said
that when the app bar becomes `n selected` the mark is dropped along with the title, because a count
is not a brand moment. That reasoning is sound and there is nowhere to apply it: selection mode is an
**S2** affordance (UI.md §5), and S2 never carries the mark in the first place (§5 below). S1 has
filter chips and no selection mode. Kept as a rule to apply *if* selection mode ever reaches S1 —
not as a description of anything that exists.

### 4.2 S1 not-configured empty state — stacked lockup

The one large, unhurried appearance. Before any feed exists, S1's empty state led with a `server`
glyph; it now leads with the **stacked lockup**, above the existing explanatory copy and the *Connect
Nextcloud* action. This is the user's first screen and the only moment in the app with room to
introduce itself. `server` has been struck from UI.md §18 — that was its only call site.

Sized by its **mark at 56 dp** rather than by a total width. This section originally said "96 dp
wide", which a composed lockup cannot honour directly: the wordmark is live type, so the total width
depends on the font and the user's font scale. Fixing the mark and deriving the type from it (§6)
keeps the proportion at any scale, and 56 dp clears the 96 dp intent comfortably.

Once even one feed is subscribed the state never returns, so this costs nothing in the steady state.
The other empty states (filter-empty, `inbox`) keep their glyphs — they are momentary and local, not
introductions.

### 4.3 S4 → About — horizontal lockup

The ABOUT group leads with the **horizontal lockup**, above the version string and the licence
notices (including Lucide's ISC). Flush left, on the surface ground, no card and no frame.

Sized by its **mark at 36 dp**, for the same reason as §4.2 — the "120 dp wide" this originally
specified is not a dimension a live-type lockup has.

### 4.4 Store listing and README — outside the app

Icon asset for the listing; horizontal lockup, outlined, for the README and any press use. The 1024 pt
store icon is `podsilo-icon-{v}.svg` re-exported at size — the geometry does not change with scale.

---

## 5. What the logo never does

- Never in the app bar of S2–S8. Those bars carry a back arrow and a context title; a mark there
  competes with the one thing the user is looking for.
- Never as an episode-row or feed-row element, and never as the artwork placeholder — a feed with no
  image gets its **monogram tile** (UI.md §18), not the brand mark. Repeating the logo down a list
  makes every podcast look like it is ours.
- Never as an affordance. It is not tappable, and it never means *home*, *refresh* or *sync*.
- Never recoloured, tinted, outlined, rounded, given a shadow, set on a photograph, or placed on any
  ground other than the light surface, ink, or the accent field.
- Never in a container with a corner radius. `--radius-md` is 0 across the system and the mark's own
  geometry assumes it.
- Never stretched — always uniform scale, always the exported viewBox.

---

## 6. Compose integration

The mark ships as `VectorDrawable`s and reaches the screen through **`core/ui/.../PodsiloLogo.kt`** —
`PodsiloMark` and `PodsiloLockup`. It does not belong in the Lucide allow-list (UI.md §18); that table
is an allow-list of *functional* glyphs, and adding a brand asset to it invites call sites to use the
logo as an icon. `PodsiloLogo.kt` deliberately sits beside `PodsiloIcons` rather than inside it.

```
core/ui/src/main/res/drawable/
  ic_podsilo_mark.xml            // two-colour, light grounds
  ic_podsilo_mark_inverse.xml    // all-white, ink and accent grounds
  ic_podsilo_mark_mono.xml       // tintable silhouette
core/download/src/main/res/drawable/
  ic_podsilo_notification.xml    // 18-in-24 canvas, alpha-masked by the system
app/src/main/res/
  drawable/ic_launcher_foreground.xml
  values/ic_launcher_background.xml   // #EC3013
```

**Two drawables, chosen by the ground rather than by the system.** The two-colour mark's vessel is ink
`#201E1D`, invisible against the dark scheme's `#14110F` surface, and §1 says the whole mark is white
on ink. `PodsiloMark` therefore picks the inverse build from the *theme's* surface luminance — not
from a `drawable-night` qualifier, because the theme is a user preference in DataStore (UI.md §12.7)
and can disagree with the device's night mode; a qualifier would then paint a white mark onto a light
surface.

**No lockup drawables.** The two lockups are composed — mark drawable plus live type — for the reasons
in §2. `ic_podsilo_lockup.xml` and `ic_podsilo_lockup_stacked.xml` were specified here and are not
buildable as written.

**Content description: `null` at every placement**, including the empty-state lockup. This section
previously asked for `"Podsilo"` there because it expected a text-free image; a lockup built from live
type is not text-free, and the wordmark is the announcement. A description on top of it produces
exactly the doubled reading the rule was written to avoid.

Neither mark drawable is tinted at the call site. If a surface needs a single-colour mark, use the
mono drawable and tint that — tinting the two-colour one flattens the bars into the vessel and
destroys the figure.

---

## 7. Open

Closed on 2026-08-08: the build is **silo** and the filenames are collapsed (§1); UI.md §17's S1
exception is struck; UI.md §18 carries the pointer here and no longer lists `server`, whose only call
site was the empty state §4.2 now owns.

Also closed: the **splash screen** is declined, not deferred (§3).

**Closed on 2026-08-09 — the mark has now been seen on a device** (Pixel 10a, Android 17). Both
questions this section carried as answerable-only-by-eye are answered, and both are now regression
tests rather than a memory of having looked:

- **Does the 24 dp mark read?** Yes. `MarkLegibilityConformanceTest` (`:core:ui`, instrumented)
  rasterises each build on the device's real canvas and counts opaque/transparent alternations down
  the mark's centre line — separation *is* the figure, so a mark whose bars had fused would collapse
  that count. It holds at 24 dp and at §1's 16 dp floor, and the inverse and mono builds are the same
  figure as the two-colour one rather than three drawings that drifted.
- **Does the notification silhouette survive the alpha mask?** Yes.
  `NotificationIconConformanceTest` (`:core:download`, instrumented) reduces the icon to what the
  system keeps — alpha only — and asserts both that the figure still alternates and that the outer
  margin is empty, so the padding §3 demands is checked rather than trusted.
- **The luminance switch was verified live, in the case a resource qualifier gets wrong** (§6): with
  the phone in dark mode and the app's own theme set to Light, S1 and S4 correctly showed the
  *two-colour* mark. A `drawable-night` qualifier would have served the white one onto a light
  surface there.

Still open:

- **Outline the wordmark** in the lockup SVGs before any use outside the app (§2). Nothing in-app
  depends on it, since in-app lockups are composed from type.
- **The notification has not been seen in a real shade** — only its icon, reduced to alpha exactly as
  the system reduces it. Posting one needs a real download, which needs Nextcloud and a granted
  folder; the icon is the part that could have been wrong.
