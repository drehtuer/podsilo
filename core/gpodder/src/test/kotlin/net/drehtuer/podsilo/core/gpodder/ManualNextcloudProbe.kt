// SPDX-License-Identifier: GPL-3.0-or-later

package net.drehtuer.podsilo.core.gpodder

import kotlinx.coroutines.runBlocking
import net.drehtuer.podsilo.core.model.port.LoginResult
import net.drehtuer.podsilo.core.sync.parseGpodderTimestamp
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

fun main(args: Array<String>) {
    val host = args.firstOrNull() ?: error("usage: nextcloudProbe <host>  (e.g. cloud.example.org)")
    val handoff = args.getOrNull(1)?.let(::File)

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

        listSubscriptions(http, result)
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
    println()
    println("Nothing was written. No episode actions were posted.")
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
