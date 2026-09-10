package io.jeemi.android

import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.semantics.SemanticsActions
import androidx.lifecycle.ViewModelProvider
import io.jeemi.android.data.chainState
import io.jeemi.android.domain.*
import io.jeemi.android.runtime.*
import io.jeemi.android.ui.JeemiViewModel
import io.jeemi.android.ui.geoRevision
import org.json.JSONArray
import org.json.JSONObject
import org.junit.*
import org.junit.Assert.*
import java.io.DataInputStream
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.URLDecoder
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.concurrent.thread

private const val SEARCH_SOURCE = """proxies:
  - {name: HK GM 01, type: socks5, server: 192.0.2.1, port: 1080}
  - {name: JP gm 02, type: socks5, server: 192.0.2.2, port: 1080}
  - {name: HK GM EV 03, type: socks5, server: 192.0.2.3, port: 1080}
  - {name: US GM 04, type: socks5, server: 192.0.2.4, port: 1080}
proxy-groups:
  - {name: Main, type: select, proxies: [HK GM 01, JP gm 02, HK GM EV 03, US GM 04, HK GM nested, DIRECT]}
  - {name: HK GM nested, type: url-test, proxies: [HK GM 01], url: 'https://example.invalid', interval: 300}
rules: ['MATCH,Main']
"""

class NodeNameSearchPageTest {
    @get:Rule(order = 0) val library = TestLibrary { app ->
        var chains = chainMutation(EMPTY_CHAIN_LIBRARY, JSONObject().put("action", "save_group").put("name", "Filtered")
            .put("kind", "manual").put("selectorFilter", "ma & in !missing").put("nodeFilter", "hk | jp & gm !ev"))
        val id = chainState(chains).groups.single().id
        chains = chainMutation(chains, JSONObject().put("action", "import_nodes").put("groupId", id).put("contents", CHAIN_LANDING))
        val profile = app.engine.normalize("Search fixture", SEARCH_SOURCE).copy(chainGroupIds = listOf(id), chainRevision = 1)
        Library(listOf(profile), profile.id, chainLibrary = chains)
    }
    @get:Rule(order = 1) val compose = createAndroidComposeRule<MainActivity>()
    private val app get() = compose.activity.application as JeemiApplication
    private val model get() = ViewModelProvider(compose.activity)[JeemiViewModel::class.java]
    private var server: SearchCoreServer? = null
    private fun label(id: Int) = compose.activity.getString(id)
    private fun query(value: String) {
        compose.onNode(hasSetTextAction() and hasText(label(R.string.search_nodes))).performTextReplacement(value)
        compose.waitForIdle()
    }
    private fun batch() = compose.onNodeWithContentDescription(label(R.string.test_nodes))
    @After fun cleanup() {
        compose.runOnIdle { app.runtime.clear(false) }
        server?.close()
    }

    @Test fun compoundSearchKeepsGeneratedCardsAndSelectsAndTestsOnlyMatchingLiveNodes() {
        compose.waitUntil(15000) { model.state.value.loaded }
        val before = model.state.value
        val candidate = before.candidate!!
        assertEquals(2, JSONObject(candidate.chains).getInt("generated"))
        val hk = candidate.nodes.single { it.name.contains("⇐ HK") }.name
        val jp = candidate.nodes.single { it.name.contains("⇐ JP") }.name
        compose.onNode(hasText(label(R.string.subscriptions)) and hasClickAction()).performClick()
        compose.onNodeWithContentDescription(label(R.string.search_nodes)).performClick()
        query("landing & hk ! ev")
        compose.onNodeWithText(hk).assertIsDisplayed().performClick()
        compose.waitUntil(10000) { !model.state.value.busy && model.state.value.library.selected!!.selections["Main"] == hk }
        assertEquals(hk, app.repository.load().selected!!.selections["Main"])
        compose.onNodeWithText(jp).assertDoesNotExist()
        assertEquals(RuntimeState.Stopped, app.runtime.state.value)
        assertEquals(candidate.revision, model.state.value.candidate!!.revision)
        assertEquals(SEARCH_SOURCE, app.repository.load().selected!!.original)

        // Simulate only the local controller boundary, never a VPN or remote node.
        // Provider members exist only in this matching live snapshot.
        val provider = "JP GM provider"
        val proxies = candidate.nodes.associate { it.name to LiveProxy(it.type, "", emptyList(), null) }.toMutableMap()
        candidate.structure.groups.forEach { group -> proxies[group.name] = LiveProxy(
            if (group.type == "select") "Selector" else "URLTest", if (group.name == "Main") hk else "HK GM 01",
            group.members + if (group.name == "Main") listOf(provider) else emptyList(), null) }
        proxies[provider] = LiveProxy("Shadowsocks", "", emptyList(), null)
        proxies["DIRECT"] = LiveProxy("Direct", "", emptyList(), null)
        val local = SearchCoreServer(proxies).also { server = it }
        compose.runOnIdle {
            app.runtime.api = CoreApi(local.port, "search-fixture")
            app.runtime.live.value = LiveSession(before.library.selectedId!!, candidate.revision, before.geoRevision, proxies)
            app.runtime.state.value = RuntimeState.Running(candidate.revision)
        }
        query("　jp ＆ gm ！ev　")
        compose.onNodeWithText(provider).assertIsDisplayed()
        compose.onNodeWithText(jp).assertIsDisplayed()
        val instrumentation = androidx.test.platform.app.InstrumentationRegistry.getInstrumentation()
        val prefix = androidx.test.platform.app.InstrumentationRegistry.getArguments().getString("visualPrefix", "portrait")
        require(prefix.matches(Regex("[A-Za-z0-9_-]+")))
        val capture = java.io.File(app.getExternalFilesDir(null), "node-search").apply { mkdirs() }
        instrumentation.uiAutomation.takeScreenshot().also { bitmap ->
            java.io.File(capture, "$prefix-results.png").outputStream().use { bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it) }
            bitmap.recycle()
        }
        batch().assertIsEnabled().performSemanticsAction(SemanticsActions.OnClick) { it() }
        compose.waitUntil(10000) { local.tested.size == 3 && !model.state.value.testing }
        assertEquals(setOf("JP gm 02", jp, provider), local.tested.toSet())
        compose.onNodeWithContentDescription(compose.activity.getString(R.string.test_node, jp))
            .performSemanticsAction(SemanticsActions.OnClick) { it() }
        compose.waitUntil(10000) { local.tested.size == 4 && model.state.value.testingNodes.isEmpty() }
        assertEquals(jp, local.tested.last())
        compose.onNodeWithText(provider).performClick()
        compose.waitUntil(10000) { !model.state.value.busy && model.state.value.library.selected!!.selections["Main"] == provider }
        assertEquals(provider, app.repository.load().selected!!.selections["Main"])
        assertEquals(provider, app.runtime.live.value!!.proxies.getValue("Main").now)
        assertEquals(listOf("Main" to provider), local.selections.toList())

