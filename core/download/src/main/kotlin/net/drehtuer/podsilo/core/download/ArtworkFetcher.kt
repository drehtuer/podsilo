// SPDX-License-Identifier: GPL-3.0-or-later

package net.drehtuer.podsilo.core.download

import okhttp3.OkHttpClient
import okhttp3.Request

/** What gets embedded, and where it came from — the source is worth logging when it is the fallback. */
data class EpisodeArtwork(
    val bytes: ByteArray,
    val mimeType: String,
    val source: Source,
) {
    enum class Source { EPISODE, PODCAST }

    // ByteArray in a data class: equals/hashCode must compare contents, or two identical downloads
    // would compare unequal and a test asserting "same artwork" would silently never fire.
    override fun equals(other: Any?): Boolean =
        this === other ||
            (
                other is EpisodeArtwork &&
                    bytes.contentEquals(other.bytes) &&
                    mimeType == other.mimeType &&
                    source == other.source
            )

    override fun hashCode(): Int = 31 * (31 * bytes.contentHashCode() + mimeType.hashCode()) + source.hashCode()
}

/**
 * Fetches the cover to embed in a downloaded episode: the episode's own artwork if the feed supplied
 * one, otherwise the podcast's.
 *
 * **No size cap** — deliberate, and the author's decision. Real podcast art runs ~300 KB against a
 * 30 MB episode, so capping would complicate the pipeline to save about one percent, and any cap
 * would eventually drop the cover of some podcast for a reason the user could not see.
 *
 * Everything here is **best-effort**. CLAUDE.md §6 is explicit that a tagging problem must never
 * lose a successful download, and artwork is the most optional part of tagging: a dead image host,
 * an HTML error page served with a 200, or a feed that lists no image at all all resolve to `null`,
 * and the episode is delivered untouched.
 */
class ArtworkFetcher(
    private val httpClient: OkHttpClient,
) {
    /**
     * @param episodeImageUrl `Episode.imageUrl` — the per-item cover, usually absent.
     * @param podcastImageUrl `Feed.imageUrl` — the fallback, usually present.
     */
    fun fetch(
        episodeImageUrl: String?,
        podcastImageUrl: String?,
    ): EpisodeArtwork? {
        val candidates =
            listOfNotNull(
                episodeImageUrl?.takeIf { it.isNotBlank() }?.let { it to EpisodeArtwork.Source.EPISODE },
                podcastImageUrl?.takeIf { it.isNotBlank() }?.let { it to EpisodeArtwork.Source.PODCAST },
            )
        // First that actually yields an image. A listed-but-broken episode cover falls through to
        // the podcast's rather than leaving the episode with none.
        return candidates.firstNotNullOfOrNull { (url, source) -> download(url, source) }
    }

    private fun download(
        url: String,
        source: EpisodeArtwork.Source,
    ): EpisodeArtwork? =
        runCatching {
            httpClient.newCall(Request.Builder().url(url).build()).execute().use { response ->
                if (!response.isSuccessful) return@use null
                val body = response.body ?: return@use null
                // Trust the response's own type over the URL's extension, the same rule the
                // enclosure's extension resolution follows (CLAUDE.md §6).
                val contentType = response.header("Content-Type")?.substringBefore(';')?.trim()
                if (contentType == null || !contentType.startsWith("image/")) return@use null
                val bytes = body.bytes()
                if (bytes.isEmpty()) null else EpisodeArtwork(bytes, contentType, source)
            }
        }.getOrNull()
}
