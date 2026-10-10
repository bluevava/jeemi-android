package io.jeemi.android

import androidx.appcompat.app.AppCompatDelegate
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.core.os.LocaleListCompat
import androidx.lifecycle.ViewModelProvider
import androidx.test.core.app.ApplicationProvider
import androidx.test.platform.app.InstrumentationRegistry
import io.jeemi.android.domain.*
import io.jeemi.android.data.withBundledAppRouting
import io.jeemi.android.runtime.ConnectionFeed
import io.jeemi.android.runtime.ConnectionRecord
import io.jeemi.android.ui.JeemiViewModel
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.ExternalResource
import java.io.File

/** Public documentation captures: isolated synthetic data, actual Compose UI, no VPN/network. */
class PublicGuideCaptureTest {
    @get:Rule(order = 0) val locale = object : ExternalResource() {
        private var previous = LocaleListCompat.getEmptyLocaleList()
        private fun set(value: LocaleListCompat) {
            val app = ApplicationProvider.getApplicationContext<JeemiApplication>()
            if (android.os.Build.VERSION.SDK_INT >= 33) {
                app.getSystemService(android.app.LocaleManager::class.java).applicationLocales =
                    android.os.LocaleList.forLanguageTags(value.toLanguageTags())
            }
            AppCompatDelegate.setApplicationLocales(value)
        }
        override fun before() = InstrumentationRegistry.getInstrumentation().runOnMainSync {
            val app = ApplicationProvider.getApplicationContext<JeemiApplication>()
            previous = if (android.os.Build.VERSION.SDK_INT >= 33) LocaleListCompat.wrap(
                app.getSystemService(android.app.LocaleManager::class.java).applicationLocales)
                else AppCompatDelegate.getApplicationLocales()
            set(LocaleListCompat.forLanguageTags("zh-CN"))
        }
        override fun after() = InstrumentationRegistry.getInstrumentation().runOnMainSync { set(previous) }
    }
    @get:Rule(order = 1) val library = TestLibrary { app ->
        val profile = app.engine.normalize("示例订阅", """
            proxies:
              - {name: 香港 01, type: socks5, server: hk.example.invalid, port: 1080}
              - {name: 日本 01, type: socks5, server: jp.example.invalid, port: 1080}
              - {name: 新加坡 01, type: socks5, server: sg.example.invalid, port: 1080}
            proxy-groups:
              - {name: 原订阅选择器, type: select, proxies: [香港 01, 日本 01, 新加坡 01]}
            rules: ['MATCH,原订阅选择器']
        """.trimIndent()).copy(icon = "🌐", updatedAt = System.currentTimeMillis())
        val seeded = withBundledAppRouting(
            Library(listOf(profile), profile.id, preferences = Preferences(theme = AppTheme.LIGHT)), app, app.engine)
        // The test process may start before AppCompat attaches a delegate. Localize only
        // these synthetic resource names explicitly; their stable-ID references stay intact.
        val labels = listOf(R.string.bundled_routing_name, R.string.bundled_routing_selector,
            R.string.bundled_routing_direct_group, R.string.bundled_routing_block_group,
            R.string.bundled_routing_direct, R.string.bundled_routing_proxy, R.string.bundled_routing_block)
        val zh = app.createConfigurationContext(android.content.res.Configuration(app.resources.configuration).apply {
            setLocale(java.util.Locale.SIMPLIFIED_CHINESE)
        })
        seeded.copy(resources = seeded.resources.mapIndexed { index, resource -> resource.copy(name = zh.getString(labels[index])) })
    }
    @get:Rule(order = 2) val compose = createAndroidComposeRule<MainActivity>()
    private val app get() = compose.activity.application as JeemiApplication
    private val model get() = ViewModelProvider(compose.activity)[JeemiViewModel::class.java]
    private fun text(id: Int) = compose.activity.getString(id)
    private fun click(id: Int) = compose.onNode(hasText(text(id)) and hasClickAction()).performClick()
    private fun saved() {
        compose.waitUntil(60_000) { !model.state.value.busy }
        compose.waitForIdle()
        assertNull(model.state.value.error)
    }
    private fun capture(name: String) {
        compose.waitForIdle()
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        instrumentation.waitForIdleSync()
        Thread.sleep(300)
        val bitmap = requireNotNull(instrumentation.uiAutomation.takeScreenshot())
        val file = File(app.getExternalFilesDir(null), "guide-$name.png")
        file.outputStream().use { bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it) }
        bitmap.recycle()
        instrumentation.uiAutomation.executeShellCommand("cp ${file.absolutePath} /data/local/tmp/jeemi-guide-$name.png").use {
            java.io.FileInputStream(it.fileDescriptor).use { output -> output.readBytes() }
        }
    }

    @Test fun captureBuiltInRoutingWalkthrough() {
        org.junit.Assume.assumeTrue("Run only when explicitly capturing public documentation",
            InstrumentationRegistry.getArguments().getString("publicGuide") == "true")
        compose.waitUntil(20_000) { model.state.value.loaded }
        assertNull(model.state.value.error)
        compose.runOnUiThread { AppCompatDelegate.setApplicationLocales(LocaleListCompat.forLanguageTags("zh-CN")) }
        compose.waitUntil(15_000) { compose.activity.resources.configuration.locales[0].language == "zh" }
        val config = model.state.value.library.resources.single { it.kind == ResourceKind.CONFIG }
        val direct = model.state.value.library.resources.single { it.kind == ResourceKind.RULES && it.name == "直连" }
        val subscription = model.state.value.library.selected!!
        compose.onNodeWithTag("navigation-SUBSCRIPTIONS").performClick()
        compose.onAllNodesWithContentDescription(text(R.string.subscription_shelf))[0].performClick()
        compose.onNodeWithTag("binding-action-${subscription.id}-CONFIG").performScrollTo().performClick()
        compose.onNode(hasText(config.name) and hasClickAction()).performClick()
        capture("01-bind-config")
        click(R.string.save)
        saved()
        assertEquals(config.id, app.repository.load().selected!!.handlerId)
        compose.onNodeWithTag("binding-name-${subscription.id}-CONFIG").performScrollTo().assertTextContains(config.name)
        compose.onNode(hasText("代理选择器") and hasClickAction()).performClick()
        capture("02-subscription")

        compose.onNodeWithTag("navigation-HOME").performClick()
        capture("00-home")
        compose.onNode(hasText(text(R.string.runtime_preferences)) and hasClickAction()).performScrollTo().performClick()
        compose.onNode(hasText(text(R.string.runtime_basics)) and hasClickAction()).performScrollTo().performClick()
        compose.onNodeWithTag("runtime-findProcessMode").performScrollTo()
        compose.onNode(hasText(text(R.string.find_always)) and hasClickAction()).performClick()
        capture("03-process-mode")
        compose.onNodeWithTag("runtime-save").performClick()
        saved()
        assertTrue(model.state.value.candidate!!.yaml.contains("find-process-mode: always"))

        val connection = ConnectionRecord("guide-browser", "example.com", "203.0.113.10", "443",
            "172.19.0.1", "42000", "tcp", "TUN", "com.android.chrome", 10123,
            "Match", "", listOf("香港 01", "🚀 代理选择器"), 4096, 65536, "2026-10-10T01:41:00Z")
        compose.runOnIdle { model.diagnostics.connections.value = ConnectionFeed(rows = listOf(connection)) }
        click(R.string.more_navigation)
        click(R.string.connections)
        compose.onNodeWithTag("connection-details-guide-browser").assertExists()
        capture("04-connections")
        compose.onNodeWithTag("connection-details-guide-browser").performClick()
        capture("05-connection-details")
        compose.onNodeWithContentDescription(compose.activity.getString(
            R.string.add_connection_rule_for, text(R.string.application_package))).performClick()
        val directChoice = direct.name + " · " + text(R.string.app_rule_list)
        compose.onNode(hasText(directChoice) and hasClickAction()).performClick()
        capture("06-select-ruleset")
        compose.onAllNodes(hasText(directChoice) and hasClickAction()).onLast().performClick()
        compose.waitUntil(15_000) {
            compose.onAllNodesWithText("- 'PROCESS-NAME,com.android.chrome'").fetchSemanticsNodes().isNotEmpty()
        }
        capture("07-rule-preview")
        click(R.string.save)
        compose.waitUntil(60_000) {
            model.state.value.library.resources.any { it.id == direct.id && it.content.contains("PROCESS-NAME,com.android.chrome") }
                || model.state.value.error != null
        }
        saved()
        assertTrue(model.state.value.candidate!!.yaml.contains("PROCESS-NAME,com.android.chrome,DIRECT"))
        compose.onNodeWithContentDescription(text(R.string.back)).performClick()
        compose.onNodeWithTag("navigation-CONFIG").performClick()
        capture("09-configuration")
        val saved = model.state.value.library.resources.single { it.id == direct.id }
        compose.runOnIdle { model.openResource(saved) }
        compose.onNode(hasText(text(R.string.rule_source_options)) and hasClickAction()).performScrollTo().performClick()
        compose.onNode(hasText(text(R.string.name)) and hasSetTextAction()).performScrollTo()
        compose.waitUntil(15_000) { compose.onAllNodesWithText("Chrome").fetchSemanticsNodes().isNotEmpty() }
        capture("08-app-list")
        assertEquals("payload:\n  - 'PROCESS-NAME,com.android.chrome'\n", JSONObject(saved.content).getString("payloadYaml"))
        compose.runOnIdle { model.closeResource() }
    }
}
