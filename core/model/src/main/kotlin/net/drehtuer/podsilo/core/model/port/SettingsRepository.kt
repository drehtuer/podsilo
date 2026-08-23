// SPDX-License-Identifier: GPL-3.0-or-later

package net.drehtuer.podsilo.core.model.port

import kotlinx.coroutines.flow.Flow
import java.time.Instant
import java.time.Period
import java.time.ZoneId

/**
 * Port for user-configurable settings, implemented in `:core:datastore` (Jetpack DataStore, with
 * the Nextcloud app password encrypted via a Keystore-backed cipher — never plaintext, CLAUDE.md
 * §5). Lives in Android-free `:core:model` so `:core:sync` and the feature view models depend on
 * the interface, not the DataStore implementation (`docs/architecture.adoc` §2).
 *
 * Everything except the app password is observable as a [Flow] so the UI (and the live naming
 * preview in `:feature:settings`) reacts to edits. The password is read-only through the suspend
 * [nextcloudCredentials] accessor rather than a hot [Flow], so the decrypted secret is never held
 * in a long-lived stream — it's fetched at the point of use (a sync pass) and dropped.
 *
 * `@Suppress("TooManyFunctions")`: the method count here *is* the setting count — one observe and
 * one set per value — so it grows linearly with the settings screen and says nothing about
 * complexity. Related fields that are genuinely read together are already grouped behind a single
 * type ([NamingSettings], [SwipeMapping]); grouping the rest would only make each observer
 * recompose on changes it does not care about.
 */
@Suppress("TooManyFunctions")
interface SettingsRepository {
    fun observeNaming(): Flow<NamingSettings>

    suspend fun setNaming(settings: NamingSettings)

    fun observeDownloadFolderUri(): Flow<String?>

    suspend fun setDownloadFolderUri(uri: String?)

    /**
     * How often the **feed refresh** runs in the background. Named for sync because it once timed
     * a periodic sync pass too; since `docs/decisions/0026` there is no such pass — every sync is
     * something the user asked for — and this is the feed-refresh interval only. The key is left
     * alone rather than migrated: it is one value, stored once, with no user-visible label.
     */
    fun observeSyncIntervalMinutes(): Flow<Long>

    suspend fun setSyncIntervalMinutes(minutes: Long)

    fun observeTheme(): Flow<ThemePreference>

    suspend fun setTheme(theme: ThemePreference)

    /**
     * Which triage action each swipe direction performs (`docs/UI.adoc` §12.1). Persisted because the
     * swipe background's icon and word are rendered *from* this — so the UI cannot show one verb
     * and perform another.
     */
    fun observeSwipeMapping(): Flow<SwipeMapping>

    suspend fun setSwipeMapping(mapping: SwipeMapping)

    /**
     * Whether downloads may run on a metered network. **A constraint, not a rule** — off by default,
     * and it only decides *when* an already-requested download runs, never *whether* one is
     * requested. Read by the UI and by `WorkScheduler`, which turns it into a WorkManager
     * `NetworkType`.
     */
    fun observeAllowMobileData(): Flow<Boolean>

    suspend fun setAllowMobileData(allowed: Boolean)

    /**
     * When the user last cleared S7's *delivered* list.
     *
     * A **display cursor, not a delete.** That list is projected from `DOWNLOADED` ledger rows, and
     * those rows are the record that stops an episode being fetched again (CLAUDE.md §11) — clearing
     * them would re-download everything the user has ever downloaded. So the rows stay and the list
     * hides anything actioned at or before this instant. `0` means never cleared.
     */
    fun observeDeliveredClearedAt(): Flow<Long>

    suspend fun setDeliveredClearedAt(millis: Long)

    /**
     * The *mark old episodes as played* cutoff (`docs/decisions/0013`). [OlderThan.OFF] by default:
     * this rule **writes** `SKIPPED` rows and emits `PLAY` actions other clients will see, so it is
     * opt-in and its first bulk application always goes through the counted preview.
     */
    fun observeMarkOldOlderThan(): Flow<OlderThan>

    suspend fun setMarkOldOlderThan(value: OlderThan)

    /** Non-secret connection fields, observable for the settings UI (URL + username, never the password). */
    fun observeNextcloudAccount(): Flow<NextcloudAccount?>

    /**
     * Reads and decrypts the full credentials for a sync pass. `null` when the user has not
     * configured an account yet. The decrypted app password is only ever materialised here.
     */
    suspend fun nextcloudCredentials(): NextcloudCredentials?

    suspend fun setNextcloudCredentials(credentials: NextcloudCredentials?)
}

/**
 * @property folderTemplate Default `{podcast}` (CLAUDE.md §6).
 * @property fileTemplate Default `{date}_{title}` — date first so files sort correctly in any
 *   browser/player (§6 forbids a title-first default).
 * @property transliterate Default `false`: non-ASCII (umlauts, CJK) survives by default (§6).
 * @property titleCleanupRules Ordered find/replace rules applied to the raw title before
 *   sanitising, default empty (opt-in). Held here as plain pattern/replacement strings — the
 *   `Regex` compilation belongs to `:core:naming`, which this Android-free module must not depend
 *   on.
 */
