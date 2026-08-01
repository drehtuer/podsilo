# UI_interface.md — the contract between the Compose UI and the app logic

Companion to [`docs/UI.md`](UI.md) (what the screens are) and
[`docs/architecture.md`](architecture.md) (what is underneath). This document defines the
**seam**: for every screen, the immutable state the UI renders, the events it emits, and the ports
it reaches through. Nothing here describes rendering; nothing here describes I/O. If a Composable
needs a value that is not in a `UiState` below, or performs an action that is not a `UiEvent`, the
seam is wrong — fix the seam, not the Composable.

Designs are **not in this repository**: `Podsilo Screens.dc.html` (every screen and state, light and
dark) and `Podsilo Prototype.dc.html` (tap-through) live in the design project. The only design
assets that are committed are `assets/icons/` (Lucide SVG source, see §17) and `assets/art/`
(generated placeholder cover art for the mock-ups, never shipped in the app).

Package root for everything below: `net.drehtuer.podsilo`.

---

## 0. Rules the seam enforces

1. **Unidirectional.** DAO `Flow` → repository → ViewModel `StateFlow<UiState>` → Composable.
   Composables emit `UiEvent` upward and never call a repository, `WorkManager`, or an HTTP client.
2. **The ViewModel never enqueues work directly.** All enqueueing goes through `WorkScheduler`
   (`:app`), which already owns it as built in Tier 4b. `WorkManager` is not a ViewModel dependency.
3. **The UI never triggers network.** It writes ledger rows and asks for work; workers do the I/O
   and write back; the UI observes the database (architecture §3).
4. **One state type per screen, always non-null.** No `UiState?`, no `isLoading` plus a nullable
   payload. Loading, empty, error and content are *variants*, not flags — every screen's state is a
   sealed hierarchy or carries an explicit `content:` variant field.
5. **Every list item carries its own affordance set.** The row does not derive "can I download this?"
   from a `when (state)` in the Composable — the ViewModel computes `actions: Set<EpisodeUiAction>`
   so the row, the overflow, the swipe label and the accessibility custom actions all read one
   source (UI.md §12.6).
6. **Presentation strings are resolved in the Composable, not the ViewModel.** State carries typed
   values (`Instant`, `LedgerState`, `ErrorCause`); `stringResource` happens at render. The one
   exception is a server-supplied message (`lastError`), which is passed through verbatim alongside
   a typed cause.
7. **Snapshots for one-shot effects.** Snackbars, navigation and system-picker launches are
   `Channel<UiEffect>`/`Flow<UiEffect>`, never state fields — they must not replay on rotation.

---

## 1. Shared types

```kotlin
// :core:model — additions the UI needs. Everything else already exists.

/**
 * What a row/sheet may currently do. Computed by the ViewModel from the ledger row.
 * NOT `EpisodeAction` — that name is already taken in :core:model by the GPodder wire type
 * (`port.EpisodeAction`, architecture §5). Two different things called EpisodeAction in one
 * module is a compile error at best and a silent mix-up at worst.
 */
enum class EpisodeUiAction { DOWNLOAD, DOWNLOAD_AGAIN, MARK_AS_PLAYED, RETRY, CANCEL, CHOOSE_FOLDER, OPEN_IN_BROWSER, COPY_LINK }

/** One row in S2/S3/S7. Wraps the existing EpisodeListItem with UI-resolved bits. */
data class EpisodeUi(
    val episodeKey: String,
    val feedUrl: String,
    val feedTitle: String,
    val title: String,
    val artworkUrl: String?,          // episode image, else the feed's
    val publishedAt: Instant?,        // null renders as no date part, never a fabricated one
    val duration: Duration?,          // itunes:duration is unreliable — null renders as no part
    val descriptionSnippet: String,   // HTML already stripped, ≤ 2 lines' worth
    val ledgerState: LedgerState?,    // null == "to decide" (there is no NEW state — architecture §9)
    val progress: DownloadProgress?,  // non-null only while this process has seen an update
    val writtenFileName: String?,
    val lastError: FailureUi?,
    val hasEnclosure: Boolean,        // false → dimmed "no audio" row, download disabled
    val actions: Set<EpisodeUiAction>,
)

/** Never reconstructed from a stale ledger row — see §7 "resuming". */
data class DownloadProgress(val bytesDownloaded: Long, val totalBytes: Long?, val percent: Int?)

data class FailureUi(val cause: ErrorCause, val message: String, val attempts: Int, val retryable: Boolean)

enum class ErrorCause { NETWORK, SERVER, AUTH, FEED_PARSE, DISK_FULL, FOLDER_UNAVAILABLE, TAG_WRITE, UNKNOWN }

/** One user-visible condition with three causes (UI.md §12.11). */
sealed interface QueueStatus {
    data object Running : QueueStatus
    data class Paused(val cause: PauseCause, val queuedCount: Int) : QueueStatus
    enum class PauseCause { FOLDER_NOT_CHOSEN, FOLDER_REVOKED, DISK_FULL }
}

sealed interface UiEffect {
    data class Snackbar(val text: SnackbarText) : UiEffect
    data class Navigate(val route: Route) : UiEffect
    data object LaunchFolderPicker : UiEffect          // ACTION_OPEN_DOCUMENT_TREE
    data class OpenUrl(val url: String) : UiEffect     // custom tab: Login Flow v2, "open in browser"
    data class ShareText(val text: String) : UiEffect
}
```

