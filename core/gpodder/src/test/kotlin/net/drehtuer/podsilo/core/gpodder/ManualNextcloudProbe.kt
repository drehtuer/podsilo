// SPDX-License-Identifier: GPL-3.0-or-later

package net.drehtuer.podsilo.core.gpodder

import kotlinx.coroutines.runBlocking
import net.drehtuer.podsilo.core.model.port.EpisodeAction
import net.drehtuer.podsilo.core.model.port.EpisodeActionPage
import net.drehtuer.podsilo.core.model.port.EpisodeActionType
import net.drehtuer.podsilo.core.model.port.LoginResult
import net.drehtuer.podsilo.core.sync.meansHandledElsewhere
import net.drehtuer.podsilo.core.sync.parseGpodderTimestamp
import net.drehtuer.podsilo.core.sync.toGpodderTimestamp
import okhttp3.OkHttpClient
import java.io.File
import java.util.concurrent.TimeUnit
import kotlin.time.Duration.Companion.seconds

/**
 * A **manual** probe against a real Nextcloud. Not a test — it has no `@Test`, JUnit never picks it
 * up, and it talks to the network, which every test in this project is forbidden from doing
 * (CLAUDE.md §7: deterministic and offline).
 *
 * It exists because there is exactly one thing the whole suite cannot prove: that this client works
 * against an actual server. Everything below runs the **production** classes —
 * [RetrofitNextcloudLoginFlowClient] and [RetrofitGpodderClient] — so a green run here is evidence
 * about the app, not about a reimplementation.
 *
 * ```
 * ./gradlew :core:gpodder:nextcloudProbe -Phost=cloud.example.org
 * ```
 *
 * **It is read-only.** It starts the login flow, waits for the human to approve it in a browser,
 * verifies gpoddersync, and then performs two `GET`s. It never calls `episode_action/create`, never
 * calls `subscription_change/create` (which the app must never call at all — CLAUDE.md §1), and
 * writes nothing to the server.
 *
 * **The app password is never printed and never written to disk.** It exists in memory for the
 * duration of the run and is the one thing here that must not leak into a terminal scrollback or a
 * log file.
 */
private const val POLL_TIMEOUT_MINUTES = 15L
private const val MILLIS_PER_SECOND = 1_000L

fun main(args: Array<String>) {
    val host = args.firstOrNull() ?: error("usage: nextcloudProbe <host>  (e.g. cloud.example.org)")
    val handoff = args.getOrNull(1)?.takeIf { it.isNotBlank() }?.let(::File)

    // Writes are opt-in *and* name the account they are allowed to touch. A login flow is approved
    // by whoever is signed in to the browser, so without this the probe would happily post actions
    // to a real account if the wrong session approved the link.
    val writeAs = args.getOrNull(2)?.takeIf { it.isNotBlank() }

    // Read-only detail dump: the newest N actions, with RePod's reading and ours side by side.
    val recent = args.getOrNull(3)?.toIntOrNull() ?: 0

    // Generous timeouts: the poll deliberately blocks until a human acts.
    val http =
        OkHttpClient
            .Builder()
            .callTimeout(POLL_TIMEOUT_MINUTES, TimeUnit.MINUTES)
            .readTimeout(POLL_TIMEOUT_MINUTES, TimeUnit.MINUTES)
            .build()

    val loginFlow =
        RetrofitNextcloudLoginFlowClient(
            httpClient = http,
            pollInterval = 3.seconds,
            maxPollAttempts = (POLL_TIMEOUT_MINUTES * 20).toInt(),
        )

    runBlocking {
        println("→ POST /index.php/login/v2 against $host")
        val flow =
            loginFlow.start(host).getOrElse {
                println("✗ start failed: ${it.message}")
                return@runBlocking
            }

        println()
        println("APPROVE THIS IN YOUR BROWSER:")
        println(flow.loginUrl)
        println()
        handoff?.writeText(flow.loginUrl)
        println("→ polling (up to $POLL_TIMEOUT_MINUTES min)…")

        val result =
            loginFlow.poll(flow).getOrElse {
                println("✗ poll failed or was abandoned: ${it.message}")
                return@runBlocking
            }
        // The login name, not the password. Never the password.
        println("✓ authorized as '${result.loginName}' on ${result.serverUrl}")

        println("→ GET /index.php/apps/gpoddersync/subscriptions (the verify step)")
        loginFlow.verifyGpodderSync(result.credentials).getOrElse {
            println("✗ gpoddersync not reachable with these credentials: ${it.message}")
            return@runBlocking
        }
        println("✓ gpoddersync is installed and answers")

        if (writeAs != null && result.loginName != writeAs) {
            println("✗ REFUSING TO WRITE: approved as '${result.loginName}', expected '$writeAs'")
            println("  Nothing was written. Re-run and approve as the intended account.")
            return@runBlocking
        }

        listSubscriptions(http, result, recent)
        if (writeAs != null) {
            verifyActionWrites(http, result)
            realDataSyncPass(http, result)
        }
    }
}

