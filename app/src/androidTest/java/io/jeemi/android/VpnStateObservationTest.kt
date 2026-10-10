package io.jeemi.android

import android.content.ComponentName
import android.content.Intent
import android.view.accessibility.AccessibilityNodeInfo
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.platform.app.InstrumentationRegistry
import io.jeemi.android.data.BundledCore
import io.jeemi.android.domain.*
import io.jeemi.android.runtime.LiveSession
import io.jeemi.android.runtime.tunnelDescriptorActive
import io.jeemi.android.runtime.ownedTunnelActive
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import java.io.File

class VpnStateObservationTest {
    @get:Rule(order = 0) val library = TestLibrary { app ->
        val profile = app.engine.normalize("VPN state fixture",
            "proxy-groups: [{name: Route, type: select, proxies: [DIRECT]}]\nrules: ['MATCH,Route']")
        Library(listOf(profile), profile.id)
    }
    @get:Rule(order = 1) val compose = createAndroidComposeRule<MainActivity>()
    private val app get() = compose.activity.application as JeemiApplication
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    private fun label(id: Int) = compose.activity.getString(id)
    private fun eventually(timeout: Long = 15_000, condition: () -> Boolean) {
        val deadline = android.os.SystemClock.elapsedRealtime() + timeout
        while (!condition()) {
            check(android.os.SystemClock.elapsedRealtime() < deadline) { "state_timeout: ${app.runtime.state.value}" }
            Thread.sleep(100)
        }
    }
    private fun acceptConsent() {
        val root = instrumentation.uiAutomation.rootInActiveWindow ?: return
        if (root.packageName == "com.android.vpndialogs")
            root.findAccessibilityNodeInfosByViewId("android:id/button1").firstOrNull()?.performAction(AccessibilityNodeInfo.ACTION_CLICK)
        root.findAccessibilityNodeInfosByViewId("com.android.permissioncontroller:id/permission_deny_button")
            .firstOrNull()?.performAction(AccessibilityNodeInfo.ACTION_CLICK)
    }
    private fun startJeemi() {
        compose.waitUntil(15_000) { compose.onAllNodesWithText("VPN state fixture").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithContentDescription(label(R.string.vpn_start)).assertIsOff().performClick()
        eventually(35_000) { acceptConsent(); app.runtime.state.value is RuntimeState.Running || app.runtime.state.value is RuntimeState.Failed }
        assertTrue(app.runtime.state.value.toString(), app.runtime.state.value is RuntimeState.Running)
        compose.onNodeWithContentDescription(label(R.string.vpn_stop)).assertIsOn()
    }
    private fun competitor(stop: Boolean = false, inspect: Boolean = false) {
        compose.activityRule.scenario.onActivity { it.startActivity(Intent().setComponent(ComponentName(
            instrumentation.context.packageName, "io.jeemi.android.testprobe.CompetingVpnActivity"))
            .putExtra("stop", stop).putExtra("inspect", inspect)) }
    }
    private fun probeText(text: String) = instrumentation.uiAutomation.rootInActiveWindow
        ?.findAccessibilityNodeInfosByText(text)?.isNotEmpty() == true
    private fun returnToJeemi() {
        compose.activityRule.scenario.onActivity { it.startActivity(Intent(it, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_REORDER_TO_FRONT)) }
        eventually { compose.activityRule.scenario.state == androidx.lifecycle.Lifecycle.State.RESUMED }
        compose.waitUntil(15_000) { runCatching { compose.onAllNodes(isRoot()).fetchSemanticsNodes().isNotEmpty() }.getOrDefault(false) }
        compose.waitForIdle()
    }
    private fun assertClosed(failed: Boolean = false) {
        eventually { if (failed) app.runtime.state.value is RuntimeState.Failed else app.runtime.state.value == RuntimeState.Stopped }
        assertNull(app.runtime.live.value)
        assertNull(app.runtime.api)
        assertNull(app.runtime.dnsSession)
        assertNull(app.runtime.request)
        assertNull(app.runtime.service)
        assertTrue(File(app.noBackupFilesDir, "sessions").listFiles().orEmpty().isEmpty())
    }
    private fun assertControlsOff() {
        compose.onNodeWithContentDescription(label(R.string.vpn_start)).assertIsOff().assertIsEnabled()
        compose.onNodeWithContentDescription(label(R.string.vpn_quick_start)).assertIsEnabled()
    }
    @After fun cleanup() {
        compose.runOnUiThread { app.runtime.stop() }
        eventually { app.runtime.state.value == RuntimeState.Stopped || app.runtime.state.value is RuntimeState.Failed }
        competitor(stop = true)
        returnToJeemi()
    }

    @Test fun secondVpnTakesOverInBackgroundAndReturningDoesNotReclaimIt() {
        startJeemi()
        val oldApi = requireNotNull(app.runtime.api)
        val tun = File("/proc/self/fd").listFiles().orEmpty().single { fd ->
            runCatching { android.system.Os.readlink(fd.path) in listOf("/dev/tun", "/dev/net/tun") }.getOrDefault(false)
        }
        // Keep a dup alive across takeover: an open descriptor and a running
        // process alone cannot prove that the system still routes through it.
        android.os.ParcelFileDescriptor.fromFd(tun.name.toInt()).use { held ->
            assertTrue(ownedTunnelActive(held))
            competitor()
            eventually { acceptConsent(); probeText("TEST_VPN_ACTIVE") }
            assertClosed()
            assertFalse(ownedTunnelActive(held))
        }
        assertTrue(runCatching { oldApi.call("/configs") }.isFailure)
        returnToJeemi()
        assertControlsOff()
        compose.onNodeWithContentDescription(label(R.string.settings)).performClick()
        compose.onNodeWithText(label(R.string.permissions)).performClick()
        compose.onNodeWithText(label(R.string.vpn_permission_on_start)).assertIsDisplayed()
        Thread.sleep(2000)
        competitor(inspect = true)
        eventually { probeText("TEST_VPN_ACTIVE") }
        returnToJeemi()
        compose.onNodeWithContentDescription(label(R.string.back)).performClick()
        startJeemi() // Explicit action may take the VPN back through normal authorization.
        compose.runOnUiThread { app.runtime.stop() }
        assertClosed()
    }

    @Test fun externallyDisabledCoreTunStopsTheSessionEvenWhenCoreApiStillResponds() {
        startJeemi()
        val client = requireNotNull(app.runtime.api)
        instrumentation.uiAutomation.performGlobalAction(android.accessibilityservice.AccessibilityService.GLOBAL_ACTION_HOME)
        client.call("/configs", "PATCH", JSONObject().put("tun", JSONObject().put("enable", false)))
        assertFalse(client.call("/configs").getJSONObject("tun").getBoolean("enable"))
        assertClosed()
        returnToJeemi()
        assertControlsOff()
    }

    @Test fun terminatedCoreStopsControlsAndCanStartANewSession() {
        startJeemi()
        val oldSession = app.runtime.live.value!!.sessionId
        val executable = BundledCore(app).executable.canonicalPath
        val children = File("/proc").listFiles().orEmpty().filter { it.name.toIntOrNull() != null }
            .filter { runCatching { File(it, "exe").canonicalPath == executable }.getOrDefault(false) }
        assertEquals("Only this app's bundled core may be terminated", 1, children.size)
        android.os.Process.killProcess(children.single().name.toInt())
        assertClosed(failed = true)
        assertControlsOff()
        startJeemi()
        assertNotEquals(oldSession, app.runtime.live.value!!.sessionId)
    }

    @Test fun binderThreadRevocationCancelsAQueuedRestartAndCleansTheSession() {
        startJeemi()
        val owner = requireNotNull(app.runtime.service)
        compose.runOnUiThread {
            app.runtime.restart(requireNotNull(app.runtime.request).copy())
            // onRevoke is a Binder callback and can overlap a queued restart.
            kotlin.concurrent.thread { owner.onRevoke() }.join()
        }
        assertClosed()
        assertControlsOff()
        startJeemi()
    }

    @Test fun returningToForegroundClearsAnOrphanedRunningSnapshot() {
        startJeemi()
        val old = requireNotNull(app.runtime.live.value)
        compose.runOnUiThread { app.runtime.stop() }
        assertClosed()
        instrumentation.uiAutomation.performGlobalAction(android.accessibilityservice.AccessibilityService.GLOBAL_ACTION_HOME)
        eventually { compose.activityRule.scenario.state == androidx.lifecycle.Lifecycle.State.CREATED }
        // Simulate a stale in-memory snapshot without an owning service.
        compose.runOnUiThread {
            app.runtime.live.value = LiveSession(old.profileId, old.revision, old.geoRevision)
            app.runtime.state.value = RuntimeState.Running(old.revision)
        }
        returnToJeemi()
        assertClosed()
        assertControlsOff()
    }

    @Test fun descriptorObservationDoesNotConsumeTrafficAndDetectsClosedPeers() {
        val pipe = android.os.ParcelFileDescriptor.createPipe()
        pipe[0].use { input ->
            pipe[1].use { output ->
                android.system.Os.write(output.fileDescriptor, byteArrayOf(42), 0, 1)
                assertTrue(tunnelDescriptorActive(input.fileDescriptor))
                val received = ByteArray(1)
                assertEquals(1, android.system.Os.read(input.fileDescriptor, received, 0, 1))
                assertEquals(42, received.single().toInt())
            }
            assertFalse(tunnelDescriptorActive(input.fileDescriptor))
        }
        assertFalse(tunnelDescriptorActive(java.io.FileDescriptor()))
    }

    @Test fun nativeTunnelProbeRejectsUnrelatedAndInvalidDescriptorsWithoutClosingThem() {
        assertFalse(mobile.Mobile.androidTunnelActive(-1))
        File("/dev/null").inputStream().use { input ->
            android.os.ParcelFileDescriptor.dup(input.fd).use { unrelated ->
                assertFalse(ownedTunnelActive(unrelated))
                assertTrue(unrelated.fileDescriptor.valid())
            }
        }
    }
}
