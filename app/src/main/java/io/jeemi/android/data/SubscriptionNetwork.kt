package io.jeemi.android.data

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import io.jeemi.android.domain.RuntimeState
import io.jeemi.android.runtime.VpnController
import java.io.IOException
import java.net.HttpURLConnection
import java.net.Proxy

/** A one-request network route, resolved after failure; never binds the process. */
@Suppress("DEPRECATION") // A one-shot snapshot avoids a persistent network observer.
internal fun directSubscriptionRoute(context: Context, runtime: VpnController): SubscriptionRoute? {
    val session = runtime.live.value?.sessionId ?: return null
    fun current() = runtime.state.value is RuntimeState.Running && runtime.live.value?.sessionId == session && runtime.api != null
    if (!current()) return null
    val connectivity = context.getSystemService(ConnectivityManager::class.java) ?: return null
    fun usable(capabilities: NetworkCapabilities?) = capabilities != null &&
        capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) &&
        capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_VPN) &&
        !capabilities.hasTransport(NetworkCapabilities.TRANSPORT_VPN)
    return try {
        val active = connectivity.activeNetwork
        val network = connectivity.allNetworks.mapNotNull { network ->
            connectivity.getNetworkCapabilities(network)?.takeIf(::usable)?.let { network to it }
        }.sortedWith(compareByDescending<Pair<android.net.Network, NetworkCapabilities>> { it.first == active }
            .thenByDescending { it.second.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED) }
            .thenByDescending { it.second.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED) })
            .firstOrNull()?.first ?: return null
        if (!current()) return null
        val route: SubscriptionRoute = { url ->
            if (!current() || !usable(connectivity.getNetworkCapabilities(network))) throw IOException("direct_route_unavailable")
            // Network.openConnection binds both DNS and sockets to this physical
            // network. NO_PROXY alone would not bypass Android's VPN routing.
            network.openConnection(url, Proxy.NO_PROXY) as HttpURLConnection
        }
        route
    } catch (_: SecurityException) { null }
}