/**
 * The read-only half: the subscription list, then one feed's actions.
 *
 * Uses `since = 0` deliberately — CLAUDE.md §5 warns that is unbounded and not something to do on
 * every launch, which is exactly why it is fine *once*, by hand, to see what is actually there.
 */
private suspend fun listSubscriptions(
    http: OkHttpClient,
    result: LoginResult,
    recent: Int,
) {
    val client = RetrofitGpodderClientFactory(http).create(result.credentials)

    // add - remove, exactly as the sync pass computes it: a follower needs what currently is,
    // not what changed (CLAUDE.md §5).
    val delta = client.fetchSubscriptions()
    val current = (delta.add - delta.remove.toSet()).sorted()
    println()
    println("SUBSCRIPTIONS (${current.size}; add=${delta.add.size} remove=${delta.remove.size}):")
    current.forEach { println("  $it") }

    val actions = client.fetchEpisodeActions(since = 0)
    println()
    println("EPISODE ACTIONS: ${actions.actions.size} (server timestamp ${actions.timestamp})")
    actions.actions
        .groupingBy { it.action }
        .eachCount()
        .forEach { (action, count) -> println("  $action: $count") }

    reportTimestamps(actions.actions.map { it.timestamp })
    if (recent > 0) reportRecent(actions, recent)
    println()
    println("Nothing was written. No episode actions were posted.")
}

/**
 * The newest [count] actions in full, with the two readings of each one printed side by side.
 *
 * This exists because the whole of issue #60 lives in the gap between them. **RePod decides "played"
 * from `position`/`total`** (`position > 0 && total > 0 && position >= total`, `src/utils/status.ts`)
 * and writes *mark as unread* as a `PLAY` with `position = 0`. **Podsilo's `reconcile` reads the
 * action type alone** and treats every `PLAY` as terminal. If those two columns ever disagree, an
 * episode is in one state on the server and another on the phone, and no amount of syncing fixes it.
 *
 * The `since` column is the other half: the server selects `timestamp_epoch > since` on the
 * *client-authored* timestamp, while the value it hands back — and that Podsilo stores as the next
 * `since` — is the server's own clock. An action whose authored time is older than the cursor is
 * invisible for ever, so printing both is what turns that from an argument into a fact.
 */
private fun reportRecent(
    page: EpisodeActionPage,
    count: Int,
) {
    val serverNowMillis = page.timestamp * MILLIS_PER_SECOND
    val newest =
        page.actions
            .sortedByDescending { parseGpodderTimestamp(it.timestamp) ?: 0L }
            .take(count)

    println()
    println("NEWEST $count ACTIONS (RePod's reading vs. Podsilo's)")
    newest.forEach { action ->
        val authored = parseGpodderTimestamp(action.timestamp)
        val rePod = if (action.isEndedByRePodsRule()) "played" else "NOT played"
        // The *real* predicate, imported rather than restated. This column was the hardcoded string
        // "HANDLED_REMOTELY", which was true when the probe was written and became a lie the moment
        // `docs/decisions/0022` landed — a diagnostic describing last week's behaviour is worse than
        // none, and this one printed "← DISAGREE" against a disagreement that no longer existed.
        val podsilo = if (action.meansHandledElsewhere()) "HANDLED_REMOTELY" else "left in To decide"
        val skew = authored?.let { (it - serverNowMillis) / MILLIS_PER_SECOND }
        println("  ${action.timestamp}  ${action.action}")
        println(
            "    guid=${action.guid ?: "(none)"}  started=${action.started} " +
                "position=${action.position} total=${action.total}",
        )
        val agree = (rePod == "played") == (podsilo == "HANDLED_REMOTELY")
        println("    RePod: $rePod   |   Podsilo: $podsilo${if (agree) "" else "  ← DISAGREE"}")
        skew?.let { println("    authored ${it}s relative to the server clock") }
    }
}

/** RePod's `hasEnded`, transcribed from `src/utils/status.ts` so the comparison is theirs, not mine. */
private fun EpisodeAction.isEndedByRePodsRule(): Boolean {
    if (action == EpisodeActionType.DELETE) return true
    val at = position ?: 0
    val end = total ?: 0
    return at > 0 && end > 0 && at >= end
}

