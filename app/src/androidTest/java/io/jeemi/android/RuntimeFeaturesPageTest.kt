package io.jeemi.android

import android.graphics.Bitmap
import androidx.appcompat.app.AppCompatDelegate
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.core.os.LocaleListCompat
import androidx.lifecycle.ViewModelProvider
import androidx.test.platform.app.InstrumentationRegistry
import io.jeemi.android.domain.*
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
              - {name: '🏡 Region', type: select, hidden: true, proxies: [HK.Test, JP.Test]}
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
    @After fun restoreLanguage() { language("en") }

    @Test fun hiddenNestedNavigationSelectionAndTitlesWorkInBothLanguages() {
        language("zh-CN")
        compose.waitUntil(20_000) { model.state.value.loaded }
        compose.onNode(hasText(label(R.string.subscriptions)) and hasClickAction()).performClick()
        compose.onNodeWithContentDescription("🏡 Region").assertDoesNotExist()
        compose.onNodeWithContentDescription("🌐 Route").performClick()
        compose.onNodeWithContentDescription(compose.activity.getString(R.string.open_nested_selector, "🏡 Region")).performClick()
        assertTrue(model.state.value.library.selected!!.selections.isEmpty())
        compose.onNodeWithText("JP.Test").performClick()
        compose.waitUntil(15_000) { model.state.value.library.selected!!.selections["🏡 Region"] == "JP.Test" }
        compose.onNodeWithText(compose.activity.getString(R.string.selector_preselected, "🏡 Region · JP.Test")).assertIsDisplayed()
        capture("nested-zh")
        language("en")
        compose.onNodeWithText("JP.Test").assertIsDisplayed()
        capture("nested-en")
        compose.onNodeWithContentDescription("🌐 Route").performTouchInput { longClick() }
        compose.onNodeWithText(label(R.string.egress_path)).assertIsDisplayed()
        compose.onNodeWithText("🏡 Region · JP.Test").assertIsDisplayed()
        compose.onNodeWithText(label(R.string.close)).performClick()
        compose.onNode(hasText(label(R.string.home)) and hasClickAction()).performClick()
        compose.onNode(hasText(label(R.string.subscriptions)) and hasClickAction()).performClick()
        // The expanded flag is local to the page, while the browsed path is retained.
        if (compose.onAllNodesWithText("JP.Test").fetchSemanticsNodes().isEmpty())
            compose.onNodeWithContentDescription("🌐 Route").performClick()
        compose.onNodeWithText("JP.Test").assertIsDisplayed()
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
