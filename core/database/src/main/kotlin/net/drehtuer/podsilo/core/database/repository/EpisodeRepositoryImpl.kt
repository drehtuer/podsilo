// SPDX-License-Identifier: GPL-3.0-or-later

package net.drehtuer.podsilo.core.database.repository

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import net.drehtuer.podsilo.core.database.dao.EpisodeDao
import net.drehtuer.podsilo.core.database.toDomain
import net.drehtuer.podsilo.core.database.toEntity
import net.drehtuer.podsilo.core.model.Episode
import net.drehtuer.podsilo.core.model.port.EpisodeRepository

/** Room-backed [EpisodeRepository]. Bound to the port via Hilt `@Binds` in `:app` (Tier 4c). */
class EpisodeRepositoryImpl(
    private val episodeDao: EpisodeDao,
) : EpisodeRepository {
    override fun observeForFeed(feedUrl: String): Flow<List<Episode>> =
        episodeDao.observeForFeed(feedUrl).map { rows -> rows.map { it.toDomain() } }

    override suspend fun get(episodeKey: String): Episode? = episodeDao.get(episodeKey)?.toDomain()

    override suspend fun replaceForFeed(
        feedUrl: String,
        episodes: List<Episode>,
    ) {
        episodeDao.replaceForFeed(feedUrl, episodes.map { it.toEntity() })
    }

    override suspend fun deleteForFeed(feedUrl: String) {
        episodeDao.deleteForFeed(feedUrl)
    }
}
