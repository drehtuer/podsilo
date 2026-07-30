// SPDX-License-Identifier: GPL-3.0-or-later

package net.drehtuer.podsilo.core.sync

import net.drehtuer.podsilo.core.model.port.EpisodeAction
import net.drehtuer.podsilo.core.model.port.EpisodeActionPage
import net.drehtuer.podsilo.core.model.port.GpodderClient
import net.drehtuer.podsilo.core.model.port.SubscriptionDelta

private val EMPTY_SUBSCRIPTIONS = SubscriptionDelta(add = emptyList(), remove = emptyList(), timestamp = 0L)

class FakeGpodderClient(
    private val subscriptions: SubscriptionDelta = EMPTY_SUBSCRIPTIONS,
    private val subscriptionsFailure: Throwable? = null,
    private val episodeActionsPage: EpisodeActionPage = EpisodeActionPage(actions = emptyList(), timestamp = 0L),
    private val episodeActionsFailure: Throwable? = null,
    private val postResult: Result<Unit> = Result.success(Unit),
) : GpodderClient {
    var postedActions: List<EpisodeAction> = emptyList()
        private set

    var fetchEpisodeActionsSinceValues: MutableList<Long> = mutableListOf()
        private set

    var postEpisodeActionsCallCount: Int = 0
        private set

    override suspend fun fetchSubscriptions(since: Long?): SubscriptionDelta {
        subscriptionsFailure?.let { throw it }
        return subscriptions
    }

    override suspend fun postEpisodeActions(actions: List<EpisodeAction>): Result<Unit> {
        postEpisodeActionsCallCount++
        postedActions = actions
        return postResult
    }

    override suspend fun fetchEpisodeActions(since: Long): EpisodeActionPage {
        fetchEpisodeActionsSinceValues += since
        episodeActionsFailure?.let { throw it }
        return episodeActionsPage
    }
}
