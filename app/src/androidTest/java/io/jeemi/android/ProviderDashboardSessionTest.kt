package io.jeemi.android

import android.content.Intent
import android.view.accessibility.AccessibilityNodeInfo
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.lifecycle.ViewModelProvider
import androidx.test.platform.app.InstrumentationRegistry
import io.jeemi.android.domain.*
import io.jeemi.android.ui.JeemiViewModel
import org.junit.After
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import java.io.File
import java.net.HttpURLConnection
import java.net.Proxy
import java.net.URI

class ProviderDashboardSessionTest {
    @get:Rule(order = 0) val server = RuntimeResourceServer()
    @get:Rule(order = 1) val dashboard = InstalledDashboardFixture()
    @get:Rule(order = 2) val library = TestLibrary { app ->
        val profile = app.engine.normalize("Cache session", """
            proxies: [{name: Static.Test, type: socks5, server: node.example.invalid, port: 1081}]
            proxy-providers:
              Remote: {type: http, url: '${server.url}/nodes.yaml', interval: 86400}
            proxy-groups:
              - {name: Route, type: select, proxies: [DIRECT, Static.Test], use: [Remote]}
            rule-providers:
              Rules: {type: http, behavior: domain, url: '${server.url}/rules.yaml', interval: 86400}
            rules: ['RULE-SET,Rules,Route', 'MATCH,Route']
        """.trimIndent())
        Library(listOf(profile), profile.id, preferences = Preferences(externalUIVersion = dashboard.version))
    }
    @get:Rule(order = 3) val compose = createAndroidComposeRule<MainActivity>()
    private val app get() = compose.activity.application as JeemiApplication
    private val model get() = ViewModelProvider(compose.activity)[JeemiViewModel::class.java]
    private fun label(id: Int) = compose.activity.getString(id)
    private fun eventually(condition: () -> Boolean) {
        val end = System.currentTimeMillis() + 35_000
        while (!condition()) { check(System.currentTimeMillis() < end) { "condition_timeout" }; Thread.sleep(100) }
    }
    private fun start() {
        compose.onNodeWithContentDescription(label(R.string.vpn_start)).performClick()
        val automation = InstrumentationRegistry.getInstrumentation().uiAutomation
        eventually {
            val root = automation.rootInActiveWindow
            if (root?.packageName == "com.android.vpndialogs")
                root.findAccessibilityNodeInfosByViewId("android:id/button1").firstOrNull()?.performAction(AccessibilityNodeInfo.ACTION_CLICK)
            root?.findAccessibilityNodeInfosByViewId("com.android.permissioncontroller:id/permission_deny_button")
                ?.firstOrNull()?.performAction(AccessibilityNodeInfo.ACTION_CLICK)
            app.runtime.state.value is RuntimeState.Running || app.runtime.state.value is RuntimeState.Failed
        }
        assertTrue("A real TUN did not start", app.runtime.state.value is RuntimeState.Running)
    }
    @After fun stop() {
        compose.runOnUiThread { app.runtime.stop() }
        eventually { app.runtime.state.value == RuntimeState.Stopped || app.runtime.state.value is RuntimeState.Failed }
    }
    private fun address() = app.runtime.dashboardAddress(model.state.value.library.selectedId,
        model.state.value.candidate!!.revision, model.state.value.geo.joinToString(":") { it.sha256 })
    private fun get(address: String): Pair<Int, String> {
        val connection = URI(address).toURL().openConnection(Proxy.NO_PROXY) as HttpURLConnection
        return try {
            connection.connectTimeout = 2000; connection.readTimeout = 2000
            connection.responseCode to if (connection.responseCode == 200) connection.inputStream.bufferedReader().readText() else ""
        } finally { connection.disconnect() }
    }
    @Test fun providersSurviveRestartAndDashboardUsesOnlyCurrentPrivateSession() {
        compose.waitUntil(20_000) { model.state.value.loaded }
        compose.onNodeWithContentDescription(label(R.string.external_ui)).performClick()
        compose.waitUntil(20_000) { !model.state.value.dashboardDownloading && model.state.value.library.preferences.externalUIEnabled }
        compose.onNodeWithText(label(R.string.open_zashboard)).assertIsNotEnabled()
        start()
        try {
            eventually { server.rules.get() >= 1 && server.nodes.get() >= 1 && app.runtime.live.value!!.proxies.containsKey("Remote.Test") }
        } catch (error: Exception) {
            throw AssertionError("Fixture counts: rules=${server.rules.get()}, nodes=${server.nodes.get()}, leaf=${app.runtime.live.value?.proxies?.containsKey("Remote.Test")}, membership=${app.runtime.live.value?.proxies?.get("Route")?.members?.contains("Remote.Test")}", error)
        }
        compose.onNodeWithText(label(R.string.open_zashboard)).assertIsEnabled()
        eventually {
            app.runtime.api!!.call("/providers/rules").getJSONObject("providers").getJSONObject("Rules").optInt("ruleCount") > 0
        }
        val firstRules = server.rules.get()
        val firstNodes = server.nodes.get()
        val first = URI(address())
        assertNull(first.rawQuery)
        assertTrue(first.rawFragment.startsWith("/setup?"))
        assertEquals("127.0.0.1", first.host)
        assertTrue(get(first.toString()).second.contains("JEEMI_DASHBOARD_OK"))
        assertEquals(401, get("http://127.0.0.1:${first.port}/proxies").first)
        // Verify the real button launches the browser without recording its
        // session credential. Chrome's first-run screen is sufficient here;
        // the authenticated local server is checked independently above.
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        compose.onNodeWithText(label(R.string.open_zashboard)).performClick()
        eventually { instrumentation.uiAutomation.rootInActiveWindow?.packageName == "com.android.chrome" }
        instrumentation.targetContext.startActivity(Intent(instrumentation.targetContext, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_REORDER_TO_FRONT))
        eventually { instrumentation.uiAutomation.rootInActiveWindow?.packageName == instrumentation.targetContext.packageName }
        stop()
        assertTrue(File(app.noBackupFilesDir, "sessions").listFiles().orEmpty().isEmpty())
        assertTrue(File(app.noBackupFilesDir, "provider-cache").listFiles().orEmpty().size >= 2)
        server.online = false
        start()
        eventually { app.runtime.live.value!!.proxies.containsKey("Remote.Test") }
        assertEquals(firstRules, server.rules.get())
        assertEquals(firstNodes, server.nodes.get())
        assertTrue("A restart must rotate session credentials", first.toString() != address())
        assertTrue(get(address()).second.contains("JEEMI_DASHBOARD_OK"))
        compose.onNodeWithContentDescription(label(R.string.external_ui)).performClick()
        compose.waitUntil(20_000) { !model.state.value.busy && !model.state.value.library.preferences.externalUIEnabled }
        compose.onNodeWithText(label(R.string.open_zashboard)).assertDoesNotExist()
        val oldSession = app.runtime.live.value!!.sessionId
        compose.runOnUiThread { model.restartVpn() }
        eventually { app.runtime.state.value is RuntimeState.Running && app.runtime.live.value?.sessionId != oldSession }
        val config = File(app.noBackupFilesDir, "sessions").listFiles()!!.single().resolve("config.yaml").readText()
        assertFalse(config.contains("external-ui:"))
        assertTrue(File(app.noBackupFilesDir, "runtime/mihomo/external-ui/zashboard/${dashboard.version}/dist/index.html").isFile)
        assertEquals(firstRules, server.rules.get())
        assertEquals(firstNodes, server.nodes.get())
    }
}
