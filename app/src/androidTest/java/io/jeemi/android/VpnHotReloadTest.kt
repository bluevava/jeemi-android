package io.jeemi.android

import android.content.ComponentName
import android.content.Intent
import android.net.VpnService
import android.view.accessibility.AccessibilityNodeInfo
import androidx.lifecycle.ViewModelProvider
import androidx.test.ext.junit.rules.ActivityScenarioRule
import androidx.test.platform.app.InstrumentationRegistry
import io.jeemi.android.data.BundledCore
import io.jeemi.android.domain.*
import io.jeemi.android.runtime.VpnRequest
import io.jeemi.android.ui.JeemiViewModel
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.withLock
import mobile.Mobile
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import java.io.File
import java.net.InetAddress
import java.net.ServerSocket
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import kotlin.concurrent.thread

class VpnHotReloadTest {
    @get:Rule(order = 0) val library = TestLibrary()
    @get:Rule(order = 1) val activityRule = ActivityScenarioRule(MainActivity::class.java)
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    private lateinit var app: JeemiApplication
    private lateinit var activity: MainActivity
    private lateinit var model: JeemiViewModel

    private fun eventually(timeout: Long = 30_000, condition: () -> Boolean) {
        val until = System.currentTimeMillis() + timeout
        while (!condition()) { check(System.currentTimeMillis() < until) { "condition_timeout: ${app.runtime.state.value}" }; Thread.sleep(75) }
    }
    private fun save(value: Library) = runBlocking(Dispatchers.IO) { app.repository.mutations.withLock { app.repository.save(value) } }
    private fun corePid(): String = File("/proc").listFiles().orEmpty().single { path ->
        path.name.toIntOrNull() != null && runCatching { File(path, "exe").canonicalPath == BundledCore(app).executable.canonicalPath }.getOrDefault(false)
    }.name
    private fun source(port: Int, extra: String = "") = "dns: {use-hosts: true}\nhosts: {reload-test.invalid: 203.0.113.111}\n" +
        "proxies: [{name: Keep, type: http, server: 127.0.0.1, port: $port}, {name: Spare$extra, type: http, server: 127.0.0.1, port: $port}]\n" +
        "proxy-groups: [{name: Route, type: select, proxies: [Spare$extra, Keep]}]\nrules: ['MATCH,Route']\n"
    private fun start(port: Int, stack: TunStack): Library {
        activityRule.scenario.onActivity { activity = it; app = it.application as JeemiApplication; model = ViewModelProvider(it)[JeemiViewModel::class.java] }
        eventually { model.state.value.loaded }
        val profile = app.engine.normalize("Reload fixture", source(port)).copy(selections = mapOf("Route" to "Keep"))
        val preferences = app.engine.normalizePreferences(Preferences(tunStack = stack, runtimeJson = JSONObject(Mobile.runtimeDefaults())
            .put("lanBypassRules", JSONArray()).toString()))
        val value = Library(listOf(profile), profile.id, preferences, bundledRoutingVersion = 1)
        save(value)
        val permission = VpnService.prepare(activity)
        if (permission != null) {
            activityRule.scenario.onActivity { it.startActivityForResult(permission, 99) }
            eventually {
                instrumentation.uiAutomation.rootInActiveWindow?.findAccessibilityNodeInfosByViewId("android:id/button1")
                    ?.firstOrNull()?.performAction(AccessibilityNodeInfo.ACTION_CLICK)
                VpnService.prepare(activity) == null
            }
        }
        val candidate = app.engine.project(profile, preferences, emptyList())
        activityRule.scenario.onActivity { app.runtime.start(VpnRequest(profile.id, candidate.yaml, true, candidate.revision,
            selections = profile.selections, tunStack = stack, libraryRevision = app.repository.revision.value)) }
        eventually { app.runtime.state.value is RuntimeState.Running || app.runtime.state.value is RuntimeState.Failed }
        assertTrue(app.runtime.state.value.toString(), app.runtime.state.value is RuntimeState.Running)
        return value
    }
    private fun probe(expected: ReloadProxy) {
        val count = expected.requests.get()
        val testPackage = instrumentation.context.packageName
        assertNotEquals(app.applicationInfo.uid, app.packageManager.getApplicationInfo(testPackage, 0).uid)
        instrumentation.runOnMainSync {
            app.startActivity(Intent().addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
                .setComponent(ComponentName(testPackage, "io.jeemi.android.testprobe.TrafficProbeActivity"))
                .putExtra("url", "http://reload-test.invalid/reload-${System.nanoTime()}"))
        }
        eventually { expected.requests.get() > count && instrumentation.uiAutomation.rootInActiveWindow
            ?.findAccessibilityNodeInfosByText("JEEMI_VPN_OK")?.isNotEmpty() == true }
    }
    private fun stop() {
        instrumentation.runOnMainSync { app.runtime.stop() }
        eventually(10_000) { app.runtime.state.value == RuntimeState.Stopped || app.runtime.state.value is RuntimeState.Failed }
        assertNull(app.runtime.live.value)
        assertTrue(File(app.noBackupFilesDir, "sessions").listFiles().orEmpty().isEmpty())
    }

