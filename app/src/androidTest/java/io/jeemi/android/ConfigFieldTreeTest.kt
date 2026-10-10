package io.jeemi.android

import androidx.appcompat.app.AppCompatDelegate
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.core.os.LocaleListCompat
import androidx.lifecycle.ViewModelProvider
import androidx.test.platform.app.InstrumentationRegistry
import io.jeemi.android.data.*
import io.jeemi.android.domain.*
import io.jeemi.android.ui.JeemiViewModel
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import java.io.File
import java.io.FileInputStream

private fun fieldTreeFixture(app: JeemiApplication): Library {
    val resource = app.engine.prepareTyped(LocalResource("e".repeat(32), "Field tree fixture", ResourceKind.CONFIG,
        """{"fields":[{"path":"/tcp-concurrent","valueYaml":"false","strategy":"replace"},
            {"path":"/sniffer/enable","valueYaml":"false","strategy":"replace"}],
            "disabledFields":[{"path":"/sniffer/force-domain","valueYaml":"- example.invalid\n","strategy":"append"}],
            "resourcePlan":{"version":1,"ruleStrategy":"append_before_terminal","match":{"mode":"none"}}}""",
        formatVersion = 2))
    return Library(resources = listOf(resource))
}

class ConfigFieldTreeTest {
    @get:Rule(order = 0) val library = TestLibrary(initial = ::fieldTreeFixture)
    @get:Rule(order = 1) val compose = createAndroidComposeRule<MainActivity>()
    private val model get() = ViewModelProvider(compose.activity)[JeemiViewModel::class.java]
    private val app get() = compose.activity.application as JeemiApplication
    private fun content() = JSONObject(model.editingResource.value!!.content)
    private fun entries(key: String) = content().configFields(key)
    private fun ready() {
        compose.waitUntil(20_000) { model.state.value.loaded }
        val locale = InstrumentationRegistry.getArguments().getString("testLocale", "zh-CN")
        compose.runOnUiThread { AppCompatDelegate.setApplicationLocales(LocaleListCompat.forLanguageTags(locale)) }
        compose.onNodeWithTag("navigation-CONFIG").performClick()
        compose.runOnIdle { model.openResource(model.state.value.library.resources.single()) }
    }
    private fun finish() {
        compose.waitUntil(30_000) { !model.state.value.busy }
        assertNull(model.state.value.error)
    }
    private fun search(value: String) = compose.onNodeWithTag("field-tree-search").performScrollTo().performTextReplacement(value)
    private fun fieldToggle(path: String) = compose.onNodeWithTag("field-toggle-$path").performScrollTo().performClick().also { finish() }
    private fun filter(id: Int) = compose.onNode(isToggleable() and hasAnyAncestor(hasTestTag(
        if (id == R.string.fields_only_enabled) "field-filter-enabled" else "field-filter-locked")))
    private fun capture(name: String) {
        if (!name.endsWith("ime")) androidx.test.espresso.Espresso.closeSoftKeyboard()
        compose.waitForIdle()
        Thread.sleep(300)
        val automation = InstrumentationRegistry.getInstrumentation().uiAutomation
        val prefix = InstrumentationRegistry.getArguments().getString("visualPrefix", "phone")
        require(prefix.matches(Regex("[A-Za-z0-9_-]+")))
        val file = File(app.getExternalFilesDir(null), "field-tree-$prefix-$name.png")
        val bitmap = automation.takeScreenshot()
        file.outputStream().use { bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it) }
        bitmap.recycle()
        automation.executeShellCommand("cp ${file.absolutePath} /data/local/tmp/jeemi-${file.name}").use {
            FileInputStream(it.fileDescriptor).use { stream -> stream.readBytes() }
        }
    }

    @Test fun fieldAndParentSwitchesRetainValuesStrategiesAndRequireResourceSave() {
        ready()
        val saved = model.state.value.library.resources.single()
        search("tcp-concurrent")
        compose.onNodeWithTag("field-name-/tcp-concurrent").assertTextContains("tcp-concurrent")
        fieldToggle("/tcp-concurrent").assertIsOff()
        assertEquals("false", entries("disabledFields").single { it.getString("path") == "/tcp-concurrent" }.getString("valueYaml").trimEnd())
        fieldToggle("/tcp-concurrent").assertIsOn()
        assertEquals("false", entries("fields").single { it.getString("path") == "/tcp-concurrent" }.getString("valueYaml").trimEnd())
        assertEquals(saved, model.state.value.library.resources.single())
        search("sniffer")
        compose.onNodeWithTag("field-name-/sniffer").performScrollTo().performClick()
        compose.onNodeWithTag("field-row-/sniffer/enable").assertDoesNotExist()
        compose.onNodeWithTag("field-name-/sniffer").performClick()
        fieldToggle("/sniffer").assertIsOff()
        assertTrue(entries("fields").none { it.getString("path").startsWith("/sniffer/") })
        fieldToggle("/sniffer").assertIsOn()
        assertEquals(2, entries("fields").count { it.getString("path").startsWith("/sniffer/") })
        assertEquals("append", entries("fields").single { it.getString("path") == "/sniffer/force-domain" }.getString("strategy"))
        capture("nested")
        compose.onNodeWithTag("resource-save").performClick(); finish()
        compose.waitUntil(15_000) { model.editingResource.value == null }
        compose.runOnIdle { model.openResource(model.state.value.library.resources.single()) }
        assertEquals(3, entries("fields").size)
        assertEquals("false", entries("fields").single { it.getString("path") == "/sniffer/enable" }.getString("valueYaml").trimEnd())
        assertEquals(RuntimeState.Stopped, model.runtime.value)
    }

    @Test fun filtersRevealReadOnlyStructureAndSearchLocalizedDescriptions() {
        ready()
        search("tun")
        compose.onNodeWithTag("field-row-/tun").assertDoesNotExist()
        filter(R.string.fields_show_locked).performScrollTo().performClick()
        compose.onNodeWithTag("field-toggle-/tun/stack").performScrollTo().assertIsNotEnabled()
        compose.onNodeWithTag("field-name-/tun/stack").assertIsNotEnabled()
        capture("locked")
        compose.onNodeWithContentDescription(compose.activity.getString(R.string.help_for, "tun.stack")).performScrollTo().performClick()
        compose.onNodeWithText(compose.activity.getString(R.string.help_purpose)).assertIsDisplayed()
        compose.onNodeWithText(compose.activity.getString(R.string.close)).performClick()
        search("external-ui")
        compose.onNodeWithTag("field-toggle-/external-ui").assertIsNotEnabled()
        filter(R.string.fields_show_locked).performScrollTo().performClick()
        search("")
        filter(R.string.fields_only_enabled).performScrollTo().performClick()
        compose.onNodeWithTag("field-row-/sniffer/enable").assertExists()
        compose.onNodeWithTag("field-row-/sniffer/force-domain").assertDoesNotExist()
        compose.onNodeWithTag("field-row-/unified-delay").assertDoesNotExist()
        filter(R.string.fields_only_enabled).performScrollTo().performClick()
        val query = if (compose.activity.resources.configuration.locales[0].language == "zh") "嗅探" else "sniffing"
        search(query)
        compose.onNodeWithTag("field-row-/sniffer").assertExists()
        compose.onNodeWithTag("field-row-/tcp-concurrent").assertDoesNotExist()
        assertEquals(model.state.value.library.resources.single().content, model.editingResource.value!!.content)
    }

    @Test fun typedValueHelpKeyboardAndLocaleKeepDraftWithoutImplicitEnabling() {
        ready()
        search("keep-alive-interval")
        compose.onNodeWithTag("field-name-/keep-alive-interval").performScrollTo().performClick()
        compose.onNodeWithTag("field-value").performTextReplacement("42")
        compose.onNodeWithTag("field-value").performTouchInput { click() }
        compose.onNodeWithTag("field-apply").assertIsDisplayed()
        capture("numeric-ime")
        compose.onAllNodesWithContentDescription(compose.activity.getString(R.string.help_for, "keep-alive-interval")).onLast().performClick()
        compose.onNodeWithText(compose.activity.getString(R.string.close)).performClick()
        compose.activityRule.scenario.recreate()
        compose.onNodeWithTag("field-value").assertTextContains("42")
        compose.onNodeWithTag("field-apply").performClick(); finish()
        assertFalse(entries("fields").any { it.getString("path") == "/keep-alive-interval" })
        assertEquals("42", entries("disabledFields").single { it.getString("path") == "/keep-alive-interval" }.getString("valueYaml").trimEnd())
        fieldToggle("/keep-alive-interval").assertIsOn()
        search("/dns/cache-algorithm")
        compose.onNodeWithTag("field-name-/dns/cache-algorithm").performScrollTo().performClick()
        if (compose.activity.resources.configuration.locales[0].language == "zh") {
            compose.onNode(hasText(compose.activity.getString(R.string.cache_arc)) and isSelectable()).assertExists()
            compose.onNode(hasText(compose.activity.getString(R.string.cache_lru)) and isSelectable()).performClick()
        } else {
            // English descriptions are deliberately long, so the same fixed field uses a menu.
            compose.onNode(hasText(compose.activity.getString(R.string.cache_arc)) and hasClickAction()).performClick()
            compose.onNode(hasText(compose.activity.getString(R.string.cache_lru)) and hasAnyAncestor(isPopup())).performClick()
        }
        compose.onNodeWithTag("field-apply").performClick(); finish()
        assertEquals("lru", entries("disabledFields").single { it.getString("path") == "/dns/cache-algorithm" }.getString("valueYaml").trimEnd())
        search("/sniffer/force-domain")
        compose.onNodeWithTag("field-name-/sniffer/force-domain").performScrollTo().performClick()
        val before = model.editingResource.value
        compose.onNodeWithTag("field-value").performTextReplacement("[broken")
        compose.onNodeWithTag("field-apply").performClick()
        compose.waitUntil(15_000) { model.state.value.error != null }
        assertEquals(before, model.editingResource.value)
        assertEquals("[broken", model.fieldDraft.value)
        compose.runOnIdle { model.dismissError() }
        compose.onNodeWithTag("field-value").performTextReplacement("- example.invalid\n- another.invalid")
        compose.onNodeWithTag("field-apply").assertIsDisplayed()
        capture("yaml-ime")
        compose.onNodeWithTag("field-apply").performClick(); finish()
        assertTrue(entries("disabledFields").single { it.getString("path") == "/sniffer/force-domain" }.getString("valueYaml").contains("another.invalid"))
    }
}
