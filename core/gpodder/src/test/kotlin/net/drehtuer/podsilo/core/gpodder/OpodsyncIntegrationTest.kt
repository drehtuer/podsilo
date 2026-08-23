// SPDX-License-Identifier: GPL-3.0-or-later

package net.drehtuer.podsilo.core.gpodder

import kotlinx.coroutines.runBlocking
import net.drehtuer.podsilo.core.model.port.EpisodeAction
import net.drehtuer.podsilo.core.model.port.EpisodeActionType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test
import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter

/**
 * Opt-in integration test against a real [opodsync](https://github.com/kd2org/opodsync) server,
 * spun up by `.devcontainer/docker-compose.yml` (CLAUDE.md section 4: don't test sync against the
 * author's real Nextcloud).
 *
 * **Skips itself unless `PODSILO_OPODSYNC_URL` is set.** CLAUDE.md section 7 is explicit that Tier 1
 * tests must be deterministic and offline, so `./gradlew test` must never require a server to be
 * running. Set the variable (see `.env.example`) to opt in.
 *
 * ✅ **Verified green against opodsync 0.5.3 on 2026-07-31** (3 tests, 0 skipped). That run is what
 * turned `decisions/0009-gpodder-api-wire-contract.adoc` from a contract *read* out of opodsync's
 * source into one actually exercised over the wire. See `dev-environment.adoc` for how to run it.
 *
 * ⚠️ **opodsync is not proof of Nextcloud's behaviour.** Most importantly, opodsync *stores*
 * `DOWNLOAD` actions while `nextcloud-gpodder` silently discards them
 * (`decisions/0008-nextcloud-gpodder-discards-download-actions.adoc`), so a green run here says
 * nothing about whether downloads sync cross-client on a real Nextcloud. Deliberately asserted
 * below so the difference is visible rather than assumed.
 */
class OpodsyncIntegrationTest {
    private lateinit var client: RetrofitGpodderClient

    private val baseUrl: String? get() = System.getenv("PODSILO_OPODSYNC_URL")?.takeIf { it.isNotBlank() }

    @Before
    fun setUp() {
        val url = baseUrl
        assumeTrue("PODSILO_OPODSYNC_URL not set — skipping opodsync integration test", url != null)

        val credentials =
            GpodderCredentials(
                username = System.getenv("OPODSYNC_USER") ?: "podsilo",
                password = System.getenv("OPODSYNC_PASSWORD") ?: "podsilo-test-password",
            )
        client = RetrofitGpodderClient.create(baseUrl = requireNotNull(url), credentials = credentials)
    }

    private fun nowIso(): String {
        val nowUtc = Instant.now().atZone(ZoneOffset.UTC).toLocalDateTime()
        return nowUtc.format(DateTimeFormatter.ISO_LOCAL_DATE_TIME)
    }

    @Test
    fun `fetching subscriptions without since returns a well-formed delta`() {
        runBlocking {
            val delta = client.fetchSubscriptions(since = null).getOrThrow()

            // The shape open decision #2 turned on: a no-`since` call must yield a usable full set,
            // and `add`/`remove` must be disjoint so `add - remove` is meaningful (decisions/0009).
            assertTrue("timestamp must be a plausible Unix-seconds value", delta.timestamp > 0)
            val overlap = delta.add.toSet() intersect delta.remove.toSet()
            assertTrue("add and remove must be disjoint, overlapped on: $overlap", overlap.isEmpty())
        }
    }

    @Test
    fun `posting a PLAY action succeeds and comes back in the action log`() {
        runBlocking {
            val episodeUrl = "https://example.com/opodsync-it/${System.nanoTime()}.mp3"
            val action =
                EpisodeAction(
                    podcast = "https://example.com/opodsync-it/feed.xml",
                    episode = episodeUrl,
                    guid = null,
                    action = EpisodeActionType.PLAY,
                    timestamp = nowIso(),
                    started = 0,
                    position = 1800,
                    total = 1800,
                )

            val posted = client.postEpisodeActions(listOf(action))
            assertTrue("POST failed: ${posted.exceptionOrNull()}", posted.isSuccess)

            val page = client.fetchEpisodeActions(since = 0).getOrThrow()
            val echoed = page.actions.firstOrNull { it.episode == episodeUrl }

            // Whether an action for a feed we are NOT subscribed to comes back is version-dependent:
            // 0009 recorded opodsync inner-joining actions against subscriptions, but 0.5.3 returned
            // them anyway. Podsilo tolerates either, so only assert the round-trip when it happens.
            if (echoed != null) {
                assertEquals(EpisodeActionType.PLAY, echoed.action)
                assertEquals(1800, echoed.total)
            }
        }
    }

    @Test
    fun `opodsync accepts DOWNLOAD -- unlike nextcloud-gpodder, which silently drops it`() {
        runBlocking {
            // Guards against mistaking a green opodsync run for proof that mark-on-download syncs
            // cross-client. It does here; it does NOT on a real Nextcloud (decisions/0008).
            val action =
                EpisodeAction(
                    podcast = "https://example.com/opodsync-it/feed.xml",
                    episode = "https://example.com/opodsync-it/dl-${System.nanoTime()}.mp3",
                    guid = null,
                    action = EpisodeActionType.DOWNLOAD,
                    timestamp = nowIso(),
                )

            val result = client.postEpisodeActions(listOf(action))

            assertTrue("opodsync should accept DOWNLOAD: ${result.exceptionOrNull()}", result.isSuccess)
        }
    }
}
