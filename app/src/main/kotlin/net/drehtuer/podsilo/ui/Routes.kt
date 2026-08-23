// SPDX-License-Identifier: GPL-3.0-or-later

package net.drehtuer.podsilo.ui

import android.net.Uri

/**
 * The routes that exist, as `docs/UI.adoc` §B9 specifies: one `NavHost`,
 * [PODCASTS] the start destination and the only screen at the bottom of the backstack.
 *
 * Arguments are URL-encoded because both are themselves URLs or GUIDs — a feed URL contains `/`
 * and `:`, which the route parser would otherwise read as separators.
 */
internal object Routes {
    const val PODCASTS = "podcasts"
    const val EPISODES = "episodes/{feedUrl}"
    const val EPISODE_DETAIL = "episode/{episodeKey}"

    const val SETTINGS = "settings"
    const val CONNECT = "connect"
    const val NAMING = "naming"
    const val ACTIVITY = "activity"
    const val ERROR_LOG = "errorlog"

    const val ARG_FEED_URL = "feedUrl"
    const val ARG_EPISODE_KEY = "episodeKey"

    fun episodes(feedUrl: String) = "episodes/${Uri.encode(feedUrl)}"

    fun episodeDetail(episodeKey: String) = "episode/${Uri.encode(episodeKey)}"
}
