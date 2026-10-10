// Author: Bluevava
// Open-source repository: https://github.com/bluevava/jeemi-android

package io.jeemi.android.runtime

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.net.VpnService
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.ParcelFileDescriptor
import androidx.core.app.NotificationCompat
import io.jeemi.android.JeemiApplication
import io.jeemi.android.MainActivity
import io.jeemi.android.R
import io.jeemi.android.data.BundledCore
import io.jeemi.android.domain.RuntimeState
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import mobile.CoreProcess
import mobile.Mobile
import java.io.File
import java.net.InetAddress
import java.net.ServerSocket
import java.util.UUID

class JeemiVpnService : VpnService() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var session: Job? = null
    private var command: Job? = null
    private var commandRevision = 0
    private var shuttingDown = false
    private var destroyed = false
    private var failureOnClose: String? = null
    private val mainHandler = Handler(Looper.getMainLooper())
    private val statusChecks = Channel<Unit>(Channel.CONFLATED)
    private val app get() = application as JeemiApplication
    private val controller get() = app.runtime
    override fun onCreate() {
        super.onCreate()
        controller.service = this
    }
    internal fun requestStatusCheck() { statusChecks.trySend(Unit) }
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val stopping = intent?.action == "stop"
        if (shuttingDown || (!stopping && intent?.action != "restart" && session?.isActive == true)) return START_NOT_STICKY
        val request = if (stopping) null else controller.request
        val revision = ++commandRevision
        if (stopping) { controller.request = null; controller.state.value = RuntimeState.Stopping }
        command?.cancel()
        command = scope.launch {
            // A restart stays in this foreground service. Join includes the old
            // non-cancellable process/FD/file cleanup before creating another TUN.
            session?.cancelAndJoin()
            session = null
            ensureActive()
            if (stopping || request == null || prepare(this@JeemiVpnService) != null) {
                closeService(startId, if (!stopping && request != null) "vpn_permission_denied" else null)
                return@launch
            }
            try { foreground() } catch (_: Exception) {
                closeService(startId, "foreground_failed")
                return@launch
            }
            session = scope.launch { runSession(request, revision, startId) }
        }
        return START_NOT_STICKY
    }
    private fun foreground(running: Boolean = false) {
        val manager = getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(NotificationChannel("vpn", getString(R.string.status_control), NotificationManager.IMPORTANCE_LOW))
        val open = PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val stop = PendingIntent.getService(this, 1, Intent(this, JeemiVpnService::class.java).setAction("stop"),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val notification = NotificationCompat.Builder(this, "vpn").setSmallIcon(R.drawable.ic_vpn)
            .setContentTitle(getString(R.string.app_name))
            .setContentText(getString(if (running) R.string.runtime_running else R.string.runtime_starting))
            .setContentIntent(open).setOngoing(true).setShowWhen(false)
            .addAction(0, getString(R.string.vpn_stop), stop).build()
        if (Build.VERSION.SDK_INT >= 34) startForeground(1, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_SYSTEM_EXEMPTED)
        else startForeground(1, notification)
    }
    private suspend fun runSession(request: VpnRequest, revision: Int, startId: Int) {
        var tun: ParcelFileDescriptor? = null
        var core: CoreProcess? = null
        var failed = false
        var failureCode = "core_failed"
        val root = File(noBackupFilesDir, "sessions").apply { mkdirs() }
        val home = File(root, UUID.randomUUID().toString()).apply { mkdirs() }
        val cache = Mobile.newProviderCacheSession(home.absolutePath, noBackupFilesDir.absolutePath)
        val connectivity = getSystemService(ConnectivityManager::class.java)
        val networks = java.util.concurrent.CopyOnWriteArrayList<Network>()
        val callback = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) { networks.addIfAbsent(network); setUnderlyingNetworks(networks.toTypedArray()) }
            override fun onLost(network: Network) { networks.remove(network); setUnderlyingNetworks(networks.toTypedArray()) }
        }
        var registered = false
        try {
            withContext(Dispatchers.IO) {
                val geoRevision = app.geodata.materialize(home)
                val port = ServerSocket(0, 1, InetAddress.getByName("127.0.0.1")).use { it.localPort }
                val secret = UUID.randomUUID().toString() + UUID.randomUUID()
                val choices = org.json.JSONObject(request.defaults + request.selections).toString()
                val primed = Mobile.primeStartupSelections(request.yaml, choices)
                if (request.externalUIVersion.isNotEmpty())
                    Mobile.materializeDashboard(noBackupFilesDir.absolutePath, request.externalUIVersion, home.absolutePath)
                val androidConfig = Mobile.androidSessionConfiguration(primed, secret, port.toLong(), request.ipv6, request.tunStack.configValue)
                val config = Mobile.androidDashboardConfiguration(androidConfig, request.externalUIVersion)
                val diagnostics = org.json.JSONObject(Mobile.prepareDNSDiagnostics(config, secret))
                File(home, "config.yaml").writeText(diagnostics.getString("configuration"))
                cache.prepare()
                ensureActive()
                val routes = org.json.JSONObject(Mobile.androidVpnRoutes(primed, request.ipv6, Build.VERSION.SDK_INT < 33))
                val address = routes.getString("address").split('/')
                val builder = Builder().setSession(getString(R.string.app_name)).setMtu(1500)
                    .addAddress(address[0], address[1].toInt()).addDnsServer("172.19.0.2")
                    .setBlocking(false)
                // Keep the TCP forwarder inside the VPN; only outbound sockets
                // are excluded through VpnService.protect on received FD copies.
                val needsSocketProtection = request.tunStack in listOf(io.jeemi.android.domain.TunStack.SYSTEM, io.jeemi.android.domain.TunStack.MIXED)
                if (!needsSocketProtection) builder.addDisallowedApplication(packageName)
                if (request.ipv6) builder.addAddress("fdfe:dcba:9876::1", 126)
                val included = routes.getJSONArray("include")
                val excluded = routes.getJSONArray("exclude")
                // The final host route keeps the service resolver reachable even
                // when its containing subnet is explicitly excluded.
                fun addRoute(index: Int) {
                    val parts = included.getString(index).split('/')
                    builder.addRoute(parts[0], parts[1].toInt())
                }
                repeat(included.length() - 1) { addRoute(it) }
                if (Build.VERSION.SDK_INT >= 33) repeat(excluded.length()) {
                    val parts = excluded.getString(it).split('/')
                    builder.excludeRoute(android.net.IpPrefix(InetAddress.getByName(parts[0]), parts[1].toInt()))
                }
                addRoute(included.length() - 1)
                if (Build.VERSION.SDK_INT >= 29) builder.setMetered(false)
                tun = requireNotNull(builder.establish())
                val fd = ParcelFileDescriptor.dup(requireNotNull(tun).fileDescriptor).detachFd()
                core = if (needsSocketProtection) Mobile.startCoreProtected(BundledCore(this@JeemiVpnService).executable.absolutePath, home.absolutePath,
                    fd.toLong(), AndroidConnectionOwner(this@JeemiVpnService), object : mobile.SocketProtector {
                        override fun protect(descriptor: Long): Boolean = controller.request === request &&
                            !shuttingDown && !destroyed && this@JeemiVpnService.protect(descriptor.toInt())
                    })
                else Mobile.startCoreWithOwner(BundledCore(this@JeemiVpnService).executable.absolutePath, home.absolutePath,
                    fd.toLong(), AndroidConnectionOwner(this@JeemiVpnService))
                val child = requireNotNull(core)
                val client = CoreApi(port, secret)
                withTimeout(30_000) {
                    while (true) {
                        ensureActive(); require(child.isRunning())
                        if (!ownedTunnelActive(requireNotNull(tun))) throw TunnelDeactivated()
                        val ready = runCatching { coreTunnelEnabled(client.call("/configs")) }.getOrDefault(false)
                        if (ready) break
                        delay(200)
                    }
                }
                controller.api = client
                controller.restoreSelections(client, request)
                controller.dnsSession = diagnostics.getJSONObject("session")
                client.call("/configs", "PATCH", org.json.JSONObject().put("log-level", request.logLevel))
                withContext(Dispatchers.Main.immediate) {
                    ensureActive()
                    if (controller.request !== request) throw CancellationException()
                    controller.live.value = LiveSession(request.profileId, request.revision, geoRevision)
                }
                controller.refresh(client)
                if (!ownedTunnelActive(requireNotNull(tun)) || !coreTunnelEnabled(client.call("/configs")))
                    throw TunnelDeactivated()
                withContext(Dispatchers.Main.immediate) {
                    ensureActive()
                    if (controller.request !== request) throw CancellationException()
                    controller.state.value = RuntimeState.Running(request.revision)
                    foreground(running = true)
                }
                connectivity.registerNetworkCallback(NetworkRequest.Builder().addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
                    .addCapability(NetworkCapabilities.NET_CAPABILITY_NOT_VPN).build(), callback)
                registered = true
                coroutineScope {
                    // Keep liveness independent of potentially slow proxy/provider
                    // snapshots, and wake immediately when the Activity resumes.
                    launch {
                        while (isActive) {
                            ensureActive()
                            require(child.isRunning())
                            if (!ownedTunnelActive(requireNotNull(tun)) ||
                                !coreTunnelEnabled(client.call("/configs", timeout = 1500))) throw TunnelDeactivated()
                            withTimeoutOrNull(1500) { statusChecks.receive() }
                        }
                    }
                    while (isActive) {
                        delay(1500)
                        controller.refresh(client)
                    }
                }
            }
        } catch (_: TunnelDeactivated) {
            // System revocation / an external TUN switch is a clean stop.
        } catch (_: TimeoutCancellationException) { failed = true; failureCode = core?.tunnelError()?.ifBlank { "tunnel_not_ready" } ?: "core_timeout"
        } catch (_: CancellationException) {
            // Stop/revoke closes both descriptor owners below.
        } catch (_: Exception) { failed = true
        } finally {
            // Keep state/foreground cleanup inside the non-cancellable scope as
            // well: returning from IO to an already cancelled job can throw.
            withContext(NonCancellable) {
                // Remove Running before potentially slow process/cache cleanup.
                // A queued restart keeps ownership of its transition instead.
                if (controller.service === this@JeemiVpnService && revision == commandRevision && controller.request === request)
                    controller.state.value = RuntimeState.Stopping
                withContext(Dispatchers.IO) {
                    if (registered) runCatching { connectivity.unregisterNetworkCallback(callback) }
                    runCatching { core?.stop() }
                    runCatching { tun?.close() }
                    runCatching { cache.save() }
                    check(home.canonicalFile.parentFile == root.canonicalFile)
                    home.deleteRecursively()
                }
                controller.clearSession()
                // A queued restart owns the next request and state, even before
                // its Intent reaches onStartCommand. Never erase it here.
                if (shuttingDown) { if (destroyed) publishClosed() }
                else if (revision == commandRevision && controller.request === request)
                    closeService(startId, if (failed) failureCode else null)
            }
        }
    }
    private fun closeService(startId: Int, failure: String? = null) {
        failureOnClose = failure
        controller.request = null
        controller.state.value = RuntimeState.Stopping
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelfResult(startId)
    }
    private fun publishClosed() {
        if (controller.service !== this) return
        controller.service = null
        controller.clear(failureOnClose != null)
        failureOnClose?.let { controller.state.value = RuntimeState.Failed(it) }
    }
    private fun cancelAll() {
        shuttingDown = true
        controller.request = null
        command?.cancel()
        if (session == null || session?.isCompleted == true) { if (destroyed) publishClosed() }
        else { controller.state.value = RuntimeState.Stopping; session?.cancel() }
    }
    override fun onRevoke() {
        // Android may deliver this Binder callback off the main thread. Serialize
        // it with start/restart/destroy before touching jobs or publishing state.
        mainHandler.post {
            if (!destroyed) {
                failureOnClose = null
                cancelAll()
                stopSelf()
            }
        }
    }
    override fun onDestroy() {
        destroyed = true
        cancelAll()
        scope.cancel()
        statusChecks.close()
        super.onDestroy()
    }
}
