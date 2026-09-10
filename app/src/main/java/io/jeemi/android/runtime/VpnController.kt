package io.jeemi.android.runtime

import android.content.Context
import android.content.Intent
import androidx.core.content.ContextCompat
import io.jeemi.android.data.readBounded
import io.jeemi.android.domain.RuntimeState
import io.jeemi.android.domain.transitioning
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.Proxy
import java.net.URL
import java.net.URLEncoder
import java.security.MessageDigest

data class VpnRequest(val profileId: String, val yaml: String, val ipv6: Boolean, val revision: String = configurationRevision(yaml), val logLevel: String = "silent",
    val selections: Map<String, String> = emptyMap(), val defaults: Map<String, String> = emptyMap())
data class LiveProxy(val type: String, val now: String, val members: List<String>, val delay: Int?, val manualDelay: Int? = null)
data class LiveSession(val profileId: String, val revision: String, val geoRevision: String,
    val proxies: Map<String, LiveProxy> = emptyMap(), val sessionId: String = java.util.UUID.randomUUID().toString())

fun configurationRevision(yaml: String): String = MessageDigest.getInstance("SHA-256")
    .digest(yaml.toByteArray()).joinToString("") { "%02x".format(it) }

class VpnController(private val context: Context) {
    val state = MutableStateFlow<RuntimeState>(RuntimeState.Stopped)
    val live = MutableStateFlow<LiveSession?>(null)
    @Volatile internal var request: VpnRequest? = null
    @Volatile internal var api: CoreApi? = null
    @Volatile internal var dnsSession: JSONObject? = null
    fun start(next: VpnRequest) {
        if (state.value.transitioning || state.value is RuntimeState.Running) return
        request = next
        state.value = RuntimeState.Starting
        try { ContextCompat.startForegroundService(context, Intent(context, JeemiVpnService::class.java).setAction("start")) }
        catch (error: Exception) { request = null; state.value = RuntimeState.Failed("start_failed"); throw error }
    }
    fun restart(next: VpnRequest) {
        if (state.value.transitioning) return
        if (state.value !is RuntimeState.Running) { start(next); return }
        val previous = request
        val previousState = state.value
        request = next
        state.value = RuntimeState.Restarting
        try { context.startService(Intent(context, JeemiVpnService::class.java).setAction("restart")) }
        catch (error: Exception) { request = previous; state.value = previousState; throw error }
    }
    fun stop() {
        if (state.value is RuntimeState.Failed || state.value == RuntimeState.Stopped) { clear(false); return }
        if (state.value == RuntimeState.Stopping) return
        val previous = request
        val previousState = state.value
        request = null
        state.value = RuntimeState.Stopping
        try { context.startService(Intent(context, JeemiVpnService::class.java).setAction("stop")) }
        catch (error: Exception) { request = previous; state.value = previousState; throw error }
    }
    internal fun clearSession() { api = null; dnsSession = null; live.value = null }
    internal fun clear(failed: Boolean) {
        request = null; clearSession()
        state.value = if (failed) RuntimeState.Failed("core_failed") else RuntimeState.Stopped
    }
    fun matches(profile: String?, revision: String?, geo: String): Boolean =
        state.value is RuntimeState.Running && live.value?.let { it.profileId == profile && it.revision == revision && it.geoRevision == geo } == true
    fun select(profile: String, revision: String, geo: String, group: String, node: String, reset: String) {
        require(matches(profile, revision, geo))
        val client = requireNotNull(api)
        val previous = requireNotNull(live.value?.proxies?.get(group))
        require(previous.type == "Selector" && node in previous.members)
        client.call("/proxies/" + encode(group), "PUT", JSONObject().put("name", node))
        val verified = client.call("/proxies/" + encode(group))
        check(api === client && matches(profile, revision, geo) && verified.optString("now") == node)
        if (reset == "all") client.call("/connections", "DELETE")
        else if (reset == "selector") {
            val connections = client.call("/connections").optJSONArray("connections")
            if (connections != null) repeat(connections.length()) {
                val connection = connections.getJSONObject(it)
                val chains = connection.optJSONArray("chains")
                if (chains != null && (0 until chains.length()).any { index -> chains.getString(index) == group })
                    client.call("/connections/" + encode(connection.getString("id")), "DELETE")
            }
        }
        refresh(client)
    }
    fun refreshProvider(profile: String, revision: String, geo: String, name: String) {
        require(matches(profile, revision, geo))
        requireNotNull(api).call("/providers/rules/" + encode(name), "PUT")
    }
    fun test(profile: String, revision: String, geo: String, node: String): Int? {
        require(matches(profile, revision, geo))
        val client = requireNotNull(api)
        val value = runCatching { client.call("/proxies/" + encode(node) + "/delay?timeout=5000&url=" +
            encode("http://www.google.com/generate_204"), timeout = 7000).getInt("delay") }.getOrNull()
        if (matches(profile, revision, geo) && api === client) {
            live.update { current ->
                val proxy = current?.proxies?.get(node)
                if (current != null && proxy != null) current.copy(proxies = current.proxies + (node to proxy.copy(delay = value ?: -1, manualDelay = value ?: -1))) else current
            }
        }
        return value
    }
    internal fun refresh(client: CoreApi) {
        val entries = client.call("/proxies").getJSONObject("proxies")
        val previous = live.value ?: return
        live.update { current ->
            if (api !== client || current == null || current.revision != previous.revision) current
            else current.copy(proxies = entries.keys().asSequence().filterNot { it.startsWith("__jeemi_dns_") }.associateWith { name ->
                val value = entries.getJSONObject(name)
                val all = value.optJSONArray("all")
                val history = value.optJSONArray("history")
                val delay = history?.takeIf { it.length() > 0 }?.getJSONObject(history.length() - 1)?.optInt("delay")?.takeIf { it > 0 }
                val old = current.proxies[name]
                // Polling must not replace a manual failure with stale successful core history.
                LiveProxy(value.optString("type"), value.optString("now"),
                    all?.let { List(it.length()) { index -> it.getString(index) }.filterNot { it.startsWith("__jeemi_dns_") } } ?: emptyList(),
                    old?.manualDelay ?: delay ?: old?.delay, old?.manualDelay)
            })
        }
    }
    internal fun restoreSelections(client: CoreApi, request: VpnRequest) {
        val groups = client.call("/proxies").getJSONObject("proxies")
        groups.keys().asSequence().filterNot { it.startsWith("__jeemi_dns_") }.forEach { name ->
            val group = groups.getJSONObject(name)
            if (group.optString("type") != "Selector") return@forEach
            val all = group.optJSONArray("all") ?: return@forEach
            val members = List(all.length()) { all.getString(it) }
            val chosen = preferredSelection(members, request.selections[name], request.defaults[name]) ?: return@forEach
            client.call("/proxies/" + encode(name), "PUT", JSONObject().put("name", chosen))
            check(client.call("/proxies/" + encode(name)).optString("now") == chosen)
        }
    }
}

