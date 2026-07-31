// SPDX-License-Identifier: GPL-3.0-or-later

package net.drehtuer.podsilo.core.sync

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import net.drehtuer.podsilo.core.model.Feed
import net.drehtuer.podsilo.core.model.port.FeedRefreshMetadata
import net.drehtuer.podsilo.core.model.port.FeedRepository

class FakeFeedRepository(
    initial: List<Feed> = emptyList(),
) : FeedRepository {
    private val state = MutableStateFlow(initial)

    val current: List<Feed> get() = state.value

    override fun observeAll(): Flow<List<Feed>> = state

    override suspend fun getAll(): List<Feed> = state.value

    override suspend fun get(url: String): Feed? = state.value.firstOrNull { it.url == url }

    override suspend fun replaceAll(feeds: List<Feed>) {
        state.value = feeds
    }

    override suspend fun updateRefreshMetadata(
        feedUrl: String,
        metadata: FeedRefreshMetadata,
    ) {
        state.value =
            state.value.map { feed ->
                if (feed.url != feedUrl) {
                    feed
                } else {
                    feed.copy(
                        title = metadata.title,
                        imageUrl = metadata.imageUrl,
                        httpEtag = metadata.httpEtag,
                        httpLastModified = metadata.httpLastModified,
                        lastRefreshedAt = metadata.refreshedAt,
                    )
                }
            }
    }
}