        listOf("main", "nested|direct", "socks5", "missing").forEach { value ->
            query(value)
            compose.onNodeWithText(label(R.string.no_results)).assertIsDisplayed()
            batch().assertIsNotEnabled()
        }
        query(" | & ! ")
        compose.onNodeWithText("Main").assertExists()
        compose.onNodeWithText(label(R.string.no_results)).assertDoesNotExist()
        assertEquals(4, local.tested.size)
        assertTrue(local.errors.toString(), local.errors.isEmpty())
    }
}

private class SearchCoreServer(initial: Map<String, LiveProxy>) : AutoCloseable {
    private val listener = ServerSocket(0, 8, InetAddress.getByName("127.0.0.1"))
    private val sockets = ConcurrentHashMap.newKeySet<Socket>()
    private val proxies = ConcurrentHashMap(initial)
    val port get() = listener.localPort
    val tested = CopyOnWriteArrayList<String>()
    val selections = CopyOnWriteArrayList<Pair<String, String>>()
    val errors = CopyOnWriteArrayList<String>()
    init { thread(isDaemon = true) {
        while (!listener.isClosed) {
            val socket = try { listener.accept() } catch (_: Exception) { break }
            sockets.add(socket)
            thread(isDaemon = true) {
                try { socket.use { handle(it) } }
                catch (error: Exception) { errors.add(error.javaClass.simpleName + ": " + error.message.orEmpty()) }
                finally { sockets.remove(socket) }
            }
        }
    } }
    private fun handle(socket: Socket) {
        socket.soTimeout = 5000
        val input = DataInputStream(socket.getInputStream())
        fun line(): String = buildString {
            while (length < 8192) { val char = input.readUnsignedByte().toChar(); if (char == '\n') break; if (char != '\r') append(char) }
        }
        val first = line().split(' ')
        val headers = mutableMapOf<String, String>()
        while (true) { val value = line(); if (value.isEmpty()) break; headers[value.substringBefore(':').lowercase()] = value.substringAfter(':').trim() }
        check(headers["authorization"] == "Bearer search-fixture")
        val bytes = ByteArray(headers["content-length"]?.toInt() ?: 0)
        require(bytes.size <= 8192); input.readFully(bytes)
        val path = first[1].substringBefore('?')
        val output = when {
            path == "/proxies" -> JSONObject().put("proxies", JSONObject(proxies.mapValues { entry(it.value) }))
            path == "/connections" -> JSONObject().put("connections", JSONArray())
            path == "/configs" -> {
                // Entering the running state synchronizes the saved log level.
                check(first[0] == "PATCH")
                check(JSONObject(bytes.toString(Charsets.UTF_8)).getString("log-level") in
                    listOf("silent", "error", "warning", "info", "debug"))
                JSONObject()
            }
            path.endsWith("/delay") -> {
                val name = URLDecoder.decode(path.removePrefix("/proxies/").removeSuffix("/delay"), "UTF-8")
                check(proxies.containsKey(name)); tested.add(name); JSONObject().put("delay", 37)
            }
            else -> {
                val name = URLDecoder.decode(path.removePrefix("/proxies/"), "UTF-8")
                if (first[0] == "PUT") {
                    val chosen = JSONObject(bytes.toString(Charsets.UTF_8)).getString("name")
                    val previous = proxies.getValue(name); check(chosen in previous.members)
                    proxies[name] = previous.copy(now = chosen); selections.add(name to chosen)
                }
                entry(proxies.getValue(name))
            }
        }.toString().toByteArray()
        socket.getOutputStream().apply {
            write("HTTP/1.1 200 OK\r\nContent-Type: application/json\r\nContent-Length: ${output.size}\r\nConnection: close\r\n\r\n".toByteArray())
            write(output); flush()
        }
    }
    private fun entry(proxy: LiveProxy) = JSONObject().put("type", proxy.type).put("now", proxy.now).apply {
        if (proxy.members.isNotEmpty()) put("all", JSONArray(proxy.members))
    }
    override fun close() { listener.close(); sockets.forEach { runCatching { it.close() } } }
}
