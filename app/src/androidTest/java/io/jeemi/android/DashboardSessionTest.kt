package io.jeemi.android

import android.net.VpnService
import android.view.accessibility.AccessibilityNodeInfo
import androidx.test.ext.junit.rules.ActivityScenarioRule
import androidx.test.platform.app.InstrumentationRegistry
import io.jeemi.android.domain.*
import io.jeemi.android.runtime.VpnRequest
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.io.File
import java.net.HttpURLConnection
import java.net.Proxy
import java.net.URL
import java.security.MessageDigest
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

class DashboardSessionTest {
    @get:Rule(order = 0) val library = TestLibrary()
    @get:Rule(order = 1) val activityRule = ActivityScenarioRule(MainActivity::class.java)
    private fun eventually(timeout: Long = 30_000, condition: () -> Boolean) {
        val end = System.currentTimeMillis() + timeout
        while (!condition()) { check(System.currentTimeMillis() < end) { "dashboard_test_timeout" }; Thread.sleep(100) }
    }
    private fun digest(bytes: ByteArray) = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }

    @Test fun offlineRepairServesHtmlScriptsAndStylesFromTheActualCoreAfterRestart() {
        lateinit var activity: MainActivity
        activityRule.scenario.onActivity { activity = it }
        val app = activity.application as JeemiApplication
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val version = "v0.0." + (System.currentTimeMillis() % 1_000_000_000)
        val root = File(app.noBackupFilesDir, "runtime/mihomo/external-ui/zashboard")
        val installed = File(root, version)
        check(!installed.exists())
        val files = mapOf("index.html" to "<html><script src=\"./assets/app.js\"></script><link rel=\"stylesheet\" href=\"./assets/app.css\"></html>",
            "assets/app.js" to "window.jeemiFixture=true;", "assets/app.css" to "body { color: purple; }")
        val archive = ByteArrayOutputStream().apply {
            ZipOutputStream(this).use { zip -> files.forEach { (name, value) ->
                zip.putNextEntry(ZipEntry("dist/$name")); zip.write(value.toByteArray()); zip.closeEntry()
            } }
        }.toByteArray()
        try {
            files.forEach { (name, value) -> File(installed, "dist/$name").apply { parentFile!!.mkdirs(); writeText(value) } }
            File(installed, "archive.zip").writeBytes(archive)
            File(installed, "metadata.json").writeText(JSONObject()
                .put("release", JSONObject().put("version", version).put("size", archive.size).put("sha256", digest(archive)))
                .put("files", JSONObject(files.mapValues { it.value.toByteArray().size }))
                .put("sha256", JSONObject(files.mapValues { digest(it.value.toByteArray()) })).toString())
            // Simulate a partially missing installation. Startup must repair offline.
            check(File(installed, "dist/assets/app.js").delete())
            VpnService.prepare(activity)?.let { consent ->
                activityRule.scenario.onActivity { it.startActivityForResult(consent, 99) }
                eventually {
                    instrumentation.uiAutomation.rootInActiveWindow?.findAccessibilityNodeInfosByViewId("android:id/button1")
                        ?.firstOrNull()?.performAction(AccessibilityNodeInfo.ACTION_CLICK)
                    VpnService.prepare(activity) == null
                }
            }
            val profile = app.engine.normalize("Dashboard fixture", "rules: ['MATCH,DIRECT']\n")
            val preferences = Preferences(externalUIEnabled = true, externalUIVersion = version)
            val candidate = app.engine.project(profile, preferences, emptyList())
            val request = VpnRequest(profile.id, candidate.yaml, false, candidate.revision, externalUIVersion = version)
            repeat(2) { attempt ->
                if (attempt == 0) activityRule.scenario.onActivity { app.runtime.start(request) }
                else {
                    val file = File(installed, "dist/assets/app.js")
                    file.writeBytes(ByteArray(file.length().toInt()) { 'x'.code.toByte() })
                    activityRule.scenario.onActivity { app.runtime.restart(request) }
                }
                eventually { app.runtime.state.value is RuntimeState.Running || app.runtime.state.value is RuntimeState.Failed }
                assertTrue("dashboard VPN startup failed", app.runtime.state.value is RuntimeState.Running)
                val address = app.runtime.dashboardAddress(profile.id, candidate.revision, app.runtime.live.value!!.geoRevision)
                val base = address.substringBefore('#')
                assertNull(URL(base).query)
                files.forEach { (name, expected) ->
                    val connection = URL(base + name).openConnection(Proxy.NO_PROXY) as HttpURLConnection
                    try {
                        connection.connectTimeout = 3000; connection.readTimeout = 3000
                        assertEquals(200, connection.responseCode)
                        assertEquals(expected, connection.inputStream.bufferedReader().use { it.readText() })
                    } finally { connection.disconnect() }
                }
                assertEquals(files.getValue("assets/app.js"), File(installed, "dist/assets/app.js").readText())
            }
        } finally {
            activityRule.scenario.onActivity { app.runtime.stop() }
            eventually { app.runtime.state.value == RuntimeState.Stopped }
            check(installed.canonicalFile.parentFile == root.canonicalFile)
            installed.deleteRecursively()
        }
    }
}
