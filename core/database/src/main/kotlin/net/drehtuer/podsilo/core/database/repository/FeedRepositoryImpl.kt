// SPDX-License-Identifier: GPL-3.0-or-later

package net.drehtuer.podsilo.core.database.repository

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import net.drehtuer.podsilo.core.database.dao.FeedDao
import net.drehtuer.podsilo.core.database.toDomain
import net.drehtuer.podsilo.core.database.toEntity
import net.drehtuer.podsilo.core.model.Feed
import net.drehtuer.podsilo.core.model.port.FeedRepository

/** Room-backed [FeedRepository]. Bound to the port via Hilt `@Binds` in `:app` (Tier 4c). */
class FeedRepositoryImpl(
    private val feedDao: FeedDao,
) : FeedRepository {
    override fun observeAll(): Flow<List<Feed>> = feedDao.observeAll().map { rows -> rows.map { it.toDomain() } }

    override suspend fun replaceAll(feeds: List<Feed>) {
        feedDao.replaceAll(feeds.map { it.toEntity() })
    }
}