data class NamingSettings(
    val folderTemplate: String = DEFAULT_FOLDER_TEMPLATE,
    val fileTemplate: String = DEFAULT_FILE_TEMPLATE,
    val transliterate: Boolean = false,
    val titleCleanupRules: List<TitleCleanupRuleSetting> = emptyList(),
) {
    companion object {
        const val DEFAULT_FOLDER_TEMPLATE: String = "{podcast}"
        const val DEFAULT_FILE_TEMPLATE: String = "{date}_{title}"
    }
}

/** One persisted title-cleanup rule — a raw regex [pattern] and its [replacement]. See [NamingSettings]. */
data class TitleCleanupRuleSetting(
    val pattern: String,
    val replacement: String,
)

/** Non-secret Nextcloud connection fields, safe to expose to the UI. */
data class NextcloudAccount(
    val serverUrl: String,
    val username: String,
)

/**
 * Full Nextcloud credentials including the app password (CLAUDE.md §5: a Nextcloud **app
 * password**, HTTP Basic, not the account password). Only constructed transiently around a sync
 * pass; the password never lives in a persisted domain object beyond this.
 */
data class NextcloudCredentials(
    val serverUrl: String,
    val username: String,
    val appPassword: String,
) {
    val account: NextcloudAccount get() = NextcloudAccount(serverUrl, username)
}

/** Default background **feed refresh** cadence (best-effort — CLAUDE.md §11's Doze note). */
const val DEFAULT_SYNC_INTERVAL_MINUTES: Long = 240

/**
 * Light / dark / system, applied at the Compose root without recreating the activity
 * (`docs/UI.adoc` §12.7). Material You dynamic colour is deliberately **off** — one seed, two
 * schemes, so both can actually be verified.
 */
enum class ThemePreference { LIGHT, DARK, SYSTEM }

/** Which triage action a swipe performs. [NONE] disables that direction entirely. */
enum class SwipeAction { DOWNLOAD, MARK_AS_PLAYED, NONE }

enum class SwipeDirection { LEFT, RIGHT }

/**
 * The swipe configuration, with the "no direction may hold the same action as the other" invariant
 * enforced by [with] rather than by a check in a ViewModel — a mapping that violated it would make
 * two gestures do the same thing and leave one action unreachable.
 */
data class SwipeMapping(
    val right: SwipeAction = SwipeAction.DOWNLOAD,
    val left: SwipeAction = SwipeAction.MARK_AS_PLAYED,
) {
    fun actionFor(direction: SwipeDirection): SwipeAction =
        when (direction) {
            SwipeDirection.RIGHT -> right
            SwipeDirection.LEFT -> left
        }

    /**
     * Assigns [action] to [direction], **swapping** rather than rejecting when the other direction
     * already holds it (`docs/UI.adoc` §7) — the user's most recent choice is always honoured, and
     * the pair stays valid, so the swipe background can be rendered from state with no defensive
     * branch. [SwipeAction.NONE] is exempt: both directions may be disabled at once.
     */
    fun with(
        direction: SwipeDirection,
        action: SwipeAction,
    ): SwipeMapping {
        val other = actionFor(if (direction == SwipeDirection.RIGHT) SwipeDirection.LEFT else SwipeDirection.RIGHT)
        val displaced = if (action != SwipeAction.NONE && other == action) actionFor(direction) else other
        return when (direction) {
            SwipeDirection.RIGHT -> SwipeMapping(right = action, left = displaced)
            SwipeDirection.LEFT -> SwipeMapping(right = displaced, left = action)
        }
    }
}

/**
 * The *mark old episodes as played* cutoff (`docs/decisions/0013`). [OFF] by default — the rule
 * writes ledger rows and emits `PLAY` actions to the shared log, so it never runs unasked.
 *
 * `@Suppress("MagicNumber")`: each constant's name states its own number, so extracting
 * `MONTHS_3 = 3` beside `MONTH_3` would be strictly less readable, not more.
 */
@Suppress("MagicNumber")
enum class OlderThan(
    private val period: Period?,
) {
    OFF(null),
    MONTH_1(Period.ofMonths(1)),
    MONTH_3(Period.ofMonths(3)),
    MONTH_6(Period.ofMonths(6)),
    YEAR_1(Period.ofYears(1)),
    ;

    /**
     * The epoch-millis instant an episode must predate to be swept up, or `null` for [OFF].
     *
     * Calendar arithmetic, not `now - 90 days`: "3 months" has to mean three calendar months or the
     * cutoff drifts against what the label says. [zone] is passed in rather than read from the
     * device mid-calculation, for the same reason `:core:naming` takes one (`docs/architecture.adoc` §11).
     */
    fun cutoffMillis(
        now: Instant,
        zone: ZoneId,
    ): Long? =
        period?.let {
            now
                .atZone(zone)
                .minus(it)
                .toInstant()
                .toEpochMilli()
        }
}