**`Instant` and `Duration` are `java.time`** — free at `minSdk 33` and already this project's time
vocabulary (`:core:naming`, `:core:sync`, `:core:feed`, `:core:download` all use it). **No
`kotlinx-datetime`, no `kotlin.time.Instant`** — either would be a second vocabulary in a codebase
that has one (ADR 0016).

Storage keeps `Long` epoch millis, unchanged. The conversion happens in exactly one place,
`EpochTime` in `:core:model`, whose value is its function names: `ofMillis` for everything, and
`ofServerSeconds` for `SyncState.lastEpisodeActionSyncTs` alone, which is Unix **seconds** verbatim
from the server. A ViewModel projecting `EpisodeListItem` → `EpisodeUi` calls `EpochTime`; nothing
else calls `Instant.ofEpochMilli` directly.

---

## 2. S1 — Podcast list

```kotlin
data class PodcastListUiState(
    val content: Content,
    val filter: PodcastFilter = PodcastFilter.WITH_NEW,
    val isRefreshing: Boolean = false,
    val queueStatus: QueueStatus = QueueStatus.Running,
    val isOffline: Boolean = false,
    val setup: SetupChecklist?,           // null once complete and the grant is intact
    val activityBadge: Boolean,           // any running download, ERROR row, or unsynced row
    val totalUndecided: Int,
) {
    sealed interface Content {
        data object NotConfigured : Content          // no Nextcloud → connect empty state
        data object Loading : Content                // shimmer rows, no spinner overlay
        data object NoSubscriptions : Content        // configured, zero feeds
        data class Feeds(val feeds: List<FeedUi>) : Content
    }
}

data class FeedUi(
    val url: String,
    val title: String?,                   // null → render the URL (architecture §4), never "Unknown"
    val artworkUrl: String?,
    val lastRefreshedAt: Instant?,        // null → "never refreshed"
    val undecidedCount: Int?,             // null → "–": never fetched is not zero (UI.md §12.5)
    val activeDownloads: Int,
    val aggregateProgress: Float?,        // 0f..1f, null → indeterminate ring
)

data class SetupChecklist(
    val nextcloudConnected: Boolean,
    val instanceLabel: String?,
    val folderState: FolderState,         // DownloadFolderAccess.State, used verbatim
    val namingPreview: String,
)

enum class PodcastFilter { WITH_NEW, ALL }

sealed interface PodcastListEvent {
    data class FeedClicked(val feedUrl: String) : PodcastListEvent
    data class FilterChanged(val filter: PodcastFilter) : PodcastListEvent
    data object PullToRefresh : PodcastListEvent
    data object ActivityClicked : PodcastListEvent
    data object SettingsClicked : PodcastListEvent
    data object ConnectNextcloudClicked : PodcastListEvent
    data object ChooseFolderClicked : PodcastListEvent
    data object NamingClicked : PodcastListEvent
    data object PausedBannerActionClicked : PodcastListEvent
}
```

**Ports used:** `FeedRepository.observeAll`, `EpisodeLedgerRepository.observeEpisodes(filter)` for
the counts (the *same* query as S2, so a badge can never disagree with the list it opens),
`SettingsRepository`, `DownloadFolderAccess`, `WorkScheduler` (refresh + sync), `ConnectivityMonitor`
(§8).

**Ordering is frozen.** The ViewModel sorts once per explicit refresh and on cold start, then holds
the key order in a `List<String>` and re-projects updated `FeedUi` values into it. Rows update in
place; they never move under the user's finger (UI.md §4). Recomputing the sort inside the `Flow`
combine is the bug this rule exists to prevent.

---

## 3. S2 — Episode list

```kotlin
data class EpisodeListUiState(
    val feedUrl: String,
    val feedTitle: String,                 // falls back to the URL
    val filter: EpisodeFilter = EpisodeFilter.TO_DECIDE,
    val content: Content,
    val sections: List<MonthSection>,      // sticky headers; null pubDate lands in the trailing "Date unknown"
    val selection: Selection?,             // non-null == selection mode
    val isRefreshing: Boolean = false,
    val queueStatus: QueueStatus = QueueStatus.Running,
    val feedError: FailureUi?,             // inline banner; episodes stay listed
    val swipeMapping: SwipeMapping,        // background label/icon are rendered FROM this
    val downloadAllCount: Int,             // overflow item reads "Download all (n)"
) {
    sealed interface Content {
        data object Loading : Content
        data class Empty(val filter: EpisodeFilter) : Content
        data class Episodes(val items: List<EpisodeUi>) : Content
    }
}

data class MonthSection(val label: YearMonth?, val firstIndex: Int, val count: Int)
data class Selection(val keys: Set<String>, val allInFilter: Int)
data class SwipeMapping(val right: SwipeAction, val left: SwipeAction)
enum class SwipeAction { DOWNLOAD, MARK_AS_PLAYED, NONE }
enum class EpisodeFilter { TO_DECIDE, DOWNLOADED, PLAYED_OR_HANDLED, ALL }

sealed interface EpisodeListEvent {
    data class RowClicked(val episodeKey: String) : EpisodeListEvent          // opens S3, never triages
    data class Triage(val episodeKey: String, val action: EpisodeUiAction) : EpisodeListEvent
    data class SwipeCommitted(val episodeKey: String, val direction: SwipeDirection) : EpisodeListEvent
    data class FilterChanged(val filter: EpisodeFilter) : EpisodeListEvent
    data class SelectionToggled(val episodeKey: String) : EpisodeListEvent
    data object SelectionStarted : EpisodeListEvent
    data object SelectionCleared : EpisodeListEvent
    data object SelectAllInFilter : EpisodeListEvent
    data class BulkConfirmed(val action: EpisodeUiAction, val keys: Set<String>) : EpisodeListEvent
    data object DownloadAllRequested : EpisodeListEvent                        // opens the confirm dialog
    data class DownloadAllConfirmed(val keys: List<String>) : EpisodeListEvent
    data object PullToRefresh : EpisodeListEvent
    data object RetryFeedClicked : EpisodeListEvent
}
```

