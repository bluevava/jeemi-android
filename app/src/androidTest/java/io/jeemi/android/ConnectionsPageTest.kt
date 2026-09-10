package io.jeemi.android

import android.graphics.Bitmap
import androidx.appcompat.app.AppCompatDelegate
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.core.os.LocaleListCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.ViewModelProvider
import androidx.test.platform.app.InstrumentationRegistry
import io.jeemi.android.domain.*
import io.jeemi.android.runtime.*
import io.jeemi.android.ui.JeemiViewModel
import org.json.JSONArray
import org.json.JSONObject
import org.junit.*
import org.junit.Assert.*
import java.io.File
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketException
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import kotlin.concurrent.thread

/** Real HTTP observation and actual Compose UI, using a synthetic local controller. */
class ConnectionsPageTest {
    @get:Rule(order = 0) val library = TestLibrary { app ->
        val profile = app.engine.normalize("Connections fixture", "proxies: []\nrules: ['MATCH,DIRECT']")
        Library(listOf(profile), profile.id)
    }
    @get:Rule(order = 1) val compose = createAndroidComposeRule<MainActivity>()
    private val app get() = compose.activity.application as JeemiApplication
    private val model get() = ViewModelProvider(compose.activity)[JeemiViewModel::class.java]
    private var server: ConnectionServer? = null
    private val initialLocale = AppCompatDelegate.getApplicationLocales()
    private fun label(id: Int) = compose.activity.getString(id)
    private fun more() {
        compose.onNode(hasText(label(R.string.more_navigation)) and hasClickAction()).performClick()
        compose.onNode(hasText(label(R.string.connections)) and hasClickAction()).performClick()
    }
    private fun ready(): ConnectionServer {
        compose.waitUntil(15000) { model.state.value.loaded }
        return ConnectionServer().also { local ->
            server = local
            compose.runOnIdle {
                val state = model.state.value
                app.runtime.api = CoreApi(local.port, "fixture-only")
                app.runtime.live.value = LiveSession(state.library.selectedId!!, state.candidate!!.revision, "fixture")
                app.runtime.state.value = RuntimeState.Running(state.candidate.revision)
            }
        }
    }
    private fun capture(name: String) {
        compose.waitForIdle()
        val instrument = InstrumentationRegistry.getInstrumentation()
        instrument.waitForIdleSync(); Thread.sleep(250)
        val prefix = InstrumentationRegistry.getArguments().getString("visualPrefix", "phone")
        check(prefix.matches(Regex("[A-Za-z0-9_-]+")))
        val directory = File(app.getExternalFilesDir(null), "connections-table").apply { mkdirs() }
        instrument.uiAutomation.takeScreenshot().also { bitmap ->
            File(directory, "$prefix-$name.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
            bitmap.recycle()
        }
    }
    private fun language(tag: String) {
        compose.runOnUiThread { AppCompatDelegate.setApplicationLocales(LocaleListCompat.forLanguageTags(tag)) }
        compose.waitUntil(10000) { compose.activity.resources.configuration.locales[0].language == tag.substringBefore('-') }
        compose.waitForIdle()
    }
    @After fun cleanup() {
        compose.activityRule.scenario.moveToState(Lifecycle.State.RESUMED)
        compose.runOnUiThread { app.runtime.clear(false); AppCompatDelegate.setApplicationLocales(initialLocale) }
        server?.close()
    }
    @Test fun navigationAndBackgroundOwnRequestsAndCancelOutstandingReads() {
        val local = ready()
        Thread.sleep(250); assertEquals(0, local.requests.get())
        more()
        compose.waitUntil(10000) { local.requests.get() >= 2 && model.diagnostics.connections.value.rows.firstOrNull()?.uploadRate != null }
        assertTrue(model.diagnostics.connections.value.rows.first().uploadRate!! > 0)
        compose.onNodeWithContentDescription(label(R.string.pause_feed)).assertDoesNotExist()
        compose.onNodeWithContentDescription(label(R.string.resume_feed)).assertDoesNotExist()
        local.holdNext.set(true)
        assertTrue(local.held.await(4, TimeUnit.SECONDS))
        compose.onNode(hasText(label(R.string.home)) and hasClickAction()).performClick()
        compose.onNodeWithTag("connection-table").assertDoesNotExist()
        assertTrue("Leaving the page left the HTTP read open", local.disconnected.await(1500, TimeUnit.MILLISECONDS))
        val afterLeave = local.requests.get(); Thread.sleep(1250)
        assertEquals(afterLeave, local.requests.get())
        assertTrue(app.runtime.state.value is RuntimeState.Running)
        more(); compose.waitUntil(5000) { local.requests.get() > afterLeave }
        compose.activityRule.scenario.moveToState(Lifecycle.State.CREATED)
        val afterBackground = local.requests.get(); Thread.sleep(1250)
        assertEquals(afterBackground, local.requests.get())
        compose.activityRule.scenario.moveToState(Lifecycle.State.RESUMED)
        compose.waitUntil(5000) { local.requests.get() > afterBackground }
        compose.onNodeWithTag("connection-close-fixture-1").performClick()
        compose.onNodeWithText(label(R.string.confirm)).performClick()
        compose.waitUntil(5000) { "fixture-1" in local.closed }
        assertEquals(setOf("fixture-1"), local.closed.toSet())
        compose.runOnIdle { app.runtime.clear(false) }
        compose.waitUntil(5000) { model.diagnostics.connections.value.let { feed ->
            feed.status == "offline" && feed.rows.isNotEmpty() && feed.rows.all { it.uploadRate == null && it.downloadRate == null }
        } }
    }
    @Test fun fourCellsDetailsAndThreeRuleTypesUseDesktopComposer() {
        val local = ready(); language("zh-CN"); more()
        compose.waitUntil(10000) { model.diagnostics.connections.value.rows.firstOrNull()?.downloadRate != null }
        for (title in listOf(R.string.connection_app_target, R.string.connection_match_outbound, R.string.connection_speed, R.string.connection_actions))
            compose.onNodeWithText(label(title)).assertIsDisplayed()
        compose.onAllNodesWithTag("connection-row-fixture-1").assertCountEquals(1)
        compose.onNodeWithTag("connection-close-fixture-1").assertIsDisplayed().assertIsEnabled()
        compose.onNodeWithTag("connection-details-fixture-1").assertIsDisplayed().assertIsEnabled()
        compose.waitUntil(5000) { compose.onAllNodesWithTag("connection-app-icon-fixture-1", useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty() }
        val icon = compose.onNodeWithTag("connection-app-icon-fixture-1", useUnmergedTree = true).assertIsDisplayed()
        val density = compose.activity.resources.displayMetrics.density
        assertTrue("App icon must stay at text scale", icon.fetchSemanticsNode().boundsInRoot.height <= 22 * density)
        compose.onNodeWithTag("connection-app-fallback-fixture-6", useUnmergedTree = true).assertIsDisplayed()
        assertTrue("Connection row must remain compact", compose.onNodeWithTag("connection-row-fixture-1").fetchSemanticsNode().boundsInRoot.height <= 64 * density)
        val closeBounds = compose.onNodeWithTag("connection-close-fixture-1").fetchSemanticsNode().boundsInRoot
        val detailsBounds = compose.onNodeWithTag("connection-details-fixture-1").fetchSemanticsNode().boundsInRoot
        assertTrue("Compact actions must not overlap", closeBounds.bottom <= detailsBounds.top)
        capture("table-zh")
        language("en")
        compose.waitUntil(10000) { compose.onAllNodesWithText("App/Target").fetchSemanticsNodes().isNotEmpty() }
        capture("table-en")
        compose.onNodeWithTag("connection-details-fixture-1").performClick()
        compose.onNode(hasText("api.example.com:443") and hasAnyAncestor(isDialog())).assertExists()
        assertTrue("Details must not close a connection", local.closed.isEmpty())
        capture("details-en")
        val original = app.repository.load().selected
        fun add(field: Int, expected: String, appendTo: String? = null) {
            compose.onNodeWithContentDescription(compose.activity.getString(R.string.add_connection_rule_for, label(field))).performScrollTo().performClick()
            compose.waitUntil(10000) { compose.onAllNodesWithText(expected, substring = true).fetchSemanticsNodes().isNotEmpty() }
            if (appendTo != null) {
                compose.onNode(hasText(label(R.string.rule_sets)) and hasClickAction()).performClick()
                compose.onNode(hasText(appendTo) and hasAnyAncestor(isPopup())).performClick()
            }
            val revision = model.state.value.saveRevision
            compose.waitUntil(10000) { compose.onAllNodes(hasText(label(R.string.save)) and isEnabled()).fetchSemanticsNodes().isNotEmpty() }
            if (field == R.string.application_package) capture("package-rule-en")
            compose.onNode(hasText(label(R.string.save)) and hasClickAction()).performClick()
            compose.waitUntil(10000) { model.state.value.saveRevision > revision || model.state.value.error != null }
            assertNull(model.state.value.error)
        }
        add(R.string.connection_target, "DOMAIN-SUFFIX,api.example.com")
        val ruleSet = app.repository.load().resources.single()
        add(R.string.application_package, "PROCESS-NAME,com.android.settings", ruleSet.name)
        add(R.string.connection_destination, "IP-CIDR,203.0.113.1/32", ruleSet.name)
        val stored = app.repository.load()
        assertEquals(original, stored.selected)
        assertEquals(1, stored.resources.size)
        for (rule in listOf("DOMAIN-SUFFIX,api.example.com", "PROCESS-NAME,com.android.settings", "IP-CIDR,203.0.113.1/32"))
            assertTrue(stored.resources.single().content.contains(rule))
        assertFalse(stored.resources.single().content.contains("PROCESS-PATH"))
    }
    @Test fun unknownSystemProcessesUseFallbacksAndReplacePreviousAppIcons() {
        val local = ready()
        local.unknownApplications = true
        language("zh-CN"); more()
        compose.waitUntil(10000) { model.diagnostics.connections.value.rows.size == 6 && model.diagnostics.connections.value.rows.first().downloadRate != null }
        assertEquals(0L, model.diagnostics.connections.value.rows.first().uid)
        assertNull(model.diagnostics.connections.value.rows[1].uid)
        for (index in 1..6) compose.onNodeWithTag("connection-app-fallback-fixture-$index", useUnmergedTree = true).assertIsDisplayed()
        capture("unknown-apps-zh")
        language("en"); capture("unknown-apps-en")
        compose.onNodeWithTag("connection-details-fixture-1").performClick()
        compose.onNode(hasText("0") and hasAnyAncestor(isDialog())).assertExists()
        compose.onNodeWithContentDescription(compose.activity.getString(R.string.add_connection_rule_for, label(R.string.application_package))).assertDoesNotExist()
        androidx.test.espresso.Espresso.pressBack()
        local.unknownApplications = false
        compose.waitUntil(10000) { compose.onAllNodesWithTag("connection-app-icon-fixture-1", useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty() }
        local.unknownApplications = true
        compose.waitUntil(10000) { model.diagnostics.connections.value.rows.first().process.isEmpty() }
        compose.waitUntil(5000) { compose.onAllNodesWithTag("connection-app-fallback-fixture-1", useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithTag("connection-app-icon-fixture-1", useUnmergedTree = true).assertDoesNotExist()
        assertTrue(local.closed.isEmpty())
        assertEquals("live", model.diagnostics.connections.value.status)
    }
    @Test fun largeLiveTableShowsTotalAndKeepsSearchAndCloseUsable() {
        val local = ready()
        local.rowCount = 3000
        language("zh-CN"); more()
        compose.waitUntil(15000) { model.diagnostics.connections.value.activeCount == 3000 }
        assertEquals(2000, model.diagnostics.connections.value.rows.count { !it.ended })
        compose.onNodeWithText(compose.activity.getString(R.string.connections_active, 3000)).assertExists()
        compose.onNodeWithText(compose.activity.getString(R.string.connection_display_limit, 2000, 3000), substring = true).assertExists()
        capture("limit-zh")
        language("en"); capture("limit-en")
        compose.onNodeWithContentDescription(label(R.string.search_connections)).performClick()
        compose.onNode(hasSetTextAction()).performTextInput("service-1999.example.com")
        compose.waitUntil(10000) {
            compose.onAllNodesWithTag("connection-row-fixture-1999").fetchSemanticsNodes().size == 1
        }
        compose.onNodeWithTag("connection-close-fixture-1999").performClick()
        compose.onNodeWithText(label(R.string.confirm)).performClick()
        compose.waitUntil(10000) { "fixture-1999" in local.closed && model.diagnostics.connections.value.activeCount == 2999 }
        compose.waitUntil(10000) { model.diagnostics.connections.value.rows.any { it.id == "fixture-1999" && it.ended } }
        compose.onNodeWithTag("connection-row-fixture-1999").assertDoesNotExist()
    }
}

private class ConnectionServer {
    private val socket = ServerSocket(0, 16, InetAddress.getByName("127.0.0.1"))
    val port get() = socket.localPort
    val requests = AtomicInteger()
    @Volatile var rowCount = 6
    @Volatile var unknownApplications = false
    val holdNext = AtomicBoolean(false)
    val held = CountDownLatch(1)
    val disconnected = CountDownLatch(1)
    val closed = ConcurrentHashMap.newKeySet<String>()
    private val clients = ConcurrentHashMap.newKeySet<Socket>()
    private val running = AtomicBoolean(true)
    private val worker = thread(isDaemon = true) {
        while (running.get()) {
            val client = runCatching { socket.accept() }.getOrNull() ?: break
            clients.add(client)
            thread(isDaemon = true) {
                try { client.use { handle(it) } } catch (_: Exception) { } finally { clients.remove(client) }
            }
        }
    }
    private fun handle(client: Socket) {
        client.soTimeout = 5000
        val reader = client.getInputStream().bufferedReader()
        val request = reader.readLine()?.split(' ') ?: return
        val headers = generateSequence { reader.readLine() }.takeWhile { it.isNotEmpty() }.toList()
        check(headers.any { it.equals("Authorization: Bearer fixture-only", ignoreCase = true) })
        val length = headers.firstOrNull { it.startsWith("Content-Length:", true) }?.substringAfter(':')?.trim()?.toIntOrNull() ?: 0
        repeat(length) { reader.read() }
        val path = request[1]
        val body = if (request[0] == "GET" && path == "/connections") {
            val tick = requests.incrementAndGet()
            if (holdNext.compareAndSet(true, false)) {
                held.countDown()
                // Closing a request socket can produce EOF or a TCP reset.
                // A server timeout must still fail the cancellation assertion.
                try { if (reader.read() == -1) disconnected.countDown() }
                catch (_: SocketException) { disconnected.countDown() }
                return
            }
            JSONObject().put("connections", JSONArray((1..rowCount).filter { "fixture-$it" !in closed }.map { index ->
                JSONObject().put("id", "fixture-$index").put("start", "2026-09-10T00:00:%02dZ".format((10 - index).coerceAtLeast(0)))
                    .put("upload", 1_000_000L + tick * 4096L).put("download", 20_000_000L + tick * 65536L)
                    .put("rule", if (index == 1) "RuleSet" else "DomainSuffix").put("rulePayload", if (index == 1) "Applications" else "example.com")
                    .put("chains", JSONArray(listOf("Node A", "Automatic", "Proxy")))
                    .put("metadata", JSONObject().put("host", if (index == 1) "api.example.com" else "service-$index.example.com")
                        .put("destinationIP", "203.0.113.$index").put("destinationPort", "443")
                        .put("sourceIP", "172.19.0.1").put("sourcePort", "12345").put("network", "tcp").put("type", "TUN")
                        .put("process", if (unknownApplications) when (index) {
                            1 -> ""; 2 -> JSONObject.NULL; 3 -> "/system/bin/netd"; 4 -> "com.example:remote"; 5 -> "com.bad\u0000name"
                            else -> "org.example.missing"
                        } else if (index == 6) "org.example.missing" else "com.android.settings")
                        .put("uid", if (unknownApplications && index == 1) 0 else if (unknownApplications && index == 2) -1 else 1000))
            })).toString()
        } else { if (request[0] == "DELETE" && path.startsWith("/connections/")) closed.add(path.substringAfterLast('/')); "{}" }
        val bytes = body.toByteArray()
        client.getOutputStream().write(("HTTP/1.1 200 OK\r\nContent-Type: application/json\r\nContent-Length: " + bytes.size + "\r\nConnection: close\r\n\r\n").toByteArray() + bytes)
    }
    fun close() { running.set(false); socket.close(); clients.forEach { runCatching { it.close() } }; worker.join(1000) }
}
