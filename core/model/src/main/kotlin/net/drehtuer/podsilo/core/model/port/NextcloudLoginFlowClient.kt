// SPDX-License-Identifier: GPL-3.0-or-later

package net.drehtuer.podsilo.core.model.port

/**
 * Port for **Nextcloud Login Flow v2**, implemented in `:core:gpodder` (still a JVM module —
 * nothing here touches an Android API; launching the browser is the UI's concern, delivered as a
 * one-shot effect, `docs/decisions/0007`).
 *
 * This is the *only* way Podsilo obtains credentials. The app never sees, asks for, or stores a
 * user's Nextcloud password: the flow hands back an **app password**, which is what gets encrypted
 * and persisted (CLAUDE.md §5, `docs/decisions/0010`). There is no username/password form anywhere
 * in the UI and there must never be one.
 *
 * Distinct from [GpodderClient], which speaks the three gpoddersync endpoints — these three live
 * on Nextcloud core and exist before any credentials do.
 */
interface NextcloudLoginFlowClient {
    /**
     * `POST /index.php/login/v2`. [baseUrl] is whatever the user typed, normalised — a Nextcloud in
     * a subdirectory is common, so a path is legal here.
     *
     * A failure at this step usually means "not a Nextcloud", which is a different message to the
     * user than a refused authorization (`docs/UI.md` §8).
     */
    suspend fun start(baseUrl: String): Result<LoginFlow>

    /**
     * Polls [LoginFlow.pollEndpoint] until the user grants access in the browser, they cancel, or
     * it times out. Nextcloud answers 404 while the flow is pending, which is *not* an error — the
     * implementation absorbs that and keeps waiting; cancelling the coroutine aborts the poll.
     */
    suspend fun poll(flow: LoginFlow): Result<LoginResult>

    /**
     * An authenticated `GET /index.php/apps/gpoddersync/subscriptions`, purely to prove the app is
     * installed and reachable with these credentials.
     *
     * **Success is only ever claimed after this returns 200.** A completed login flow proves the
     * server is a Nextcloud and the password is good; it says nothing about whether gpoddersync is
     * installed, and connecting to a Nextcloud without it would leave the user with an app that
     * silently syncs nothing. On failure the app password is discarded rather than stored.
     */
    suspend fun verifyGpodderSync(credentials: NextcloudCredentials): Result<Unit>
}

/**
 * The started flow. [loginUrl] is opened in a Custom Tab; [pollEndpoint] and [token] are what
 * [NextcloudLoginFlowClient.poll] uses. Both URLs come from the server rather than being built
 * locally — a Nextcloud behind a reverse proxy can legitimately return a different host than the
 * one the user typed.
 */
data class LoginFlow(
    val loginUrl: String,
    val pollEndpoint: String,
    val token: String,
)

/**
 * What the flow hands back once the user grants access. [serverUrl] is the server's own canonical
 * URL — persist that, not the typed one.
 */
data class LoginResult(
    val serverUrl: String,
    val loginName: String,
    val appPassword: String,
) {
    val credentials: NextcloudCredentials
        get() = NextcloudCredentials(serverUrl = serverUrl, username = loginName, appPassword = appPassword)
}

/**
 * Why a login attempt failed, in the vocabulary S5 renders (`docs/UI.md` §8).
 *
 * **Distinguishing these is the whole point.** "Check the spelling and your network" and "this
 * Nextcloud has no GPodder Sync app installed" are different problems with different fixes, and a
 * single "login failed" hides both. It lives on the port rather than beside the Retrofit
 * implementation because the *kind* of failure is part of the contract the UI binds to — a caller
 * that cannot tell them apart can only ever show one message, which is the bug this prevents.
 */
enum class LoginFlowFailure { UNREACHABLE, TLS, NOT_NEXTCLOUD, NO_GPODDERSYNC, UNAUTHORIZED, ABANDONED }

/** Carries a [LoginFlowFailure] out through `Result.failure`. **Never contains a credential.** */
class LoginFlowException(
    val failure: LoginFlowFailure,
    message: String,
) : Exception(message)