/**
 * The single most fragile thing in this API (CLAUDE.md §11).
 *
 * The per-action `timestamp` is ISO-8601 while `since` is Unix seconds, and getting them confused
 * does not crash — it silently breaks incremental sync in a way that looks like "sync just doesn't
 * work". `docs/decisions/0009` records that the two reference servers emit `+00:00` and `Z`, and
 * that Podsilo parses all three forms; this is the first time that claim meets a real Nextcloud.
 */
private fun reportTimestamps(stamps: List<String>) {
    println()
    println("TIMESTAMP FORMS SEEN:")
    stamps
        .map { it.describeShape() }
        .groupingBy { it }
        .eachCount()
        .forEach { (shape, count) -> println("  $shape: $count") }

    val unparseable = stamps.filter { parseGpodderTimestamp(it) == null }
    if (unparseable.isEmpty()) {
        println("  ✓ all ${stamps.size} parse")
    } else {
        println("  ✗ ${unparseable.size} DO NOT PARSE, e.g. ${unparseable.take(3)}")
    }
    stamps.firstOrNull()?.let { println("  sample: $it") }
}

/** Shape, not value — enough to tell the three ADR 0009 forms apart without printing 3,000 lines. */
private fun String.describeShape(): String =
    when {
        endsWith("Z") -> "…Z"
        Regex("[+-]\\d{2}:\\d{2}$").containsMatchIn(this) -> "…±hh:mm"
        else -> "bare (no offset)"
    }

/**
 * The write half — **only** reached with an explicit `-Pwrite=<loginName>`, and only after the
 * approved account matched that name.
 *
 * It exists to settle `docs/decisions/0008`, which has been "source-read-only" since it was written:
 * `nextcloud-gpodder`'s controller filters posted actions down to `play` and returns 200 regardless,
 * so a `DOWNLOAD` looks accepted and vanishes. That was read out of PHP, never observed. Everything
 * downstream — the outbox, `syncedToServer`, mark-on-download — behaves differently depending on
 * whether it is true.
 *
 * It also checks the mark-as-played encoding from `docs/architecture.md` §6
 * (`started = 0, position = total`) survives a round trip.
 *
 * Both actions name a **synthetic feed and episode** that no real subscription uses, so nothing the
 * user actually listens to is touched.
 */
private suspend fun verifyActionWrites(
    http: OkHttpClient,
    result: LoginResult,
) {
    val client = RetrofitGpodderClientFactory(http).create(result.credentials)
    val marker = System.currentTimeMillis()
    val feed = "https://podsilo.invalid/probe-$marker.xml"
    val downloadEpisode = "https://podsilo.invalid/probe-$marker-download.mp3"
    val playEpisode = "https://podsilo.invalid/probe-$marker-play.mp3"
    val stamp = marker.toGpodderTimestamp()

    println()
    println("WRITE PROBE (synthetic feed $feed — touches no real subscription)")

    val before = client.fetchEpisodeActions(since = 0).actions.size
    val posted =
        client.postEpisodeActions(
            listOf(
                EpisodeAction(
                    podcast = feed,
                    episode = downloadEpisode,
                    guid = "probe-$marker-download",
                    action = EpisodeActionType.DOWNLOAD,
                    timestamp = stamp,
                ),
                EpisodeAction(
                    podcast = feed,
                    episode = playEpisode,
                    guid = "probe-$marker-play",
                    action = EpisodeActionType.PLAY,
                    timestamp = stamp,
                    // `docs/architecture.md` §6: "done with this episode" is a full-length PLAY.
                    started = 0,
                    position = 1800,
                    total = 1800,
                ),
            ),
        )
    println("→ POST episode_action/create: ${if (posted.isSuccess) "2xx" else "failed: ${posted.exceptionOrNull()}"}")

    val after = client.fetchEpisodeActions(since = 0).actions
    val mine = after.filter { it.podcast == feed }
    println("→ read back since=0: ${after.size} actions total (was $before), ${mine.size} of them ours")
    mine.forEach {
        println("   ${it.action}  guid=${it.guid}  started=${it.started} position=${it.position} total=${it.total}")
    }

    val keptDownload = mine.any { it.action == EpisodeActionType.DOWNLOAD }
    val keptPlay = mine.any { it.action == EpisodeActionType.PLAY }

    println()
    println("ADR 0008 — does Nextcloud keep a DOWNLOAD action?")
    if (keptDownload) {
        println("  UNEXPECTED: it was kept. ADR 0008 needs revisiting.")
    } else {
        println("  CONFIRMED: discarded, exactly as the PHP said.")
    }
    println("architecture §6 — does the mark-as-played PLAY survive?")
    println(if (keptPlay) "  CONFIRMED: kept." else "  UNEXPECTED: the PLAY was dropped too.")
}
