package io.jeemi.android

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

/** Exercises the common authorization callback from a page other than Home. */
class HomeControlsTest {
    @get:Rule(order = 0) val library = TestLibrary { app ->
        val source = "proxies: [{name: Local fixture, type: http, server: 127.0.0.1, port: 9}]\n" +
            "proxy-groups: [{name: Route, type: select, proxies: [DIRECT, Local fixture]}]\nrules: ['MATCH,Route']"
        val profile = app.engine.normalize("Mobile controls", source).copy(icon = "🍀")
        Library(listOf(profile), profile.id)
    }
    @get:Rule(order = 1) val compose = createAndroidComposeRule<MainActivity>()
    private val app get() = compose.activity.application as JeemiApplication
    private fun label(id: Int) = compose.activity.getString(id)
    private fun eventually(condition: () -> Boolean) {
        val end = System.currentTimeMillis() + 35_000
        while (!condition()) { check(System.currentTimeMillis() < end) { "condition_timeout" }; Thread.sleep(100) }
    }
    @After fun stop() {
        compose.runOnUiThread { app.runtime.stop() }
        eventually { app.runtime.state.value == RuntimeState.Stopped || app.runtime.state.value is RuntimeState.Failed }
    }
    @Test fun startFromSubscriptionsRestartFromConfigurationAndStopWithHomeSwitch() {
        compose.waitUntil(15000) { compose.onAllNodesWithText("Mobile controls").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText("Mobile controls").performClick()
        compose.onNodeWithContentDescription(label(R.string.vpn_quick_start)).assertIsEnabled().performClick()
        val automation = InstrumentationRegistry.getInstrumentation().uiAutomation
        eventually {
            val root = automation.rootInActiveWindow
            if (root?.packageName == "com.android.vpndialogs")
                root.findAccessibilityNodeInfosByViewId("android:id/button1").firstOrNull()?.performAction(AccessibilityNodeInfo.ACTION_CLICK)
            root?.findAccessibilityNodeInfosByViewId("com.android.permissioncontroller:id/permission_deny_button")
                ?.firstOrNull()?.performAction(AccessibilityNodeInfo.ACTION_CLICK)
            app.runtime.state.value is RuntimeState.Running || app.runtime.state.value is RuntimeState.Failed
        }
        assertTrue("Global action did not start a real TUN: " + app.runtime.state.value, app.runtime.state.value is RuntimeState.Running)
        assertTrue(java.io.File(app.noBackupFilesDir, "sessions").listFiles()!!.single().resolve("config.yaml").readText().contains("find-process-mode: strict"))
        val first = app.runtime.live.value!!.sessionId
        compose.onNodeWithContentDescription(label(R.string.vpn_restart)).assertIsEnabled()
        compose.onNode(hasText(label(R.string.configuration)) and hasClickAction()).performClick()
        // A saved Home override must be picked up even when restarting on Configuration.
        val model = ViewModelProvider(compose.activity)[JeemiViewModel::class.java]
        compose.runOnUiThread { model.savePreferences(model.state.value.library.preferences.copy(mode = ProxyMode.DIRECT,
            runtimeJson = org.json.JSONObject(model.state.value.library.preferences.runtimeJson).put("findProcessMode", "off").toString())) }
        compose.waitUntil(15000) { !model.state.value.busy && model.state.value.library.preferences.mode == ProxyMode.DIRECT }
        compose.onNodeWithContentDescription(label(R.string.vpn_restart)).performClick()
        eventually { app.runtime.state.value is RuntimeState.Running && app.runtime.live.value?.sessionId != first }
        assertTrue(java.io.File(app.noBackupFilesDir, "sessions").listFiles()!!.single().resolve("config.yaml").readText().contains("find-process-mode: off"))
        assertEquals("direct", requireNotNull(app.runtime.api).call("/configs").getString("mode"))
        assertEquals(model.state.value.candidate?.revision, app.runtime.live.value?.revision)
        compose.onNode(hasText(label(R.string.more_navigation)) and hasClickAction()).performClick()
        compose.onNode(hasText(label(R.string.tools)) and hasClickAction()).performClick()
        compose.onNodeWithContentDescription(label(R.string.vpn_restart)).assertIsEnabled()
        compose.onNode(hasText(label(R.string.home)) and hasClickAction()).performClick()
        compose.onNodeWithContentDescription(label(R.string.vpn_stop)).assertIsOn().performClick()
        eventually { app.runtime.state.value == RuntimeState.Stopped }
        compose.onNodeWithContentDescription(label(R.string.vpn_start)).assertIsOff()
        compose.onNodeWithContentDescription(label(R.string.vpn_quick_start)).assertIsEnabled()
        assertNull(app.runtime.live.value)
        assertTrue(java.io.File(app.noBackupFilesDir, "sessions").listFiles().orEmpty().isEmpty())
    }
}