**Confirmation is a UI-owned dialog, not a state field of the list.** `DownloadAllRequested` makes
the ViewModel produce a `BulkPreview` (below) which the dialog renders; nothing is written until
`DownloadAllConfirmed`.

```kotlin
data class BulkPreview(
    val count: Int,
    val perFeed: List<Pair<String, Int>>,   // S4's mark-as-played preview reuses this exact type
    val estimatedBytes: Long?,              // null when any duration is unknown → no size shown
    val freeBytes: Long?,                   // read ONCE when the dialog opens
    val exceedsFreeSpace: Boolean,          // warning line only; never disables the action
)
```

**Ports used:** `EpisodeLedgerRepository.observeEpisodes(filter)` (one SQL join — the filter is
resolved in the DAO, not in Kotlin), `EpisodeLedgerRepository.upsert`, `FeedRepository.get`,
`SettingsRepository` (swipe mapping), `WorkScheduler`, `DownloadTarget.existingNames` **only** via
the download path, never from the ViewModel.

---

## 4. S3 — Episode detail sheet

```kotlin
data class EpisodeDetailUiState(
    val episode: EpisodeUi,
    val descriptionHtml: String,      // RAW, straight from Episode.description
    val deliveredTo: String?,         // "SD card / Podcasts" — folder label, only when DOWNLOADED
    val episodePageUrl: String?,      // RSS <link> of the item; null → the row is not rendered
)

sealed interface EpisodeDetailEvent {
    data class Triage(val action: EpisodeUiAction) : EpisodeDetailEvent
    data object Dismissed : EpisodeDetailEvent
    data object ErrorDetailsClicked : EpisodeDetailEvent   // → S8
    data class LinkClicked(val url: String) : EpisodeDetailEvent
    data object OpenInBrowserClicked : EpisodeDetailEvent
}
```

**Opening in the browser is an effect, not navigation.** `OpenInBrowserClicked` emits
`UiEffect.OpenUrl(episodePageUrl)`, which the host Composable hands to a Custom Tab (falling back to
`Intent.ACTION_VIEW`). The sheet stays open behind it — leaving to read show notes is not a triage
decision, and coming back must not cost the user their place. The row renders only when the feed
supplied an item `<link>`; it is never synthesised from the enclosure URL, which points at an audio
file rather than a page. Needs one field on the domain type:

```kotlin
// :core:model — Episode
val link: String?,   // RSS <item><link> / Atom <link rel="alternate">, mapped in :core:feed
```
See §8.

**Sanitisation happens at render, never at write** (architecture §4). The sanitiser is a UI-layer
function, not a repository concern:

```kotlin
// :feature:episodes
fun sanitizeEpisodeHtml(raw: String): AnnotatedString
```
Allowed: paragraphs, line breaks, bold/italic, lists, links. Stripped: `<script>`, `<style>`,
`<iframe>`, remote images, tracking pixels. It is a pure function and is table-tested; feeding it a
malicious feed is a unit test, not a manual check.

---

## 5. S4 / S5 / S6 — Settings, connection, naming

```kotlin
data class SettingsUiState(
    val nextcloud: NextcloudUi,
    val downloadFolder: FolderUi,
    val namingSummary: String,
    val allowMobileData: Boolean,
    val swipeMapping: SwipeMapping,
    val markOldOlderThan: OlderThan,        // OFF | M1 | M3 | M6 | Y1
    val theme: ThemePreference,             // LIGHT | DARK | SYSTEM
    val errorLogCount: Int,
    val version: String,
    val pendingBulk: BulkPreview?,          // non-null while the preview dialog is up
)

data class NextcloudUi(val instanceUrl: String?, val loginName: String?, val connectedAt: Instant?, val lastSyncAt: Instant?, val outboxDepth: Int)
data class FolderUi(val label: String?, val state: FolderState)   // DownloadFolderAccess.State

sealed interface SettingsEvent {
    data object ConnectClicked : SettingsEvent
    data object DisconnectClicked : SettingsEvent
    data object ChooseFolderClicked : SettingsEvent
    data object NamingClicked : SettingsEvent
    data object LastSyncClicked : SettingsEvent
    data object ErrorLogClicked : SettingsEvent
    data class MobileDataChanged(val allowed: Boolean) : SettingsEvent
    data class SwipeChanged(val direction: SwipeDirection, val action: SwipeAction) : SettingsEvent
    data class OlderThanChanged(val value: OlderThan) : SettingsEvent
    data class BulkPreviewRequested(val scope: BulkScope) : SettingsEvent   // OLDER_THAN | ALL
    data object BulkConfirmed : SettingsEvent
    data object BulkCancelled : SettingsEvent
    data class ThemeChanged(val theme: ThemePreference) : SettingsEvent
}
```