internal fun preferredSelection(members: List<String>, saved: String?, default: String?): String? =
    saved?.takeIf { it in members } ?: default?.takeIf { it in members } ?: members.firstOrNull()

internal class CoreApi(private val port: Int, private val secret: String) {
    internal fun open(path: String, timeout: Int): HttpURLConnection =
        (URL("http://127.0.0.1:" + port + path).openConnection(Proxy.NO_PROXY) as HttpURLConnection).apply {
            connectTimeout = 3000; readTimeout = timeout; instanceFollowRedirects = false
            setRequestProperty("Authorization", "Bearer " + secret)
        }
    fun call(path: String, method: String = "GET", body: JSONObject? = null, timeout: Int = 2000): JSONObject {
        val connection = URL("http://127.0.0.1:" + port + path).openConnection(Proxy.NO_PROXY) as HttpURLConnection
        try {
            connection.connectTimeout = timeout; connection.readTimeout = timeout
            connection.requestMethod = method
            connection.instanceFollowRedirects = false
            connection.setRequestProperty("Authorization", "Bearer " + secret)
            if (body != null) {
                connection.doOutput = true; connection.setRequestProperty("Content-Type", "application/json")
                connection.outputStream.use { it.write(body.toString().toByteArray()) }
            }
            require(connection.responseCode in 200..299)
            if (connection.responseCode == 204) return JSONObject()
            val text = connection.inputStream.use { it.readBounded(4 * 1024 * 1024).toString(Charsets.UTF_8) }
            return if (text.isBlank()) JSONObject() else JSONObject(text)
        } finally { connection.disconnect() }
    }
}
internal fun encode(value: String): String = URLEncoder.encode(value, "UTF-8").replace("+", "%20")
