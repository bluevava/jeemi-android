package io.jeemi.android

import android.net.VpnService
import android.view.accessibility.AccessibilityNodeInfo
import androidx.lifecycle.ViewModelProvider
import androidx.test.ext.junit.rules.ActivityScenarioRule
import androidx.test.platform.app.InstrumentationRegistry
import io.jeemi.android.data.SubscriptionFetcher
import io.jeemi.android.data.directSubscriptionRoute
import io.jeemi.android.domain.*
import io.jeemi.android.runtime.VpnRequest
import io.jeemi.android.ui.JeemiViewModel
import mobile.Mobile
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.ExternalResource
import java.io.IOException
import java.net.URL

class SubscriptionDirectRetryTest {
    private val host get() = "http://10.0.2.2:" + InstrumentationRegistry.getArguments().getString("subscriptionPort").also { require(!it.isNullOrBlank()) }
    // The external fixture makes the ordinary connection traverse the TUN;
    // device loopback would bypass it and give a false-positive fallback test.
    @get:Rule(order = 0) val fixture = object : ExternalResource() {
        override fun before() {
            assumeTrue("Requires scripts/dev.py device-subscription-test --serial <emulator>",
                !InstrumentationRegistry.getArguments().getString("subscriptionPort").isNullOrBlank())
        }
    }
    @get:Rule(order = 1) val library = TestLibrary { app ->
        val subscriptions = listOf("ok", "fail", "invalid", "slow").map { kind ->
            app.engine.normalize("Saved $kind", "rules: ['MATCH,REJECT']\n").copy(
                sourceUrl = "$host/transaction/$kind?token=fixture%2Bvalue", icon = "🌐", updatedAt = 1,
                uploadedBytes = 1, downloadedBytes = 2, totalBytes = 10)
        }
        Library(subscriptions, subscriptions.first().id, preferences = Preferences(tunStack = TunStack.SYSTEM,
            runtimeJson = JSONObject(Mobile.runtimeDefaults()).put("lanBypassRules", JSONArray()).toString()))
    }
    @get:Rule(order = 2) val activityRule = ActivityScenarioRule(MainActivity::class.java)
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    private lateinit var app: JeemiApplication
    private lateinit var model: JeemiViewModel
    private fun eventually(timeout: Long = 20_000, condition: () -> Boolean) {
        val until = System.currentTimeMillis() + timeout
        while (!condition()) { check(System.currentTimeMillis() < until) { "condition_timeout" }; Thread.sleep(50) }
    }
    private fun initialize() {
        activityRule.scenario.onActivity { app = it.application as JeemiApplication; model = ViewModelProvider(it)[JeemiViewModel::class.java] }
        eventually { model.state.value.loaded }
    }
    private fun start(stack: TunStack) {
        activityRule.scenario.onActivity {
            val consent = VpnService.prepare(it)
            if (consent != null) it.startActivityForResult(consent, 99)
        }
        eventually {
            instrumentation.uiAutomation.rootInActiveWindow?.findAccessibilityNodeInfosByViewId("android:id/button1")
                ?.firstOrNull()?.performAction(AccessibilityNodeInfo.ACTION_CLICK)
            VpnService.prepare(app) == null
        }
        val saved = model.state.value.library
        val candidate = app.engine.project(requireNotNull(saved.selected), saved.preferences.copy(tunStack = stack), saved.resources)
        activityRule.scenario.onActivity { app.runtime.start(VpnRequest(requireNotNull(saved.selectedId), candidate.yaml, true,
            revision = candidate.revision, tunStack = stack)) }
        eventually(35_000) { app.runtime.state.value is RuntimeState.Running || app.runtime.state.value is RuntimeState.Failed }
        assertTrue(app.runtime.state.value.toString(), app.runtime.state.value is RuntimeState.Running)
    }
    private fun stop() {
        activityRule.scenario.onActivity { app.runtime.stop() }
        eventually { app.runtime.state.value == RuntimeState.Stopped }
    }
    private fun directGet(path: String): String = SubscriptionFetcher(
        openConnection = requireNotNull(directSubscriptionRoute(app, app.runtime))).fetch(host + path)
    private fun requests(path: String) = JSONObject(directGet("/counts")).optInt(path)