The two swipe dropdowns **cannot hold the same action**: `SwipeChanged` swaps them in the ViewModel
rather than rejecting the input, so the pair is always valid and the swipe background can be
rendered from state with no defensive branch.

### S5 — Nextcloud connection dialog

```kotlin
data class ConnectUiState(
    val host: String,
    val phase: Phase,
    val inlineError: ConnectError?,
    val isChangingExisting: Boolean,
) {
    sealed interface Phase {
        data object Editing : Phase
        data object RequestingFlow : Phase
        data object AwaitingAuthorization : Phase   // field read-only, Cancel aborts the poll
        data object VerifyingGpodderSync : Phase    // the authenticated GET /subscriptions
    }
}

enum class ConnectError { UNREACHABLE, TLS, NOT_NEXTCLOUD, NO_GPODDERSYNC, UNAUTHORIZED, ABANDONED }

sealed interface ConnectEvent {
    data class HostChanged(val value: String) : ConnectEvent   // a typed scheme is stripped, not rejected
    data object Submit : ConnectEvent
    data object Cancel : ConnectEvent
}
```

**Success is claimed only after `VerifyingGpodderSync` returns 200.** On failure the app password is
discarded, never stored. The dialog is not dismissable by tapping outside while a request is in
flight.

Uses `NextcloudLoginFlowClient` (§8), implemented in `:core:gpodder`.

### S6 — Naming template editor

```kotlin
data class NamingUiState(
    val folderTemplate: String,
    val fileTemplate: String,
    val validation: Validation,
    val previews: List<NamingPreview>,     // real recent episode + synthetic worst cases
    val placeholders: List<String>,
) {
    sealed interface Validation {
        data object Valid : Validation
        data class Invalid(val field: Field, val reason: String) : Validation   // cannot be applied
    }
}

data class NamingPreview(val label: PreviewCase, val resolved: String)
enum class PreviewCase { RECENT_EPISODE, MISSING_DATE, OVERLONG_TITLE, ILLEGAL_CHARACTERS }
```

Previews call the already-tested `NamingTemplateEngine.resolve()` — the editor contains **zero**
sanitisation, truncation or date logic of its own (architecture §11). The `MISSING_DATE` preview is
expected to render `00000000` (ADR 0004); if it ever renders an empty segment, the engine regressed,
not the UI.

---

## 6. S7 — Activity

```kotlin
data class ActivityUiState(
    val queueStatus: QueueStatus,
    val sync: SyncUi,
    val downloading: List<EpisodeUi>,
    val queued: List<QueuedUi>,
    val failed: List<EpisodeUi>,
    val recent: List<DeliveredUi>,          // last ~20, filenames only
)

data class SyncUi(val lastSyncAt: Instant?, val outboxDepth: Int, val canSyncNow: Boolean, val blockedReason: BlockedReason?)
data class QueuedUi(val episode: EpisodeUi, val reason: WaitReason)  // WIFI, NETWORK, FOLDER, RESUMING
data class DeliveredUi(val fileName: String, val folderLabel: String, val episodeKey: String)

sealed interface ActivityEvent {
    data object SyncNowClicked : ActivityEvent
    data class CancelClicked(val episodeKey: String) : ActivityEvent
    data class RetryClicked(val episodeKey: String) : ActivityEvent
    data class MarkAsPlayedClicked(val episodeKey: String) : ActivityEvent
    data class DetailsClicked(val episodeKey: String) : ActivityEvent
    data class RowClicked(val episodeKey: String) : ActivityEvent     // jumps to S2
    data object PausedBannerActionClicked : ActivityEvent
    data object ErrorLogClicked : ActivityEvent
}
```

`recent` exists to answer "did it actually land?" and nothing else. There is **no** delete, no
open-file, and no existence check — Podsilo is not a file manager (README).

A `FOLDER_UNAVAILABLE` failure carries `retryable = false`, so its row renders **Choose folder** and
not **Retry** (UI.md §12.11, ADR 0011).

---

### 6b. S8 — Error log

```kotlin
data class ErrorLogUiState(
    val filter: LogCategory?,          // null == All
    val entries: List<LogEntry>,       // already collapsed by the DAO, newest first
    val expanded: Set<Long>,           // ids whose technical-detail section is open
    val canClear: Boolean,             // false when empty — Clear/Copy/Share go disabled, not hidden
    val pendingClear: Boolean,         // the confirmation dialog is up
)

sealed interface ErrorLogEvent {
    data class FilterChanged(val category: LogCategory?) : ErrorLogEvent
    data class DetailToggled(val id: Long) : ErrorLogEvent
    data class EntryClicked(val id: Long) : ErrorLogEvent    // jumps to the episode in S2, when it names one
    data object CopyAllClicked : ErrorLogEvent
    data object ShareClicked : ErrorLogEvent
    data object ClearRequested : ErrorLogEvent               // opens the confirmation
    data object ClearConfirmed : ErrorLogEvent
    data object ClearCancelled : ErrorLogEvent
}
```

