package io.jeemi.android

import android.graphics.Bitmap
import androidx.appcompat.app.AppCompatDelegate
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.core.os.LocaleListCompat
import androidx.lifecycle.ViewModelProvider
import androidx.test.platform.app.InstrumentationRegistry
import io.jeemi.android.domain.*
import io.jeemi.android.runtime.*
import io.jeemi.android.ui.JeemiViewModel
import mobile.Mobile
import org.json.JSONObject
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import java.io.File

/** Actual Compose screenshots with explicitly synthetic diagnostic data. */
class DiagnosticsVisualTest {
    @get:Rule(order = 0) val library = TestLibrary { app ->
        val names = (1..30).map { "🇯🇵 Japan · Node %02d".format(it) }
        val source = "proxies:\n" + names.joinToString("\n") { " - {name: '$it', type: ss, server: 127.0.0.1, port: 9, cipher: aes-128-gcm, password: fixture}" } +
            "\nproxy-groups:\n - name: 🌏 Proxy\n   type: select\n   proxies: [" + names.joinToString(",") { "'$it'" } + "]\nrules: ['MATCH,🌏 Proxy']"
        val config = LocalResource("a".repeat(32), "Mobile rules", ResourceKind.CONFIG, "sniffer: {enable: true}")
        val script = LocalResource("b".repeat(32), "Profile script", ResourceKind.SCRIPT, "function main(config) { return config; }")
        val profile = app.engine.normalize("Jeemi Mobile", source).copy(icon = "🌐", sourceUrl = "http://127.0.0.1:9/fixture",
            updatedAt = System.currentTimeMillis() - 125000, uploadedBytes = 1073741824, downloadedBytes = 22548578304,
            totalBytes = 107374182400, expiresAt = 1798732800, ruleProviderCount = 4, handlerId = config.id)
        val backup = app.engine.normalize("Backup subscription with a long name", source).copy(icon = "🍀", sourceUrl = "http://127.0.0.1:9/fixture",
            updatedAt = System.currentTimeMillis() - 10800000, uploadedBytes = 0, downloadedBytes = 536870912,
            totalBytes = 53687091200, expiresAt = 1796054400, ruleProviderCount = 2)
        val scripted = app.engine.normalize("Script subscription", source).copy(icon = "📜", sourceUrl = "http://127.0.0.1:9/fixture",
            updatedAt = System.currentTimeMillis() - 172800000, ruleProviderCount = 1, handlerId = script.id)
        Library(listOf(profile, backup, scripted), profile.id, Preferences(nodeDensity = NodeDensity.SMALL,
            runtimeJson = JSONObject(Mobile.runtimeDefaults()).put("logLevel", "info").toString()), listOf(config, script))
    }
    @get:Rule(order = 1) val compose = createAndroidComposeRule<MainActivity>()
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    private var previousDnsPreferences: Map<String, *> = emptyMap<String, String>()
    @Before fun seedDnsPreferences() {
        val preferences = compose.activity.getSharedPreferences("dns-diagnostics", 0)
        previousDnsPreferences = preferences.all
        check(preferences.edit().clear().putString("proxy", "8.8.8.8").putString("direct", "223.5.5.5")
            .putString("custom", "1.1.1.1").putString("customProxy", "false").commit())
    }
    private fun label(id: Int) = compose.activity.getString(id)
    private fun language(tag: String) {
        compose.activityRule.scenario.onActivity { AppCompatDelegate.setApplicationLocales(LocaleListCompat.forLanguageTags(tag)) }
        compose.waitUntil(15000) { compose.activity.resources.configuration.locales[0].language == tag.substringBefore('-') }
    }
    private fun capture(name: String) {
        compose.waitForIdle(); instrumentation.waitForIdleSync()
        // Dialog window fades run outside the Compose test clock.
        Thread.sleep(350)
        val file = File(compose.activity.getExternalFilesDir(null), "diagnostics-$name.png")
        val bitmap = requireNotNull(instrumentation.uiAutomation.takeScreenshot())
        file.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }; bitmap.recycle()
        instrumentation.uiAutomation.executeShellCommand("cp " + file.absolutePath + " /data/local/tmp/jeemi-diagnostics-$name.png").use {
            java.io.FileInputStream(it.fileDescriptor).use { output -> output.readBytes() }
        }
    }
    private fun more(destination: Int) {
        compose.onNode(hasText(label(R.string.more_navigation)) and hasClickAction()).performClick()
        compose.onNode(hasText(label(destination)) and hasClickAction()).performClick()
    }
    @After fun restore() {
        compose.runOnUiThread {
            (compose.activity.application as JeemiApplication).runtime.clear(false)
        }
        language("en")
        val editor = compose.activity.getSharedPreferences("dns-diagnostics", 0).edit().clear()
        previousDnsPreferences.forEach { (key, value) -> editor.putString(key, value as String) }
        check(editor.commit())
    }
    @Test fun captureCompactNodesAndDiagnosticPages() {
        compose.waitUntil(15000) { compose.onAllNodesWithText(label(R.string.runtime_stopped)).fetchSemanticsNodes().isNotEmpty() }
        language("zh-CN")
        capture("home-stopped-zh")
        compose.runOnIdle {
            val model = ViewModelProvider(compose.activity)[JeemiViewModel::class.java]
            val state = model.state.value; val profile = state.library.selected!!
            val proxies = profile.nodes.mapIndexed { i, node -> node.name to LiveProxy("Shadowsocks", "", emptyList(), listOf(65, 175, 265, 550)[i % 4]) }.toMap() +
                ("🌏 Proxy" to LiveProxy("Selector", profile.nodes.first().name, profile.nodes.map { it.name }, null))
            val app = compose.activity.application as JeemiApplication
            app.runtime.live.value = LiveSession(profile.id, state.candidate!!.revision, state.geo.joinToString(":") { it.sha256 }, proxies)
            app.runtime.state.value = RuntimeState.Running(state.candidate.revision)
            model.diagnostics.connections.value = ConnectionFeed(rows = (1..12).map {
                ConnectionRecord("fixture-$it", "service-$it.example.com", "203.0.113.$it", "443", "172.19.0.1", (43000 + it).toString(),
                    "tcp", "TUN", "org.example.browser", 12345, "ProcessName", "org.example.browser", listOf("🇯🇵 Japan · Node 01", "🌏 Proxy"), 1024L * it, 4096L * it, "2026-09-09T08:30:00Z")
            })
            model.diagnostics.logs.value = LogFeed(rows = (1L..12).map { CoreLog(it, if (it % 3 == 0L) "warning" else "info", "[TCP] org.example.browser → service-$it.example.com:443 using 🌏 Proxy", "16:30:0" + it % 10) })
        }
        capture("home-running-zh")
        language("en"); capture("home-running-en"); language("zh-CN")
        compose.onNodeWithText("Jeemi Mobile").performClick()
        capture("subscriptions-collapsed-zh")
        compose.onNodeWithContentDescription("🌏 Proxy").performClick(); capture("nodes-zh")
        compose.onAllNodesWithContentDescription(label(R.string.subscription_shelf))[0].performClick()
        capture("subscriptions-expanded-zh")
        compose.onNodeWithText("Backup subscription with a long name").performClick()
        compose.waitUntil(10000) { (compose.activity.application as JeemiApplication).repository.load().selected?.name == "Backup subscription with a long name" }
        capture("subscriptions-selected-zh")
        compose.onNodeWithText(label(R.string.associate_subscription_handler)).performClick()
        compose.onNodeWithContentDescription(label(R.string.back)).performClick()
        compose.onNodeWithText("Jeemi Mobile").performClick()
        compose.waitUntil(10000) { (compose.activity.application as JeemiApplication).repository.load().selected?.name == "Jeemi Mobile" }
        language("en"); capture("subscriptions-expanded-en")
        compose.onNodeWithText(label(R.string.collapse_subscription_panel)).performClick()
        capture("subscriptions-collapsed-en")
        if (InstrumentationRegistry.getArguments().getString("layoutOnly") == "true") return
        language("zh-CN")
        compose.onNode(hasText(label(R.string.more_navigation)) and hasClickAction()).performClick(); capture("more-zh")
        compose.onNode(hasText(label(R.string.connections)) and hasClickAction()).performClick(); capture("connections-zh")
        compose.onNodeWithTag("connection-details-fixture-1").performClick(); capture("details-zh")
        compose.onNodeWithContentDescription(label(R.string.back)).performClick()
        more(R.string.logs); capture("logs-zh")
        more(R.string.tools); capture("dns-zh")
        language("en")
        capture("dns-en")
    }
}
