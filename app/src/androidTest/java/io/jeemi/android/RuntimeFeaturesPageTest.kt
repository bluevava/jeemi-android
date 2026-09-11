package io.jeemi.android

import android.graphics.Bitmap
import androidx.appcompat.app.AppCompatDelegate
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.core.os.LocaleListCompat
import androidx.lifecycle.ViewModelProvider
import androidx.test.platform.app.InstrumentationRegistry
import io.jeemi.android.domain.*
import io.jeemi.android.runtime.LiveProxy
import io.jeemi.android.runtime.LiveSession
import io.jeemi.android.ui.JeemiViewModel
import org.junit.After
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import java.io.File

class RuntimeFeaturesPageTest {
    @get:Rule(order = 0) val library = TestLibrary { app ->
        val profile = app.engine.normalize("Runtime features", """
            proxies:
              - {name: HK.Test, type: socks5, server: node.example.invalid, port: 1080}
              - {name: JP.Test, type: socks5, server: node.example.invalid, port: 1081}
            proxy-groups:
              - {name: '🌐 Route', type: select, proxies: ['🏡 Region', DIRECT]}
              - {name: '🏡 Region', type: select, hidden: true, proxies: ['⚡ Auto', HK.Test, JP.Test]}
              - {name: '⚡ Auto', type: url-test, hidden: true, proxies: [HK.Test, JP.Test], url: 'https://example.invalid', interval: 300}
            rules: ['MATCH,🌐 Route']
        """.trimIndent())
        Library(listOf(profile), profile.id)
    }
    @get:Rule(order = 1) val compose = createAndroidComposeRule<MainActivity>()
    private val model get() = ViewModelProvider(compose.activity)[JeemiViewModel::class.java]
    private fun label(id: Int) = compose.activity.getString(id)
    private fun language(tag: String) {
        compose.runOnUiThread { AppCompatDelegate.setApplicationLocales(LocaleListCompat.forLanguageTags(tag)) }
        compose.waitUntil(15_000) { compose.activity.resources.configuration.locales[0].language == tag.substringBefore('-') }
        compose.waitForIdle()
    }
    @After fun restoreLanguage() {
        compose.runOnUiThread {
            val runtime = (compose.activity.application as JeemiApplication).runtime
            runtime.live.value = null
            runtime.state.value = RuntimeState.Stopped
        }
        language("en")
    }

    @Test fun hiddenNestedNavigationSelectionAndTitlesWorkInBothLanguages() {
        language("zh-CN")
        compose.waitUntil(20_000) { model.state.value.loaded }
        compose.onNode(hasText(label(R.string.subscriptions)) and hasClickAction()).performClick()
        compose.onNodeWithContentDescription("🏡 Region").assertDoesNotExist()
        compose.onNodeWithContentDescription("🌐 Route").performClick()
        compose.onNodeWithTag("selector-breadcrumb").assertDoesNotExist()
        capture("root-zh")
        compose.onNodeWithContentDescription(compose.activity.getString(R.string.open_nested_selector, "🏡 Region")).performClick()
        compose.onNodeWithTag("selector-breadcrumb").assertIsDisplayed()
        assertTrue(model.state.value.library.selected!!.selections.isEmpty())
        compose.onNodeWithText("JP.Test").performClick()
        compose.waitUntil(15_000) { model.state.value.library.selected!!.selections["🏡 Region"] == "JP.Test" }
        compose.onNodeWithText(compose.activity.getString(R.string.selector_preselected, "JP.Test")).assertIsDisplayed()
        compose.onNodeWithText(compose.activity.getString(R.string.selector_preselected, "🏡 Region · JP.Test")).assertDoesNotExist()
        capture("nested-zh")
        language("en")
        compose.onNodeWithText("JP.Test").assertIsDisplayed()
        capture("nested-en")
        compose.onNodeWithContentDescription("🌐 Route").performTouchInput { longClick() }
        compose.onNodeWithText(label(R.string.egress_node)).assertIsDisplayed()
        compose.onNodeWithText("🏡 Region · JP.Test").assertDoesNotExist()
        compose.onNodeWithText(label(R.string.close)).performClick()
        compose.onNode(hasText(label(R.string.home)) and hasClickAction()).performClick()
        compose.onNode(hasText(label(R.string.subscriptions)) and hasClickAction()).performClick()
        // The expanded flag is local to the page, while the browsed path is retained.
        if (compose.onAllNodesWithText("JP.Test").fetchSemanticsNodes().isEmpty())
            compose.onNodeWithContentDescription("🌐 Route").performClick()
        compose.onNodeWithText("JP.Test").assertIsDisplayed()
        compose.onNode(hasText("🌐 Route") and hasClickAction()).performClick()
        compose.onNodeWithTag("selector-breadcrumb").assertDoesNotExist()
        capture("root-en")
    }

