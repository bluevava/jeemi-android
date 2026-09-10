package io.jeemi.android.runtime

import io.jeemi.android.domain.RuntimeState
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.*
import mobile.DNSQuery
import mobile.Mobile
import org.json.JSONObject
import java.util.concurrent.atomic.AtomicLong

data class ConnectionRecord(val id: String, val host: String, val destinationIP: String, val destinationPort: String,
    val sourceIP: String, val sourcePort: String, val network: String, val inbound: String,
    val process: String, val uid: Long?, val rule: String, val rulePayload: String,
    val chains: List<String>, val upload: Long, val download: Long, val start: String, val ended: Boolean = false,
    val uploadRate: Double? = null, val downloadRate: Double? = null) {
    val target: String get() = host.ifBlank { destinationIP }
    val outbound: String get() = chains.firstOrNull().orEmpty()
    fun matches(query: String): Boolean = query.trim().let { text -> text.isBlank() ||
        target.contains(text, true) || destinationIP.contains(text, true) || sourceIP.contains(text, true) ||
        process.contains(text, true) || network.contains(text, true) || rule.contains(text, true) ||
        rulePayload.contains(text, true) || chains.any { it.contains(text, true) } }
}
data class ConnectionFeed(val sessionId: String = "", val rows: List<ConnectionRecord> = emptyList(),
    val status: String = "offline", val busy: Boolean = false, val error: Boolean = false,
    val activeCount: Int = rows.count { !it.ended }, val limitExceeded: Boolean = false)
data class CoreLog(val id: Long, val level: String, val message: String, val time: String)
data class LogFeed(val sessionId: String = "", val rows: List<CoreLog> = emptyList(), val status: String = "offline")
data class DnsFeed(val busy: Boolean = false, val result: String? = null, val error: String = "")

// Disconnect the outstanding HTTP read as soon as its page leaves composition
// or the Activity stops. Cancelling a coroutine alone cannot close this socket.
internal fun CoreApi.connectionSnapshot(previous: List<ConnectionRecord>): Flow<ConnectionSnapshot> = callbackFlow {
    val connection = open("/connections", 2000)
    val worker = launch(Dispatchers.IO) {
        try {
            require(connection.responseCode in 200..299)
            val context = currentCoroutineContext()
            val snapshot = connection.inputStream.use { readConnectionSnapshot(it, previous) { context.ensureActive() } }
            trySend(snapshot); close()
        } catch (cancelled: CancellationException) { throw cancelled
        } catch (error: Exception) { close(error)
        } finally { connection.disconnect() }
    }
    awaitClose { connection.disconnect(); worker.cancel() }
}

private val logIDs = AtomicLong()
private fun CoreApi.logs(level: String): Flow<CoreLog> = callbackFlow {
    val connection = open("/logs?level=" + encode(level), 25_000)
    val worker = launch(Dispatchers.IO) {
        try {
            require(connection.responseCode == 200)
            connection.inputStream.bufferedReader().use { reader ->
                val line = StringBuilder()
                var oversized = false
                while (isActive) {
                    val char = reader.read(); if (char < 0) break
                    if (char == 10) {
                        if (!oversized && line.isNotEmpty()) {
                            val json = JSONObject(line.toString())
                            val kind = json.optString("type")
                            if (kind in listOf("debug", "info", "warning", "error")) {
                                val message = redactLog(json.optString("payload").take(8192))
                                trySend(CoreLog(logIDs.incrementAndGet(), kind, message,
                                    java.time.LocalTime.now().withNano(0).toString()))
                            }
                        }
                        line.setLength(0); oversized = false
                    } else if (line.length < 65536) line.append(char.toChar()) else oversized = true
                }
            }
            close()
        } catch (cancelled: CancellationException) { throw cancelled
        } catch (error: Exception) { close(error)
        } finally { connection.disconnect() }
    }
    awaitClose { connection.disconnect(); worker.cancel() }
}.buffer(256, BufferOverflow.DROP_OLDEST)

internal fun redactLog(message: String): String = message
    .replace(Regex("(https?://)[^\\s/@]+:[^\\s/@]+@"), "$1***@")
    .replace(Regex("(?i)([?&](?:token|key|password|secret|auth)\\s*=)[^&\\s]+"), "$1***")

/** Page-owned observations. Runtime state always refers to the actual session,
 * even if the user has selected a different subscription for the next start. */
class Diagnostics(private val runtime: VpnController, private val scope: CoroutineScope, context: android.content.Context) {
    private val preferences = context.getSharedPreferences("dns-diagnostics", android.content.Context.MODE_PRIVATE)
    private val packages = context.packageManager
    internal val applicationIcons = io.jeemi.android.data.InstalledAppIcons(packages)
    private val appNames = io.jeemi.android.data.InstalledAppLabels(packages)
    suspend fun applicationNames(names: List<String>, locale: String): Map<String, String> = appNames.load(names, locale)
    fun dnsPreference(key: String, fallback: String): String = preferences.getString(key, fallback) ?: fallback
    val connections = MutableStateFlow(ConnectionFeed())
    val logs = MutableStateFlow(LogFeed())
    val dns = MutableStateFlow(DnsFeed())
    private var dnsJob: Job? = null
    private val connectionObservation = AtomicLong()
    private val connectionMutation = AtomicLong()
    @Volatile private var dnsHandle: DNSQuery? = null