**Clearing always confirms** (`UI.md` §11) — the dialog names the count and says the log is
device-local, because there is no copy anywhere else and clearing is not undoable. It clears the
whole ring buffer, **not** the current filter: a filtered view that cleared only the visible
category would leave a count the user cannot account for. Recording resumes immediately; clearing is
`LogRepository.clear()` and touches nothing else — no ledger row, no worker, no sync state. Clear,
Copy all and Share are disabled (not hidden) when the log is empty, so the affordance stays where
the user learned it.

---

## 7. Progress, and the rule about stale percentages

`DownloadProgress` is assembled from `WorkManager`'s `WorkInfo.progress` **for this process only**,
merged with the ledger state:

| Ledger state | Live progress seen this process | What the UI shows |
|---|---|---|
| `DOWNLOADING` | yes | determinate bar, `%` and `MB / MB` |
| `DOWNLOADING` | no, work is live | **indeterminate**, word *resuming* |
| `DOWNLOADING` | no, no live work for the key | *queued*, and the ViewModel re-enqueues on first observation |
| `QUEUED` | — | indeterminate + the wait reason |

**A percentage is only ever drawn from an update received in this process.** Persisting a percentage
to render after process death is the specific bug this table forbids.

Updates are throttled to **1 Hz** in the ViewModel — the same throttle `DownloadNotifications`
already uses, so the notification, the row, S1's aggregate ring and S7 can never disagree. The bar
animates between updates rather than stepping.

---

## 8. What the screens bind to — all of it built

This was a gap list of ten items the UI needed and the repository did not have. **Every one of them
now exists** (2026-08-01), so it is kept as a short index of what each turned into, and of the three
places the built thing differs from the sketch.

| # | Needed for | Built as |
|---|---|---|
| 8.1 | S8's entire backing store | `LogRepository` + `error_log` (schema v2). Collapse-on-identity and eviction are **DAO queries**, not UI logic and not an app-start sweep |
| 8.2 | *Download again* | `KEY_USER_REQUESTED` + the pre-flight duplicate guard — `docs/decisions/0012` |
| 8.3 | `UI.md` §12.10's offline rules | `ConnectivityMonitor` / `AndroidConnectivityMonitor` |
| 8.4 | S5 | `NextcloudLoginFlowClient` / `RetrofitNextcloudLoginFlowClient` |
| 8.5 | the bulk-download warning line | `DownloadTarget.freeBytes()` |
| 8.6 | S2 selection mode, S4's mark-as-played | `upsertAll`, `previewUndecided`, `undecided` |
| 8.7 | S2's pull-to-refresh | `FeedRefreshWorker.KEY_FEED_URL` — the same worker, not a second one |
| 8.8 | *Open in browser* | `Episode.link`, mapped in `:core:feed`, stored in schema v2 |
| 8.9 | S4's four persisted controls | `SettingsRepository.observeTheme`/`SwipeMapping`/`AllowMobileData`/`MarkOldOlderThan` |
| 8.10 | artwork and icons | Coil and `icons-lucide-android`, pinned — `docs/decisions/0015` |

**Three things came out differently from the sketch, and the built shape is the contract:**

- `previewUndecided` returns `List<FeedUndecidedCount>`, not `List<Pair<String, Int>>` —
  `first`/`second` says nothing about which is the feed and which is the count.
- `BulkScope` is a **data class**, not an enum: `OLDER_THAN` has to carry its cutoff, and both scopes
  need the optional per-feed narrowing *Download all* uses. Both select only episodes with **no
  ledger row**, so a bulk action can never re-touch a decided episode; with a cutoff, episodes with
  an unknown `pubDate` are excluded, because a missing date is not evidence of being old and
  sweeping one up would emit a `PLAY` the user never agreed to.
- `Instant` is **`java.time`**, and nothing was added to get it — see §1 and `docs/decisions/0016`.

**Still missing, and it is not a port:** error-log *write points*. `FeedRefresher` records feed
failures; `SyncOrchestrator`/`SyncWorker`, `EpisodeDownloader`/`DownloadWorker` and the S5 auth flow
do not record anything yet, so S8 will render an honest but very quiet log until they do. The test
that no entry ever contains the app password, the Basic-auth header, or a URL with credentials is
also still unwritten.

## 9. Navigation

Single activity, one `NavHost`, `S1` the start destination and the only screen at the bottom of the
backstack.

```kotlin
sealed interface Route {
    data object Podcasts : Route                        // S1
    data class Episodes(val feedUrl: String) : Route    // S2
    data class EpisodeDetail(val episodeKey: String) : Route  // S3, bottom sheet destination
    data object Settings : Route                        // S4
    data object Connect : Route                         // S5, dialog destination
    data object Naming : Route                          // S6
    data object Activity : Route                        // S7
    data object ErrorLog : Route                        // S8
}
```

Back from S2 returns to S1 with its scroll position **and** filter intact — the per-feed filter is
held in the ViewModel's `SavedStateHandle`, session-scoped, resetting to `TO_DECIDE` on cold start.
S5 cannot be dismissed by tapping outside while an auth request is in flight.

`:feature:episodes`'s TODO scope names only the episode list. S1, S7 and S8 need a home: either the
module list gains them, or S7/S8 land in `:app`. Recommended: S1 and S2 in `:feature:episodes`
(they share the ledger query and the `EpisodeUi` projection), S7 and S8 in `:app` (they are
cross-cutting: workers, sync and the log, none of which are episode-list concerns).