    @Test fun homeSwitchAndScriptUrlEditorKeepBilingualHelpAndDraft() {
        language("zh-CN")
        compose.waitUntil(20_000) { model.state.value.loaded }
        compose.onNodeWithText(label(R.string.external_ui)).assertIsDisplayed()
        compose.onNodeWithText(label(R.string.open_zashboard)).assertDoesNotExist()
        capture("home-zh")
        language("en")
        compose.onNodeWithText(label(R.string.external_ui)).assertIsDisplayed()
        capture("home-en")
        compose.onNode(hasText(label(R.string.configuration)) and hasClickAction()).performClick()
        compose.runOnUiThread { model.openResource(LocalResource("a".repeat(32), "URL example", ResourceKind.SCRIPT,
            "function main(config) { return config; }", formatVersion = 2)) }
        compose.onNodeWithText(label(R.string.script_source_input)).performTextReplacement("https://example.invalid/script.js")
        compose.onNodeWithText("https://example.invalid/script.js").assertExists()
        language("zh-CN")
        compose.onNodeWithText("https://example.invalid/script.js").assertExists()
        capture("script-zh")
        language("en")
        capture("script-en")
        assertEquals("https://example.invalid/script.js", model.scriptInput.value)
    }

    @Test fun nestedBadgeUsesCurrentLeafLatencyAndRejectsStaleSessionValues() {
        language("zh-CN")
        compose.waitUntil(20_000) { model.state.value.loaded }
        val runtime = (compose.activity.application as JeemiApplication).runtime
        val current = model.state.value
        val proxies = mapOf(
            "🌐 Route" to LiveProxy("Selector", "🏡 Region", listOf("🏡 Region", "DIRECT"), 777),
            "🏡 Region" to LiveProxy("Selector", "⚡ Auto", listOf("⚡ Auto", "HK.Test", "JP.Test"), 999),
            "⚡ Auto" to LiveProxy("URLTest", "JP.Test", listOf("HK.Test", "JP.Test"), 888),
            "JP.Test" to LiveProxy("Socks5", "", emptyList(), 222),
            "HK.Test" to LiveProxy("Socks5", "", emptyList(), 55),
            "DIRECT" to LiveProxy("Direct", "", emptyList(), null),
        )
        // A presentation fixture only: no VPN or network measurement is started.
        val session = LiveSession(current.library.selectedId!!, current.candidate!!.revision,
            current.geo.joinToString(":") { it.sha256 }, proxies)
        compose.runOnUiThread { runtime.live.value = session; runtime.state.value = RuntimeState.Running(session.revision) }
        compose.onNode(hasText(label(R.string.subscriptions)) and hasClickAction()).performClick()
        compose.onNodeWithContentDescription("🌐 Route").performClick()
        compose.onNodeWithTag("selector-breadcrumb").assertDoesNotExist()
        compose.onNodeWithText("JP.Test").assertIsDisplayed()
        compose.onNodeWithText("222 ms").assertIsDisplayed()
        compose.onNodeWithText("999 ms").assertDoesNotExist()
        capture("latency-zh")
        compose.runOnUiThread { runtime.live.value = session.copy(proxies = proxies +
            ("⚡ Auto" to proxies.getValue("⚡ Auto").copy(now = "HK.Test"))) }
        compose.onNodeWithText("HK.Test").assertIsDisplayed()
        compose.onNodeWithText("55 ms").assertIsDisplayed()
        compose.onNodeWithText("222 ms").assertDoesNotExist()
        compose.runOnUiThread { runtime.live.value = session.copy(proxies = proxies +
            ("JP.Test" to proxies.getValue("JP.Test").copy(delay = -1))) }
        val badgeLabel = compose.activity.getString(R.string.open_nested_selector, "🏡 Region")
        compose.onNodeWithContentDescription(badgeLabel).assert(hasText("—")).performClick()
        compose.onNodeWithTag("selector-breadcrumb").assertIsDisplayed()
        assertTrue(model.state.value.library.selected!!.selections.isEmpty())
        assertNull(model.state.value.error)
        compose.onNode(hasText("🌐 Route") and hasClickAction()).performClick()
        compose.runOnUiThread { runtime.live.value = session.copy(revision = "stale-fixture") }
        compose.onNodeWithText("222 ms").assertDoesNotExist()
        compose.onNodeWithContentDescription(badgeLabel).assert(hasText("—")).performClick()
        compose.onNodeWithTag("selector-breadcrumb").assertIsDisplayed()
    }

    private fun capture(name: String) {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val prefix = InstrumentationRegistry.getArguments().getString("visualPrefix", "phone")!!
        require(prefix.matches(Regex("[A-Za-z0-9_-]+")))
        compose.waitForIdle(); instrumentation.waitForIdleSync()
        val directory = File(compose.activity.getExternalFilesDir(null), "runtime-features").apply { mkdirs() }
        val file = File(directory, "$prefix-$name.png")
        val bitmap = requireNotNull(instrumentation.uiAutomation.takeScreenshot())
        try { file.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) } }
        finally { bitmap.recycle() }
        instrumentation.uiAutomation.executeShellCommand("cp ${file.absolutePath} /data/local/tmp/runtime-features-${file.name}").use {
            java.io.FileInputStream(it.fileDescriptor).use { input -> input.readBytes() }
        }
    }
}
