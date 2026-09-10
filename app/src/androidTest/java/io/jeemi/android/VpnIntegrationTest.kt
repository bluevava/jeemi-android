package io.jeemi.android

import android.content.ComponentName
import android.content.Intent
import android.net.VpnService
import android.view.accessibility.AccessibilityNodeInfo
import androidx.test.ext.junit.rules.ActivityScenarioRule
import androidx.test.platform.app.InstrumentationRegistry
import io.jeemi.android.domain.Preferences
import io.jeemi.android.domain.ProxyMode
import io.jeemi.android.domain.RuntimeState
import io.jeemi.android.runtime.VpnRequest
import mobile.Mobile
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import java.net.ServerSocket
import java.net.InetAddress
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.concurrent.thread
import kotlinx.coroutines.*
import io.jeemi.android.runtime.Diagnostics

class VpnIntegrationTest {
    @get:Rule(order = 0) val library = TestLibrary {
        io.jeemi.android.domain.Library(preferences = Preferences(runtimeJson =
            JSONObject(Mobile.runtimeDefaults()).put("logLevel", "info").put("findProcessMode", "always").toString()))
    }
    @get:Rule(order = 1) val activityRule = ActivityScenarioRule(MainActivity::class.java)
    private fun eventually(timeout: Long = 20_000, condition: () -> Boolean) {
        val until = System.currentTimeMillis() + timeout
        while (!condition()) { check(System.currentTimeMillis() < until) { "condition_timeout" }; Thread.sleep(100) }
    }
    @Test fun authorizedTunCarriesSeparateUidTcpAndDnsThroughMihomoAndStopsCleanly() = runVpn(ProxyMode.RULE)
    @Test fun globalModeExposesAndUsesTheCoreGlobalSelector() = runVpn(ProxyMode.GLOBAL)
    private fun runVpn(mode: ProxyMode) {
        lateinit var activity: MainActivity
        activityRule.scenario.onActivity { activity = it }
        val app = activity.application as JeemiApplication
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val diagnosticScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val diagnostic = Diagnostics(app.runtime, diagnosticScope, app)
        val observed = CountDownLatch(1)
        val inspected = CountDownLatch(1)
        val testPackage = instrumentation.context.packageName
        val close = AtomicBoolean(false)
        val sockets = java.util.concurrent.ConcurrentHashMap.newKeySet<java.net.Socket>()
        val traces = java.util.concurrent.CopyOnWriteArrayList<String>()
        var logConnection: java.net.HttpURLConnection? = null
        val proxy = ServerSocket(0, 8, InetAddress.getByName("127.0.0.1")).apply { soTimeout = 1000 }
        val worker = thread {
            while (!close.get()) {
                val socket = runCatching { proxy.accept() }.getOrNull() ?: continue
                sockets.add(socket)
                thread(isDaemon = true) { runCatching { socket.use {
                    it.soTimeout = 8000
                    val reader = it.getInputStream().bufferedReader()
                    var request = reader.readLine().orEmpty()
                    while (!reader.readLine().isNullOrEmpty()) { }
                    if (request.startsWith("CONNECT ")) {
                        it.getOutputStream().write("HTTP/1.1 200 Connection Established\r\n\r\n".toByteArray())
                        if (request.contains(":53 ")) {
                            val stream = java.io.DataInputStream(it.getInputStream())
                            val question = ByteArray(stream.readUnsignedShort()); stream.readFully(question)
                            val kind = question[question.size - 3].toInt() and 255
                            val address = if (kind == 28) byteArrayOf(0x20, 1, 0x0d, 0xb8.toByte(), 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 7) else byteArrayOf(203.toByte(), 0, 113, 7)
                            question[2] = 0x81.toByte(); question[3] = 0x80.toByte(); question[6] = 0; question[7] = 1
                            val answer = question + byteArrayOf(0xc0.toByte(), 12, 0, kind.toByte(), 0, 1, 0, 0, 0, 30, 0, address.size.toByte()) + address
                            val output = java.io.DataOutputStream(it.getOutputStream()); output.writeShort(answer.size); output.write(answer); output.flush()
                            return@use
                        }
                        request = reader.readLine().orEmpty()
                        while (!reader.readLine().isNullOrEmpty()) { }
                    }
                    if (request.contains("/jeemi-vpn-probe")) { observed.countDown(); inspected.await(8, TimeUnit.SECONDS) }
                    val body = "JEEMI_VPN_OK".toByteArray()
                    it.getOutputStream().write(("HTTP/1.1 200 OK\r\nContent-Length: " + body.size + "\r\nConnection: close\r\n\r\n").toByteArray() + body)
                } }; sockets.remove(socket) }
            }
        }
        try {
            val permission = VpnService.prepare(activity)
            if (permission != null) {
                activityRule.scenario.onActivity { it.startActivityForResult(permission, 99) }
                eventually {
                    val root = instrumentation.uiAutomation.rootInActiveWindow
                    val button = root?.findAccessibilityNodeInfosByViewId("android:id/button1")?.firstOrNull()
                    if (button != null) button.performAction(AccessibilityNodeInfo.ACTION_CLICK)
                    VpnService.prepare(activity) == null
                }
            }
            val source = "tun: {route-exclude-address: [198.51.100.0/24, 172.16.0.0/12, 'fc00::/7']}\n" +
                "dns: {use-hosts: true}\nhosts: {vpn-test.invalid: 203.0.113.111}\n" +
                "proxies:\n  - {name: Loopback test proxy, type: http, server: 127.0.0.1, port: " + proxy.localPort + "}\n" +
                "proxy-groups:\n  - {name: Probe, type: select, proxies: [DIRECT, Loopback test proxy]}\nrules: ['PROCESS-NAME," + testPackage + ",Probe', 'MATCH,Probe']"
            val profile = app.engine.normalize("Isolated VPN test", source)
            val preferences = Preferences(mode = mode, runtimeJson = JSONObject(Mobile.runtimeDefaults()).put("lanBypassRules", JSONArray()).put("findProcessMode", "always").toString())
            val candidate = app.engine.project(profile, preferences, emptyList())
            val selector = if (mode == ProxyMode.GLOBAL) "GLOBAL" else "Probe"
            assertTrue(candidate.structure.groups.any { it.name == selector })
            val startRequest = VpnRequest(profile.id, candidate.yaml, true,
                selections = mapOf("Probe" to "Loopback test proxy", selector to "Loopback test proxy"))
            activityRule.scenario.onActivity { app.runtime.start(startRequest) }
            eventually(35_000) { app.runtime.state.value is RuntimeState.Running || app.runtime.state.value is RuntimeState.Failed }
            assertTrue("Real TUN startup failed: " + app.runtime.state.value, app.runtime.state.value is RuntimeState.Running)
            val connectivity = app.getSystemService(android.net.ConnectivityManager::class.java)
            eventually {
                connectivity.allNetworks.any { network ->
                    connectivity.getNetworkCapabilities(network)?.hasTransport(android.net.NetworkCapabilities.TRANSPORT_VPN) == true &&
                        connectivity.getLinkProperties(network)?.routes?.let { routes ->
                            routes.any { it.destination.toString() == "198.51.100.0/24" && it.type == android.net.RouteInfo.RTN_THROW } &&
                                routes.any { it.destination.toString() == "172.19.0.2/32" && it.type == android.net.RouteInfo.RTN_UNICAST }
                        } == true
                }
            }
            val first = requireNotNull(app.runtime.live.value)
            val oldApi = requireNotNull(app.runtime.api)
            val oldHomes = java.io.File(app.noBackupFilesDir, "sessions").listFiles().orEmpty().map { it.name }
            activityRule.scenario.onActivity {
                app.runtime.restart(startRequest.copy(logLevel = "info"))
                assertEquals(RuntimeState.Restarting, app.runtime.state.value)
                // Repeated presses must not queue another restart or override the first one.
                app.runtime.restart(startRequest.copy(logLevel = "warning"))
            }
            eventually(35_000) { app.runtime.state.value is RuntimeState.Running || app.runtime.state.value is RuntimeState.Failed }
            assertTrue("Real TUN restart failed: " + app.runtime.state.value, app.runtime.state.value is RuntimeState.Running)
            val live = requireNotNull(app.runtime.live.value)
            assertNotEquals(first.sessionId, live.sessionId)
            assertNotSame(oldApi, app.runtime.api)
            assertEquals("info", app.runtime.request?.logLevel)
            assertEquals("info", requireNotNull(app.runtime.api).call("/configs").getString("log-level"))
            val newHomes = java.io.File(app.noBackupFilesDir, "sessions").listFiles().orEmpty().map { it.name }
            assertEquals(1, newHomes.size)
            assertTrue(oldHomes.intersect(newHomes.toSet()).isEmpty())
            assertTrue(live.proxies[selector]?.members?.contains("Loopback test proxy") == true)
            assertEquals("Loopback test proxy", live.proxies[selector]?.now)
            app.runtime.select(profile.id, candidate.revision, live.geoRevision, selector, "DIRECT", "selector")
            assertEquals("DIRECT", app.runtime.live.value?.proxies?.get(selector)?.now)
            app.runtime.select(profile.id, candidate.revision, live.geoRevision, selector, "Loopback test proxy", "selector")
            assertEquals("Loopback test proxy", app.runtime.live.value?.proxies?.get(selector)?.now)
            if (mode == ProxyMode.RULE) {
                activityRule.scenario.onActivity {
                    app.runtime.restart(startRequest.copy())
                    app.runtime.stop()
                    app.runtime.start(startRequest) // Ignored until stop and Service destruction finish.
                }
                eventually { app.runtime.state.value == RuntimeState.Stopped }
                assertNull(app.runtime.live.value)
                assertTrue(java.io.File(app.noBackupFilesDir, "sessions").listFiles().orEmpty().isEmpty())
                activityRule.scenario.onActivity { app.runtime.start(startRequest.copy()) }
                eventually(35_000) { app.runtime.state.value is RuntimeState.Running || app.runtime.state.value is RuntimeState.Failed }
                assertTrue(app.runtime.state.value is RuntimeState.Running)
            }
            val coreApi = requireNotNull(app.runtime.api)
            coreApi.call("/configs", "PATCH", JSONObject().put("log-level", "debug"))
            logConnection = coreApi.open("/logs?level=debug", 3000)
            thread(isDaemon = true) { runCatching { logConnection!!.inputStream.bufferedReader().useLines { lines ->
                lines.take(500).forEach { traces.add(it.take(4096)) }
            } } }
            diagnosticScope.launch { diagnostic.observeLogs("debug") }
            Thread.sleep(200)
            assertNotEquals(app.applicationInfo.uid, app.packageManager.getApplicationInfo(testPackage, 0).uid)
            activityRule.scenario.onActivity { host ->
                host.startActivity(Intent().setComponent(ComponentName(testPackage, "io.jeemi.android.testprobe.TrafficProbeActivity"))
                    .putExtra("url", "http://vpn-test.invalid/jeemi-vpn-probe"))
            }
            assertTrue("Separate UID traffic did not reach the mihomo-selected proxy", observed.await(20, TimeUnit.SECONDS))
            val connections = requireNotNull(app.runtime.api).call("/connections").getJSONArray("connections")
            val record = (0 until connections.length()).map { connections.getJSONObject(it) }
                .first { it.getJSONObject("metadata").optString("host") == "vpn-test.invalid" }
            assertEquals(testPackage, record.getJSONObject("metadata").getString("process"))
            assertEquals(app.packageManager.getApplicationInfo(testPackage, 0).uid.toLong(), record.getJSONObject("metadata").getLong("uid"))
            if (mode == ProxyMode.RULE) assertEquals("ProcessName", record.getString("rule"))
            inspected.countDown()
            eventually { instrumentation.uiAutomation.rootInActiveWindow?.findAccessibilityNodeInfosByText("JEEMI_VPN_OK")?.isNotEmpty() == true }
            assertTrue(app.runtime.state.value is RuntimeState.Running)
            eventually(5000) { diagnostic.logs.value.rows.any { it.message.contains("vpn-test.invalid") } }
            diagnostic.queryDns("example.test", "1.1.1.1", "invalid", "invalid", false)
            eventually(12000) { !diagnostic.dns.value.busy }
            val dns = JSONObject(requireNotNull(diagnostic.dns.value.result)).getJSONObject("proxy")
            assertEquals("success", dns.getString("status"))
            assertEquals("Loopback test proxy", dns.getString("node"))
            val records = dns.getJSONArray("records")
            assertEquals("203.0.113.7", records.getJSONObject(0).getJSONArray("addresses").getString(0))
            assertEquals("2001:db8::7", records.getJSONObject(1).getJSONArray("addresses").getString(0))
            assertEquals("Loopback test proxy", app.runtime.live.value?.proxies?.get(selector)?.now)
        } finally {
            inspected.countDown()
            diagnostic.cancelDns(); diagnosticScope.cancel()
            logConnection?.disconnect()
            java.io.File(app.cacheDir, "vpn-test-trace.txt").writeText(traces.joinToString("\n"))
            sockets.forEach { runCatching { it.close() } }
            app.runtime.stop()
            eventually { app.runtime.state.value == RuntimeState.Stopped || app.runtime.state.value is RuntimeState.Failed }
            close.set(true); proxy.close(); worker.join(2000)
        }
        assertNull(app.runtime.live.value)
        assertTrue(java.io.File(app.noBackupFilesDir, "sessions").listFiles().orEmpty().isEmpty())
    }
}