    @Test fun mipsAppliesSavedSubscriptionsWithoutReplacingCoreAndReconnectsChangedRoutes() = reload(TunStack.MIPS)
    @Test fun systemAppliesSavedSubscriptionsWithoutReplacingCoreAndReconnectsChangedRoutes() = reload(TunStack.SYSTEM)
    @Test fun gvisorAppliesSavedSubscriptionsWithoutReplacingCoreAndReconnectsChangedRoutes() = reload(TunStack.GVISOR)
    @Test fun mixedAppliesSavedSubscriptionsWithoutReplacingCoreAndReconnectsChangedRoutes() = reload(TunStack.MIXED)

    private fun reload(stack: TunStack) {
        ReloadProxy().use { first -> ReloadProxy().use { second ->
            val initial = start(first.port, stack)
            try {
                val owner = app.runtime.service
                val api = app.runtime.api
                val pid = corePid()
                val home = File(app.noBackupFilesDir, "sessions").listFiles().orEmpty().single()
                val originalSession = app.runtime.live.value!!.sessionId
                instrumentation.runOnMainSync {
                    owner!!.onStartCommand(Intent(app, io.jeemi.android.runtime.JeemiVpnService::class.java)
                        .setAction("reconfigure").putExtra("command", "expired-fixture"), 0, 999)
                }
                assertTrue(app.runtime.state.value is RuntimeState.Running)
                probe(first)
                // Use the actual editor transaction, including core validation.
                eventually { model.state.value.library.selectedId == initial.selectedId }
                instrumentation.runOnMainSync { model.importText("Reload fixture", source(second.port, "New"), id = initial.selectedId) }
                eventually { model.state.value.library.selected?.original?.contains("SpareNew") == true && !model.state.value.busy }
                assertNull(model.state.value.error)
                eventually { app.runtime.live.value?.revision == model.state.value.candidate?.revision && app.runtime.state.value is RuntimeState.Running }
                assertSame(owner, app.runtime.service); assertSame(api, app.runtime.api); assertEquals(pid, corePid())
                assertEquals(home, File(app.noBackupFilesDir, "sessions").listFiles().orEmpty().single())
                assertNotEquals(originalSession, app.runtime.live.value!!.sessionId)
                eventually { app.runtime.live.value?.proxies?.get("Route")?.now == "Keep" }
                assertTrue(app.runtime.live.value!!.proxies.getValue("Route").members.contains("SpareNew"))
                probe(second)

                // A running VPN must not keep subscription downloads or full
                // node snapshots active behind the separate-UID probe.
                eventually { !app.uiVisible.value }
                val scheduled = app.repository.load().let { value -> value.copy(subscriptions = listOf(value.selected!!.copy(
                    sourceUrl = "https://example.invalid/offline-fixture", updatedAt = 1000, autoUpdateIntervalMinutes = 10))) }
                save(scheduled)
                val scheduledCalls = AtomicInteger()
                val updater = io.jeemi.android.data.SubscriptionAutoUpdater(app, { 601_000L }) {
                    io.jeemi.android.data.SubscriptionFetcher(openConnection = { url ->
                        scheduledCalls.incrementAndGet()
                        object : java.net.HttpURLConnection(url) {
                        override fun connect() = Unit
                        override fun usingProxy() = false
                        override fun disconnect() = Unit
                        override fun getResponseCode() = 200
                        override fun getInputStream() = source(second.port, "Scheduled").byteInputStream()
                    } })
                }
                try {
                    updater.initialized()
                    Thread.sleep(1800) // Let any foreground snapshot already in flight finish.
                    api!!.call("/proxies/Route", "PUT", JSONObject().put("name", "SpareNew"))
                    Thread.sleep(3500)
                    assertEquals(0, scheduledCalls.get())
                    assertEquals("Keep", app.runtime.live.value!!.proxies.getValue("Route").now)
                    assertEquals("SpareNew", api.call("/proxies/Route").getString("now"))
                    api.call("/proxies/Route", "PUT", JSONObject().put("name", "Keep"))
                    instrumentation.runOnMainSync {
                        app.startActivity(Intent(app, MainActivity::class.java)
                            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_REORDER_TO_FRONT))
                    }
                    eventually { app.uiVisible.value }
                    eventually {
                        check(updater.errors.value.isEmpty()) { "scheduled_validation_failed" }
                        app.repository.load().selected!!.original.contains("SpareScheduled")
                    }
                    assertEquals(1, scheduledCalls.get())
                } finally { updater.close() }
                val downloaded = app.repository.load()
                assertTrue(downloaded.selected!!.original.contains("SpareScheduled"))
                val scheduledCandidate = app.engine.project(downloaded.selected!!, downloaded.preferences, downloaded.resources)
                eventually { app.runtime.live.value?.revision == scheduledCandidate.revision && app.runtime.state.value is RuntimeState.Running }
                assertEquals(pid, corePid()); probe(second)
                val good = app.repository.load()
                val liveRevision = app.runtime.live.value!!.revision
                // The core accepts syntax but cannot bind this occupied port.
                // Require an acknowledged rollback and a real working data plane.
                ServerSocket(0, 1, InetAddress.getByName("127.0.0.1")).use { occupied ->
                    save(good.copy(preferences = good.preferences.copy(runtimeJson = JSONObject(good.preferences.runtimeJson)
                        .put("listenPort", occupied.localPort).toString())))
                    eventually { app.runtime.updateFailed.value && app.runtime.state.value is RuntimeState.Running }
                    assertEquals(liveRevision, app.runtime.live.value!!.revision)
                    assertEquals(pid, corePid()); probe(second)
                    save(good)
                    eventually { !app.runtime.updateFailed.value }
                }
                // A corrupt externally saved revision must never replace the active core.
                val bad = good.selected!!.copy(original = "proxies: [{name: Keep, type: ss, server: 127.0.0.1, port: 1234, cipher: invalid-fixture, password: fixture}]\nproxy-groups: [{name: Route, type: select, proxies: [Keep]}]\nrules: ['MATCH,Route']")
                save(good.copy(subscriptions = listOf(bad)))
                eventually { app.runtime.updateFailed.value }
                assertEquals(liveRevision, app.runtime.live.value!!.revision)
                assertEquals(pid, corePid()); probe(second)

                for (suffix in listOf("One", "Latest")) {
                    val fresh = app.engine.normalize("Reload fixture", source(second.port, suffix)).copy(id = good.selectedId!!, selections = good.selected!!.selections)
                    save(good.copy(subscriptions = listOf(fresh)))
                }
                val latest = app.repository.load()
                val latestCandidate = app.engine.project(latest.selected!!, latest.preferences, latest.resources)
                eventually { app.runtime.live.value?.revision == latestCandidate.revision && app.runtime.state.value is RuntimeState.Running &&
                    app.runtime.live.value?.proxies?.get("Route")?.members?.contains("SpareLatest") == true }
                assertEquals(pid, corePid()); assertFalse(app.runtime.updateFailed.value)
                assertTrue(app.runtime.live.value!!.proxies.getValue("Route").members.contains("SpareLatest"))

                val routes = latest.copy(preferences = latest.preferences.copy(runtimeJson = JSONObject(latest.preferences.runtimeJson)
                    .put("dnsFakeIpRange", "198.19.5.1/24").toString()))
                val newCandidate = app.engine.project(routes.selected!!, routes.preferences, routes.resources)
                save(routes)
                eventually(40_000) { app.runtime.live.value?.revision == newCandidate.revision && app.runtime.state.value is RuntimeState.Running }
                assertSame(owner, app.runtime.service); assertNotSame(api, app.runtime.api); assertNotEquals(pid, corePid())
                probe(second)
                stop()
                save(latest)
                Thread.sleep(750)
                assertEquals(RuntimeState.Stopped, app.runtime.state.value)
            } finally { stop() }
        } }
    }

    @Test fun stopDuringProviderReloadNeverResurrectsTheSession() {
        ReloadProxy().use { proxy ->
            val initial = start(proxy.port, TunStack.MIPS)
            val server = ServerSocket(0, 1, InetAddress.getByName("127.0.0.1")).apply { soTimeout = 1000 }
            val close = AtomicBoolean(false)
            val loading = AtomicBoolean(false)
            val worker = thread(isDaemon = true) {
                while (!close.get()) {
                    val socket = runCatching { server.accept() }.getOrNull() ?: continue
                    socket.use { loading.set(true); while (!close.get()) Thread.sleep(50) }
                }
            }
            try {
                val raw = source(proxy.port, "Provider").replace("rules: ['MATCH,Route']", "rules: ['RULE-SET,Slow,Route', 'MATCH,Route']") +
                    "rule-providers: {Slow: {type: http, behavior: domain, proxy: DIRECT, path: ./slow.yaml, url: 'http://127.0.0.1:${server.localPort}/rules', interval: 3600}}\n"
                val changed = app.engine.normalize("Reload fixture", raw).copy(id = initial.selectedId!!)
                val candidate = app.engine.project(changed, initial.preferences, initial.resources)
                io.jeemi.android.data.ChainCandidateValidator(app).use { it.validate(candidate.yaml) }
                save(initial.copy(subscriptions = listOf(changed)))
                eventually {
                    check(!app.runtime.updateFailed.value) { "reload_failed_before_provider_loading" }
                    loading.get() && app.runtime.state.value == RuntimeState.Updating
                }
                stop()
                assertEquals(RuntimeState.Stopped, app.runtime.state.value)
                Thread.sleep(750)
                assertEquals(RuntimeState.Stopped, app.runtime.state.value)
            } finally { close.set(true); server.close(); worker.join(2000); stop() }
        }
    }
}