    suspend fun observeConnections() = withContext(Dispatchers.IO) {
        val observation = connectionObservation.incrementAndGet()
        val client = runtime.api; val session = runtime.live.value
        if (client == null || session == null || runtime.state.value !is RuntimeState.Running) {
            connections.update { it.copy(status = "offline", rows = it.rows.map { row -> row.copy(uploadRate = null, downloadRate = null) }) }; return@withContext
        }
        if (connections.value.sessionId != session.sessionId) connections.value = ConnectionFeed(session.sessionId, status = "connecting")
        else connections.update { it.copy(status = "connecting", rows = it.rows.map { row -> row.copy(uploadRate = null, downloadRate = null) }) }
        fun current() = connectionObservation.get() == observation && runtime.api === client && runtime.live.value?.sessionId == session.sessionId
        var previous: List<ConnectionRecord>? = null
        var sampledAt: Long? = null
        var retry = 1000L
        try {
            while (currentCoroutineContext().isActive && current()) {
                try {
                    val mutation = connectionMutation.get()
                    val snapshot = withTimeoutOrNull(5000) { client.connectionSnapshot(connections.value.rows).first() }
                        ?: throw java.net.SocketTimeoutException()
                    val now = System.nanoTime()
                    val rows = connectionRates(previous, snapshot.rows, sampledAt?.let { now - it })
                    ensureActive()
                    if (current()) connections.update {
                        if (connectionMutation.get() != mutation) it else it.copy(
                            rows = mergeConnections(it.rows, rows, snapshot.presentTrackedIds),
                            activeCount = snapshot.activeCount, status = "live", error = false, limitExceeded = false)
                    }
                    previous = rows; sampledAt = now; retry = 1000
                } catch (cancelled: CancellationException) { throw cancelled
                } catch (error: Exception) {
                    previous = null; sampledAt = null
                    retry = (retry * 2).coerceAtMost(8000)
                    if (current()) connections.update { it.copy(status = "stale", error = true, limitExceeded = error is ConnectionSnapshotLimit,
                        rows = it.rows.map { row -> row.copy(uploadRate = null, downloadRate = null) }) }
                }
                delay(retry)
            }
        } finally {
            if (current()) connections.update { it.copy(status = "stale", rows = it.rows.map { row -> row.copy(uploadRate = null, downloadRate = null) }) }
        }
    }

    fun closeConnections(ids: List<String>, sessionId: String) {
        if (connections.value.busy || ids.isEmpty()) return
        connections.update { it.copy(busy = true, error = false) }
        scope.launch {
            try { withContext(Dispatchers.IO) {
                val client = requireNotNull(runtime.api)
                require(runtime.state.value is RuntimeState.Running && runtime.live.value?.sessionId == sessionId)
                val closed = ids.toHashSet()
                closed.forEach { id ->
                    ensureActive(); check(runtime.api === client && runtime.live.value?.sessionId == sessionId)
                    client.call("/connections/" + encode(id), "DELETE")
                }
                // The page owns snapshot reads. A confirmed close may finish
                // after navigation; it must not restart observation off-page.
                if (runtime.api === client) {
                    connectionMutation.incrementAndGet()
                    connections.update {
                        if (it.sessionId == sessionId) it.copy(rows = markClosedConnections(it.rows, closed),
                            activeCount = (it.activeCount - it.rows.count { row -> !row.ended && row.id in closed }).coerceAtLeast(0)) else it
                    }
                }
            } } catch (cancelled: CancellationException) { throw cancelled
            } catch (_: Exception) { connections.update { if (it.sessionId == sessionId) it.copy(error = true) else it }
            } finally { connections.update { if (it.sessionId == sessionId) it.copy(busy = false) else it } }
        }
    }
    fun clearEndedConnections() {
        connectionMutation.incrementAndGet()
        connections.update { it.copy(rows = it.rows.filterNot(ConnectionRecord::ended)) }
    }