---

## 10. Theming


```kotlin
@Composable fun PodsiloTheme(preference: ThemePreference, content: @Composable () -> Unit)
```

One seed colour, two generated Material 3 schemes, **dynamic colour off** so the app looks the same
on every device and both schemes can actually be verified. Applied at the root; changing the
preference recomposes and does **not** recreate the activity.

The visual system these screens are drawn in maps onto M3 as: `primary` = the accent (`#ec3013`
light, one ramp step up on the dark ground), `surface`/`background` = the light ground, zero corner
radius on every shape token, `Archivo` for both the display and body type roles, and 2 dp dividers
where M3 would default to 1 dp. Status is always carried by **text** as well as colour, greyed-out
rows use `onSurfaceVariant` rather than an opacity that drops the title below 4.5:1, and swipe
backgrounds stay ≥ 3:1 and distinguishable in dark mode (darkening them is not sufficient).

---

## 11. Motion — the Compose mapping

The durations, easings and the three rules behind them are in **`UI.md` §16**, which is canonical;
this table is only which API carries each one, so a reader implementing a screen does not have to
guess.

| Transition | Carried by |
|---|---|
| S1 → S2, forward and back | `NavHost`'s `enterTransition`/`exitTransition` and `popEnterTransition`/`popExitTransition` — separate values, since back is faster |
| S3 sheet | `ModalBottomSheet`; its default drag-following dismiss is what §16 asks for, so do not replace it with an animated visibility |
| Triage commit | `Modifier.animateItem` for the collapse, `AnimatedContent` for the treatment crossfade, and a plain `delay` for the 400 ms hold — a delay, not an animation, so *Remove animations* cannot strip it |
| Swipe | `SwipeToDismissBox`, with the background composed behind rather than faded in |
| Progress | `animateFloatAsState` with a 1 000 ms `tween(easing = LinearEasing)`; `LinearProgressIndicator` |
| Banners | `AnimatedVisibility(expandVertically/shrinkVertically)` |
| Dialogs | `AlertDialog` / `BasicAlertDialog` |
| Snackbar | `SnackbarHost`; `SnackbarDuration.Short` — no action label, ever |
| Chips, segments, theme | no animation API at all; these change state and repaint |

The one thing to get right that no API gives you: the triage hold must survive the reduced-motion
setting, so read it (`AccessibilityManager`/`Settings.Global.ANIMATOR_DURATION_SCALE`) to disable the
*other* transitions and leave the delay alone — not the reverse.

---

## 12. Consistency invariants

The spacing and sizing contract is in **`UI.md` §17** — one canonical list, including the single
intentional asymmetry (leading-icon screens inset 14 dp, S1 16 dp). Two implementation notes that
belong here rather than there:

- Those values want to be **named constants in one file** per feature module, not literals at ~200 call
  sites. Every one of them was found drifting at least once while the screens were being drawn, and
  that was with 37 static frames to compare — it will drift faster in code.
- The ≥ 48 dp floor is a `Modifier.sizeIn(minHeight = 48.dp)` on the control, **not** extra padding
  around the glyph or the label: padding changes the visual, `sizeIn` changes only the target.

---

## 13. Types referenced above, declared

Everything the state classes lean on, so no reader has to infer a shape. These live in
`:core:model` unless marked otherwise.

```kotlin
// All four are built, in :core:model's `port` package beside SettingsRepository, which persists them.
enum class SwipeDirection { LEFT, RIGHT }
enum class ThemePreference { LIGHT, DARK, SYSTEM }

// OlderThan carries its own Period and computes the cutoff: `cutoffMillis(now, zone)`. Calendar
// arithmetic, not `now - 90 days`, so "3 months" means what the label says. Two callers need it (the
// preview dialog and FeedRefresher), which is why it is on the type rather than at a call site.
enum class OlderThan { OFF, MONTH_1, MONTH_3, MONTH_6, YEAR_1 }

// SwipeMapping enforces "the two directions never hold the same action" in `with(direction, action)`,
// which swaps rather than rejects. NONE is exempt — both directions may be disabled.
data class SwipeMapping(val right: SwipeAction, val left: SwipeAction)
enum class LogCategory { SYNC, FEED, DOWNLOAD, STORAGE, AUTH }
enum class WaitReason { WIFI, NETWORK, FOLDER, RESUMING }
enum class BlockedReason { OFFLINE, NOT_CONFIGURED, SYNC_IN_FLIGHT }

/**
 * NOT a new type: this is `DownloadFolderAccess.State`, as built in Tier 4b, reproduced here only
 * so the state classes above read. It lives in `:core:download`; if `:feature:settings` cannot see
 * it from there, promote that one enum to `:core:model` rather than declaring a parallel copy.
 */
enum class FolderState { NOT_CHOSEN, GRANTED, REVOKED }

/**
 * A snackbar's *identity*, not its text — the string is resolved at render (rule §0.6).
 * Sealed rather than an enum because two of them carry a value.
 */
sealed interface SnackbarText {
    data object SyncFailed : SnackbarText
    data object SyncSucceeded : SnackbarText
    data class AlreadyInFolder(val fileName: String) : SnackbarText   // informational, NOT an error (§12.3)
    data class BulkApplied(val count: Int) : SnackbarText
    data object LogCleared : SnackbarText
    data class DownloadFailed(val cause: ErrorCause) : SnackbarText
    data object LinkCopied : SnackbarText
}

/** S6's invalid-template target. */
enum class Field { FOLDER_TEMPLATE, FILE_TEMPLATE }

/** :feature:episodes — month grouping key; kotlinx.datetime has no YearMonth. */
data class YearMonth(val year: Int, val month: Int)

data class NewLogEntry(
    val category: LogCategory,
    val feedUrl: String?,
    val episodeKey: String?,
    val message: String,
    val detail: String?,
)
```