    @Test fun systemAndMixedRetryOverPhysicalNetworkWithoutChangingVpnOrProxyMode() {
        initialize()
        try {
            for (stack in listOf(TunStack.SYSTEM, TunStack.MIXED)) {
                start(stack)
                val session = requireNotNull(app.runtime.live.value).sessionId
                val path = "/route-${stack.name}/redirect?token=fixture%2Bvalue"
                // MATCH,REJECT blocks this app's ordinary request via the TUN.
                assertThrows(IOException::class.java) { SubscriptionFetcher().fetch(host + path) }
                val fetcher = app.subscriptionFetcher()
                assertEquals("rules: ['MATCH,DIRECT']\n", fetcher.fetch(host + path))
                assertEquals("Downloaded title", fetcher.suggestedName)
                assertEquals(1, requests("/route-${stack.name}/redirect"))
                assertEquals(1, requests("/route-${stack.name}/ok"))
                assertEquals(session, app.runtime.live.value?.sessionId)
                assertTrue(app.runtime.state.value is RuntimeState.Running)
                assertEquals("rule", requireNotNull(app.runtime.api).call("/configs").getString("mode"))
                val oldRoute = requireNotNull(directSubscriptionRoute(app, app.runtime))
                stop()
                assertNull(directSubscriptionRoute(app, app.runtime))
                assertThrows(IOException::class.java) { oldRoute(URL(host + "/unused")) }
            }
        } finally { stop() }
    }

    @Test fun refreshCommitsOnlyValidatedResponseAndKeepsOldLibraryOnFailureOrCancel() {
        initialize()
        try {
            start(TunStack.SYSTEM)
            val session = app.runtime.live.value?.sessionId
            val before = model.state.value.library
            val first = before.subscriptions.first()
            activityRule.scenario.onActivity { model.refresh(first.id) }
            eventually(40_000) { !model.state.value.busy && !model.state.value.downloadBusy }
            assertNull(model.state.value.error)
            val updated = app.repository.load().subscriptions.first()
            assertEquals(first.id, updated.id); assertEquals(first.name, updated.name)
            assertEquals(first.sourceUrl, updated.sourceUrl); assertEquals(first.icon, updated.icon)
            assertEquals("rules: ['MATCH,DIRECT']\n", updated.original)
            assertEquals(20L, updated.downloadedBytes); assertTrue(updated.updatedAt > first.updatedAt)
            assertEquals(before.selectedId, app.repository.load().selectedId)
            assertEquals(session, app.runtime.live.value?.sessionId)
            val good = app.repository.load()
            for (kind in listOf("fail", "invalid", "slow")) {
                val item = good.subscriptions.first { it.name == "Saved $kind" }
                activityRule.scenario.onActivity { model.refresh(item.id) }
                if (kind == "slow") {
                    eventually { requests("/transaction/slow") == 1 }
                    activityRule.scenario.onActivity { model.cancelDownload() }
                }
                eventually(40_000) { !model.state.value.busy && !model.state.value.downloadBusy }
                if (kind == "slow") assertNull(model.state.value.error) else assertNotNull(model.state.value.error)
                assertEquals(good, app.repository.load())
                assertEquals(good, model.state.value.library)
                assertEquals(1, requests("/transaction/$kind"))
                assertEquals(session, app.runtime.live.value?.sessionId)
                assertTrue(app.runtime.state.value is RuntimeState.Running)
            }
            activityRule.scenario.onActivity { model.importUrl("Imported directly", "$host/transaction/import?token=fixture%2Bvalue", icon = "🌐") }
            eventually(40_000) { !model.state.value.busy && !model.state.value.downloadBusy }
            assertNull(model.state.value.error)
            val imported = app.repository.load()
            assertEquals(good.subscriptions.size + 1, imported.subscriptions.size)
            assertEquals("rules: ['MATCH,DIRECT']\n", imported.subscriptions.last().original)
            assertEquals(good.selectedId, imported.selectedId)
            assertEquals(1, requests("/transaction/import"))
            assertEquals(session, app.runtime.live.value?.sessionId)
        } finally { stop() }
    }
}