    suspend fun observeLogs(level: String) = withContext(Dispatchers.IO) {
        val client = runtime.api; val session = runtime.live.value
        if (client == null || session == null || runtime.state.value !is RuntimeState.Running || level == "silent") {
            logs.update { it.copy(status = "offline") }; return@withContext
        }
        if (logs.value.sessionId != session.sessionId) logs.value = LogFeed(session.sessionId)
        var retry = 1000L
        try {
            while (currentCoroutineContext().isActive && runtime.api === client) {
                logs.update { it.copy(status = "live") }
                try {
                    client.logs(level).collect { row ->
                        if (runtime.api === client) logs.update { it.copy(rows = (it.rows + row).takeLast(500), status = "live") }
                        retry = 1000
                    }
                } catch (cancelled: CancellationException) { throw cancelled
                } catch (_: Exception) { logs.update { it.copy(status = "stale") } }
                delay(retry); retry = (retry * 2).coerceAtMost(8000)
            }
        } finally { logs.update { it.copy(status = "paused") } }
    }
    fun clearLogs() { logs.update { it.copy(rows = emptyList()) } }
    fun applyLogLevel(level: String) {
        if (level !in listOf("silent", "error", "warning", "info", "debug")) return
        scope.launch(Dispatchers.IO) {
            val client = runtime.api ?: return@launch
            runCatching { client.call("/configs", "PATCH", JSONObject().put("log-level", level)) }
                .onFailure { logs.update { it.copy(status = "stale") } }
        }
        if (level == "silent") clearLogs()
    }

    fun queryDns(domain: String, proxy: String, direct: String, custom: String, customProxy: Boolean) {
        if (dnsJob?.isActive == true) return
        dns.value = DnsFeed(busy = true)
        preferences.edit().putString("proxy", proxy.take(2048)).putString("direct", direct.take(2048))
            .putString("custom", custom.take(2048)).putString("customProxy", customProxy.toString()).apply()
        val deadline = android.os.SystemClock.elapsedRealtime() + 12_000
        val captured = runtime.api
        val session = runtime.live.value?.sessionId
        dnsJob = scope.launch {
            try {
                val result = withContext(Dispatchers.IO) {
                    val request = JSONObject().put("domain", domain).put("proxyDNS", proxy).put("directDNS", direct)
                        .put("customDNS", custom).put("customProxy", customProxy)
                    val input = JSONObject().put("request", request)
                    if (captured != null && runtime.state.value is RuntimeState.Running) {
                        val options = requireNotNull(runtime.dnsSession)
                        input.put("session", options)
                        val leaf = runCatching { dnsProxyLeaf(captured, options, deadline) }.getOrNull()
                        input.put("proxyNode", leaf.orEmpty()).put("proxyError", if (leaf == null) "proxy_invalid" else "")
                    } else check(runtime.state.value == RuntimeState.Stopped || runtime.state.value is RuntimeState.Failed)
                    ensureActive()
                    val remaining = deadline - android.os.SystemClock.elapsedRealtime()
                    check(remaining > 0)
                    input.put("timeoutMS", remaining)
                    val handle = Mobile.newDNSQuery(input.toString())
                    dnsHandle = handle
                    try { ensureActive(); handle.run() } finally { handle.cancel(); dnsHandle = null }
                }
                ensureActive()
                check(runtime.api === captured && runtime.live.value?.sessionId == session)
                dns.value = DnsFeed(result = result)
            } catch (cancelled: CancellationException) { dns.value = dns.value.copy(busy = false, error = "cancelled"); throw cancelled
            } catch (error: Exception) { dns.value = DnsFeed(error = if (error.message?.contains("invalid_domain") == true) "invalid_domain" else "query_failed") }
        }
    }
    fun cancelDns() { dnsHandle?.cancel(); dnsJob?.cancel() }

    private suspend fun dnsProxyLeaf(client: CoreApi, session: JSONObject, deadline: Long): String {
        val context = currentCoroutineContext()
        fun call(path: String, method: String = "GET", body: JSONObject? = null): JSONObject {
            context.ensureActive()
            val remaining = deadline - android.os.SystemClock.elapsedRealtime()
            check(remaining > 0)
            return client.call(path, method, body, minOf(remaining, 1500).toInt())
        }
        val proxies = call("/proxies").getJSONObject("proxies")
        val providers = session.optJSONArray("ProviderNames")
        val providerNames = mutableSetOf<String>()
        if (providers != null) repeat(providers.length()) { index ->
            val rows = call("/providers/proxies/" + encode(providers.getString(index))).getJSONArray("proxies")
            repeat(rows.length()) { i ->
                val item = rows.getJSONObject(i); val name = item.getString("name")
                check(providerNames.add(name))
                // Duplicate leaf names across provider sources are ambiguous.
                check(!proxies.has(name))
                proxies.put(name, item)
            }
        }
        var name = session.getString("MatchTarget")
        val visited = mutableSetOf<String>()
        repeat(32) {
            check(name.isNotBlank() && visited.add(name))
            val node = proxies.getJSONObject(name)
            val all = node.optJSONArray("all")
            if (all == null) {
                check(node.optString("type").lowercase() !in listOf("direct", "reject", "rejectdrop", "pass", "compatible"))
                val group = proxies.getJSONObject("__jeemi_dns_egress").getJSONArray("all")
                check((0 until group.length()).any { group.getString(it) == name })
                call("/proxies/__jeemi_dns_egress", "PUT", JSONObject().put("name", name))
                check(call("/proxies/__jeemi_dns_egress").optString("now") == name)
                return name
            }
            name = node.optString("now")
            check((0 until all.length()).any { all.getString(it) == name })
        }
        error("proxy_invalid")
    }
}
