// SPDX-License-Identifier: GPL-3.0-or-later

package net.drehtuer.podsilo.core.feed

import com.prof18.rssparser.RssParser

/**
 * Parses raw feed bytes into [ParsedFeed]. Fetching those bytes over HTTP (conditional `GET`,
 * `ETag`/`Last-Modified`, redirects) is `:core:feed`'s HTTP-fetch layer's job (Tier 3, MockWebServer
 * -tested, not built yet) -- this class only ever sees bytes already in hand, so its tests need
 * no network at all (CLAUDE.md section 7 item 2).
 *
 * Uses rssparser (`architecture.adoc` §7, not Stalla). [rssParser]'s default construction eagerly
 * creates an `OkHttpClient` internally (for its own `getRssChannel(url)` entry point) but that
 * client is never touched by [parse], which only calls the byte-string [RssParser.parse] overload.
 */
class FeedXmlParser(
    private val rssParser: RssParser = RssParser(),
) {
    suspend fun parse(
        feedUrl: String,
        xmlBytes: ByteArray,
    ): ParsedFeed {
        val xmlString = decodeFeedXml(xmlBytes)
        val channel = rssParser.parse(xmlString)
        return channel.toParsedFeed(feedUrl)
    }
}