`EpisodeUiAction` (§1) is the full affordance vocabulary; `Route` (§9) is the full destination set.
Neither is extended anywhere else — a new affordance or destination is an edit to those two
declarations, so the compiler finds every `when` that needs updating.

---

## 14. Corner cases

The cases below are where a plausible implementation is wrong. Each is a test, not a note.

### 14.1 State changes under the user

| Case | Required behaviour |
|---|---|
| Remote `PLAY`/`DOWNLOAD` arrives for an episode the user is mid-swipe on | the swipe still commits — the local write wins, and reconciliation is idempotent (architecture §9). Never cancel a gesture because of a background sync. |
| Remote action arrives for a `DOWNLOADING` episode | the row moves to `HANDLED_REMOTELY` and the worker is cancelled; the partial cache file is deleted. The row animates through the normal terminal treatment so the change is visible, never silent. |
| Remote action arrives for an already-`DOWNLOADED` episode | **no-op.** No state change, no animation, no snackbar. This is the "triage durability" property and it must be observable as *nothing happening*. |
| A feed is unsubscribed on the server while S2 for that feed is open | the screen stays up with its episodes (they are still in Room until the next refresh prunes them) and shows a one-line inline notice that the podcast is no longer in Nextcloud. It does **not** pop the backstack — yanking a screen out from under a reader is worse than a stale one. Triage actions stay enabled: the ledger is keyed by episode, not by current subscription (architecture §6). |
| A feed's title arrives from its first successful fetch while S1 is on screen | the row's primary line swaps from URL to title **in place**, without re-sorting (§2's frozen ordering). |
| The download folder grant is revoked while S2 is open | the paused banner appears above the list; `QUEUED` rows stay `QUEUED` and read *paused*; new download requests are still accepted (§12.11). |

### 14.2 Lifecycle and process death

| Case | Required behaviour |
|---|---|
| Process death mid-download | covered by §7 — never a stale percentage; `DOWNLOADING` with no live work re-enqueues on first observation. |
| Process death in selection mode | selection is **dropped**, not restored. A restored set of checkboxes the user cannot remember choosing is a bulk action waiting to be confirmed by accident. |
| Process death with the S5 dialog open mid-poll | the flow is abandoned and the app password discarded. On return the dialog is closed and S4 shows the previous instance (or none). An abandoned flow is written to the log (`AUTH`). |
| Rotation with a dialog or sheet open | preserved, via `SavedStateHandle` — but one-shot `UiEffect`s must not replay (rule §0.7). |
| Rotation into landscape | no state consequence — every screen stays one scrolling column (`UI.md` §19). The four short-window adjustments there are layout-only: no `UiState` field describes orientation, and no ViewModel reads a window size class. If one ever needs to, that is a design change first. |
| Cold start while a sync is already running from a previous process | S1 shows the refresh indicator for the live work; it does not start a second pass. `WorkScheduler` uses unique work, so this is a query, not a guard. |

### 14.3 Data shapes that break naive rendering

| Case | Required behaviour |
|---|---|
| `Episode.description` is null or strips to empty | the snippet line is **omitted**, not rendered as an empty line, and S3 shows a single muted "No description." sentence rather than a blank sheet. |
| `durationSeconds` and `pubDate` both absent | meta line renders neither part and shows nothing — never "unknown", never a fabricated value. The row is still fully triageable. |
| Episode has no enclosure | `hasEnclosure = false` → `actions` contains only `OPEN_IN_BROWSER`; the row is dimmed with a **no audio** badge. Download must be *absent*, not present-and-failing. |
| Two episodes in one feed share a `guid` | the ledger is keyed by `episodeKey`, so they are one row and one decision. The list must not show a duplicate — dedup by key when projecting, and do not assume the DAO did it. |
| A title long enough to overflow at the largest font scale | the title truncates first; the decision affordances never do (§12.12). |
| A feed with 500+ episodes under `All` | paging or a keyed `LazyColumn` with stable `episodeKey`s — the sticky headers and the fast-scroll thumb both depend on stable keys, and `animateItem` misbehaves without them. |
| `writtenFileName` present but the file is gone | the row still reads `DOWNLOADED`. Podsilo does not check, track, or care whether the file still exists — the only permitted existence check is the pre-flight duplicate guard on an explicit re-download (`docs/decisions/0012`). |

### 14.4 Disconnect

`SettingsEvent.DisconnectClicked` opens a confirmation that states plainly that **the ledger is
kept** — so nothing is re-downloaded after reconnecting — and that only the credentials are cleared.
Same type as the other previews:

```kotlin
data class DisconnectConfirm(val instanceUrl: String, val outboxDepth: Int)
```
A non-zero `outboxDepth` adds a line naming how many decisions have not reached Nextcloud yet: they
survive in the outbox and push after reconnecting, which the user should know before disconnecting
rather than discover afterwards.

---

## 15. Notifications

Not screen state, but part of this seam — they are the UI when the app is closed, and they are
already half-built (`DownloadNotifications`, Tier 4b).

| Notification | Content | Tap target |
|---|---|---|
| Foreground service, while downloads run | "Downloading n episodes", current title, determinate progress, **Cancel all**. Shows *Paused* rather than progress when the queue is paused (§12.11). | S7 |
| Completion, one per batch | the count only; silent by default | S7 |
| Failure, only after retries are exhausted | the plain-language cause | S8 |
| Sync | **never.** No notification for sync, ever. | — |

Progress uses the same 1 Hz throttle as the UI, from the same source, so the notification and S7 can
never disagree. `FOREGROUND_SERVICE_TYPE_DATA_SYNC`, as built.

---

## 16. Accessibility contract

These are state and semantics decisions, so they belong here rather than in a style guide:

- **Selection mode is reachable without a long-press.** When a touch-exploration service is active,
  a checkbox appears on the leading artwork of every row. That is a state input the ViewModel needs:
  `EpisodeListUiState.showsSelectionAffordance: Boolean`, driven by `AccessibilityManager`, not by a
  `LocalInspectionMode`-style guess.
- **Swipe actions are duplicated as custom accessibility actions**, with labels read from
  `SwipeMapping` — the announced label follows the configured mapping, so a remapped swipe never
  announces the wrong verb.
- **Every status is carried by text**, never colour alone. Greyed-out rows announce their state word
  ("played", "handled elsewhere"), not just a visual change.
- **Progress is announced as text** ("downloading, 62 percent"), throttled — a 1 Hz announcement is
  correct; a per-frame one is unusable.
- **Artwork carries a content description** ("cover art for <podcast>"); the monogram fallback tile
  carries the same one, not "no image".
- **Selection changes announce `n selected`** on every toggle, and sticky month headers are exposed
  as list headings so the fast-scroll thumb is not the only way through a long list.

---

## 17. Icons — the technical half

Which icon carries which meaning is a UX decision and lives in **`UI.md` §18**, the single
canonical mapping; do not restate it here or the two will drift. What belongs here is how they get
into the app.

### Android has no runtime SVG support

This matters and my first pass got it wrong. Android cannot render an `.svg` at runtime at all — the
platform's vector format is **`VectorDrawable`** (XML in `res/drawable`), and Compose's is
**`ImageVector`**. `stroke="currentColor"` is not valid in either; a converter emits a literal colour
which the call site then overrides with a tint (`Icon(painter, tint = …)`, or `android:tint`).

So an SVG in the repo is *source material*, never a shipped asset. Which raises the real question:

### Prefer the Lucide artifact over converting by hand

CLAUDE.md's guiding rule — **use existing libraries, don't invent replacements** — applies here.
Hand-converting 27 SVGs, keeping their names in step with the table in §18, and re-converting whenever
the set changes is exactly the kind of hand-rolled pipeline that rule exists to prevent. Lucide ships
for Compose:

- `com.composables:icons-lucide-android` (Maven Central) — the Android variant bundles the icons as
  **Vector Drawables** reachable through `R.drawable`, so `painterResource` works and Android Studio
  gives XML previews in autocomplete.
- The Compose-Multiplatform variant of the same set exposes them as `ImageVector` extensions
  (`Lucide.Download`), which reads better at the call site but has no IDE preview.

Either is a **new dependency and therefore needs author approval** per CLAUDE.md §3 — it is not
pre-approved, so it is a decision, not a given. The trade is: one dependency carrying ~1.7k icons
where 27 are used (tree-shaking/R8 handles the rest) against 27 checked-in XML files that are ours to
maintain. Recommendation: take the dependency, and treat §18's table as the allow-list rather than as
a manifest of files.

If the author would rather not add the dependency, the fallback is Android Studio's **Vector Asset
Studio** (`New → Vector Asset → Local file`) on the exported SVGs in `assets/icons/` — a one-off
conversion producing 27 `res/drawable/ic_*.xml` files. Those exports exist for exactly this path; they
are not intended to be committed as `.svg`.

### Scaling

Neither format has a density problem: both are vector, sized in `dp`, rasterised per-device at
runtime — no `-hdpi`/`-xxhdpi` buckets, no bitmap variants, nothing to re-export per screen size.
Three real caveats, none about screen density:

- **Don't render Lucide below ~20 dp.** The 2 dp stroke is drawn for a 24 dp grid; smaller and the
  strokes thin out and the joins mush. Every icon in these designs is 16–21 dp *drawn* — the smaller
  ones are inline markers beside text, and if any read weakly on a real device, raise the size rather
  than thinning the stroke.
- **Icons do not scale with font scale, deliberately.** At large accessibility font sizes the text
  grows and the icons hold at 24 dp; the title truncates first (`UI.md` §12.12). An icon that
  grows with the type breaks every row height in the app.
- **The glyph is not the target.** 24 dp drawn inside a ≥ 48 dp touch target (§12) — the padding is
  part of the control, not decoration.

The **monogram tile** is not an icon and needs no drawable: when `Feed.imageUrl` is null or not yet
fetched, the artwork slot renders as a filled surface square with the feed's first letter at the
heading weight in the muted role. Same content description as real artwork ("cover art for
<podcast>"), never "no image".