private class ReloadProxy : AutoCloseable {
    private val server = ServerSocket(0, 8, InetAddress.getByName("127.0.0.1")).apply { soTimeout = 500 }
    private val closed = AtomicBoolean(false)
    private val sockets = java.util.concurrent.ConcurrentHashMap.newKeySet<java.net.Socket>()
    val port get() = server.localPort
    val requests = AtomicInteger()
    private val worker = thread(isDaemon = true) {
        while (!closed.get()) {
            val socket = runCatching { server.accept() }.getOrNull() ?: continue
            sockets.add(socket)
            thread(isDaemon = true) { try { socket.use {
                it.soTimeout = 5000
                val reader = it.getInputStream().bufferedReader()
                var line = reader.readLine().orEmpty()
                while (!reader.readLine().isNullOrEmpty()) { }
                if (line.startsWith("CONNECT ")) {
                    it.getOutputStream().write("HTTP/1.1 200 Connection Established\r\n\r\n".toByteArray())
                    line = reader.readLine().orEmpty()
                    while (!reader.readLine().isNullOrEmpty()) { }
                }
                if (line.contains("/reload-")) requests.incrementAndGet()
                val body = "JEEMI_VPN_OK".toByteArray()
                it.getOutputStream().write(("HTTP/1.1 200 OK\r\nContent-Length: ${body.size}\r\nConnection: close\r\n\r\n").toByteArray() + body)
            } } catch (_: Exception) { } finally { sockets.remove(socket) } }
        }
    }
    override fun close() { closed.set(true); server.close(); sockets.forEach { runCatching { it.close() } }; worker.join(1500) }
}
