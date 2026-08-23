// SPDX-License-Identifier: GPL-3.0-or-later

package net.drehtuer.podsilo.core.feed

import kotlinx.coroutines.CancellationException
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.IOException

private const val HTTP_NOT_MODIFIED = 304

/**
 * Result of one conditional feed fetch.
 *
 * [httpEtag]/[httpLastModified] are echoed back from the response so the caller can persist them
 * onto `Feed` for the next conditional request (`docs/architecture.adoc` section 7's refresh
 * sequence) -- this class deliberately holds no state of its own between calls.
 */
sealed interface FeedFetchResult {
    /** 200 with a body. [bytes] are raw and undecoded -- [FeedXmlParser] handles the charset. */
    data class Fetched(
        val bytes: ByteArray,
        val httpEtag: String?,
        val httpLastModified: String?,
    ) : FeedFetchResult {
        // ByteArray in a data class gets identity equals/hashCode, which is a footgun in tests
        // and in any future set/map use. Compare by content instead.
        override fun equals(other: Any?): Boolean {
            if (this === other) return true
            if (other !is Fetched) return false
            return bytes.contentEquals(other.bytes) &&
                httpEtag == other.httpEtag &&
                httpLastModified == other.httpLastModified
        }

        override fun hashCode(): Int {
            var result = bytes.contentHashCode()
            result = 31 * result + (httpEtag?.hashCode() ?: 0)
            result = 31 * result + (httpLastModified?.hashCode() ?: 0)
            return result
        }
    }

    /** 304 -- the feed is unchanged; skip parsing entirely and keep the cached episodes. */
    data object NotModified : FeedFetchResult

    /** Non-2xx (other than 304). [code] lets the caller distinguish 404-gone from 5xx-retry-later. */
    data class HttpError(
        val code: Int,
        val message: String,
    ) : FeedFetchResult

    /** Network-level failure (DNS, connect, timeout, TLS) -- transient, worth retrying. */
    data class NetworkError(
        val reason: String,
    ) : FeedFetchResult
}

/**
 * Fetches feed XML over HTTP with conditional-GET support (CLAUDE.md section 7,
 * `docs/architecture.adoc` section 7): sends `If-None-Match`/`If-Modified-Since` from the previously
 * stored validators so an unchanged feed costs a 304 and no body.
 *
 * Failures are returned as [FeedFetchResult] values rather than thrown -- CLAUDE.md section 8:
 * model expected failures as return types. A feed server being down is entirely expected.
 *
 * Redirects are followed by OkHttp's default policy; nothing here overrides it.
 */
class FeedFetcher(
    private val okHttpClient: OkHttpClient = OkHttpClient(),
) {
    suspend fun fetch(
        feedUrl: String,
        httpEtag: String? = null,
        httpLastModified: String? = null,
    ): FeedFetchResult =
        try {
            executeFetch(feedUrl, httpEtag, httpLastModified)
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (network: IOException) {
            FeedFetchResult.NetworkError(network.message ?: "network error fetching feed")
        }

    private fun executeFetch(
        feedUrl: String,
        httpEtag: String?,
        httpLastModified: String?,
    ): FeedFetchResult {
        val request =
            Request
                .Builder()
                .url(feedUrl)
                .get()
                .apply {
                    httpEtag?.let { header("If-None-Match", it) }
                    httpLastModified?.let { header("If-Modified-Since", it) }
                }.build()

        okHttpClient.newCall(request).execute().use { response ->
            return when {
                response.code == HTTP_NOT_MODIFIED -> FeedFetchResult.NotModified
                !response.isSuccessful -> FeedFetchResult.HttpError(response.code, response.message)
                else ->
                    FeedFetchResult.Fetched(
                        bytes = response.body.bytes(),
                        httpEtag = response.header("ETag"),
                        httpLastModified = response.header("Last-Modified"),
                    )
            }
        }
    }
}
