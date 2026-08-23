// SPDX-License-Identifier: GPL-3.0-or-later

package net.drehtuer.podsilo.system

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import androidx.core.content.getSystemService
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import net.drehtuer.podsilo.core.model.port.Connectivity
import net.drehtuer.podsilo.core.model.port.ConnectivityMonitor
import javax.inject.Inject
import javax.inject.Singleton

/**
 * [ConnectivityMonitor] over `ConnectivityManager` (`docs/UI.adoc` §12.10).
 *
 * Its whole purpose is to be consulted **before** a request is started, so a pull-to-refresh with
 * no network returns immediately instead of spinning against every feed in turn until each times
 * out. Being offline is a precondition, not a failure — nothing here writes to the error log.
 *
 * `NET_CAPABILITY_VALIDATED` rather than merely "connected": a captive portal is exactly the case
 * where the radio is up, the request will fail, and the user needs to be told something other than
 * "server did not respond".
 */
@Singleton
class AndroidConnectivityMonitor
    @Inject
    constructor(
        @ApplicationContext private val context: Context,
    ) : ConnectivityMonitor {
        override fun observe(): Flow<Connectivity> =
            callbackFlow {
                val manager = context.getSystemService<ConnectivityManager>()
                if (manager == null) {
                    // No ConnectivityManager at all is not a device we can reason about; claiming
                    // "offline" would block every download request, so assume online and let the
                    // request itself fail honestly.
                    trySend(Connectivity(online = true, metered = false))
                    awaitClose { }
                    return@callbackFlow
                }

                fun emitCurrent() {
                    val capabilities = manager.getNetworkCapabilities(manager.activeNetwork)
                    trySend(capabilities.toConnectivity())
                }

                val callback =
                    object : ConnectivityManager.NetworkCallback() {
                        override fun onAvailable(network: Network) = emitCurrent()

                        override fun onLost(network: Network) = emitCurrent()

                        override fun onCapabilitiesChanged(
                            network: Network,
                            capabilities: NetworkCapabilities,
                        ) = emitCurrent()
                    }

                manager.registerNetworkCallback(
                    NetworkRequest.Builder().addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET).build(),
                    callback,
                )
                emitCurrent()

                awaitClose { manager.unregisterNetworkCallback(callback) }
            }.distinctUntilChanged()
    }

private fun NetworkCapabilities?.toConnectivity(): Connectivity {
    if (this == null) return Connectivity(online = false, metered = false)
    val online =
        hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) &&
            hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
    val metered = !hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED)
    return Connectivity(online = online, metered = metered)
}
