// SPDX-License-Identifier: GPL-3.0-or-later

package net.drehtuer.podsilo.core.gpodder

import net.drehtuer.podsilo.core.model.port.GpodderClient
import net.drehtuer.podsilo.core.model.port.GpodderClientFactory
import net.drehtuer.podsilo.core.model.port.NextcloudCredentials
import okhttp3.OkHttpClient

/**
 * Retrofit-backed [GpodderClientFactory]. The [okHttpClient] is shared across every client it
 * builds, so a per-pass client costs a couple of objects rather than a fresh connection and thread
 * pool.
 */
class RetrofitGpodderClientFactory(
    private val okHttpClient: OkHttpClient = OkHttpClient(),
) : GpodderClientFactory {
    override fun create(credentials: NextcloudCredentials): GpodderClient =
        RetrofitGpodderClient.create(
            baseUrl = credentials.serverUrl,
            credentials = GpodderCredentials(credentials.username, credentials.appPassword),
            okHttpClient = okHttpClient,
        )
}
