// SPDX-License-Identifier: GPL-3.0-or-later

package net.drehtuer.podsilo.core.sync

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import net.drehtuer.podsilo.core.model.Feed
import net.drehtuer.podsilo.core.model.port.FeedRepository

class FakeFeedRepository(
    initial: List<Feed> = emptyList(),
) : FeedRepository {
    private val state = MutableStateFlow(initial)

    val current: List<Feed> get() = state.value

    override fun observeAll(): Flow<List<Feed>> = state

    override suspend fun replaceAll(feeds: List<Feed>) {
        state.value = feeds
    }
}
