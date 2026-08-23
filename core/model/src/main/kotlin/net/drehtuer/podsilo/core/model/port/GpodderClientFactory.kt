// SPDX-License-Identifier: GPL-3.0-or-later

package net.drehtuer.podsilo.core.model.port

/**
 * Builds a [GpodderClient] for the credentials in force *right now*.
 *
 * A client cannot be a singleton: its server URL and Basic-auth header are fixed at construction,
 * the user can change both in settings, and the decrypted app password is deliberately short-lived
 * (see [SettingsRepository]). So `SyncWorker` builds one per pass, through this port rather than
 * against the Retrofit implementation directly — which is also what lets the worker be tested with
 * a fake client and no HTTP at all (`docs/architecture.adoc` §2).
 */
fun interface GpodderClientFactory {
    fun create(credentials: NextcloudCredentials): GpodderClient
}
