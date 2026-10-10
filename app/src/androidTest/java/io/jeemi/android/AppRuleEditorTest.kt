package io.jeemi.android

import androidx.appcompat.app.AppCompatDelegate
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.text.TextLayoutResult
import androidx.core.os.LocaleListCompat
import androidx.lifecycle.ViewModelProvider
import androidx.test.platform.app.InstrumentationRegistry
import io.jeemi.android.data.*
import io.jeemi.android.domain.*
import io.jeemi.android.ui.JeemiViewModel
import io.jeemi.android.ui.connectionRuleSets
import io.jeemi.android.ui.preferredAppRuleSet
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import java.io.File
import java.io.FileInputStream

private fun appRuleFixture(app: JeemiApplication): Library {
    val rule = app.engine.prepareTyped(LocalResource("a".repeat(32), "Mixed Apps", ResourceKind.RULES,
        JSONObject().put("sourceType", "inline").put("behavior", "classical").put("format", "yaml")
            .put("payloadYaml", "# mixed rules\npayload:\n  - PROCESS-NAME,${app.packageName}\n  - DOMAIN,example.invalid # domain\n  - PROCESS-NAME,org.example.uninstalled\n  - IP-CIDR,192.0.2.0/24\n  - PROCESS-NAME,android\n").toString(), formatVersion = 2))
    return Library(resources = listOf(rule))
}

class AppRuleEditorTest {
    @get:Rule(order = 0) val library = TestLibrary(initial = ::appRuleFixture)
    @get:Rule(order = 1) val compose = createAndroidComposeRule<MainActivity>()
    private val model get() = ViewModelProvider(compose.activity)[JeemiViewModel::class.java]
    private val app get() = compose.activity.application as JeemiApplication
    private fun label(id: Int) = compose.activity.getString(id)
    private fun content() = JSONObject(model.editingResource.value!!.content)
    private fun yaml() = content().getString("payloadYaml")
    private fun ready() {
        compose.waitUntil(20_000) { model.state.value.loaded }
        val locale = InstrumentationRegistry.getArguments().getString("testLocale", "zh-CN")
        compose.runOnUiThread { AppCompatDelegate.setApplicationLocales(LocaleListCompat.forLanguageTags(locale)) }
        compose.waitForIdle()
        compose.onNodeWithTag("navigation-CONFIG").performClick()
        compose.runOnIdle { model.openResource(model.state.value.library.resources.single()) }
    }
    private fun toggle() = compose.onNodeWithTag("rule-app-toggle").performScrollTo().performClick()
    private fun waitApps() {
        compose.waitUntil(10_000) { compose.onAllNodesWithTag("rule-app-list").fetchSemanticsNodes().isNotEmpty() }
    }
    private fun finish() {
        compose.waitUntil(60_000) { !model.state.value.busy }
        assertNull(model.state.value.error)
    }
    private fun expandYaml() = compose.onNode(hasText(label(R.string.app_rule_yaml)) and hasClickAction()).performScrollTo().performClick()
    private fun capture(name: String) {
        compose.waitForIdle()
        val automation = InstrumentationRegistry.getInstrumentation().uiAutomation
        Thread.sleep(300)
        val prefix = InstrumentationRegistry.getArguments().getString("visualPrefix", "phone")
        require(prefix.matches(Regex("[A-Za-z0-9_-]+")))
        val file = File(app.getExternalFilesDir(null), "app-rules-$prefix-$name.png")
        val bitmap = automation.takeScreenshot()
        file.outputStream().use { bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it) }
        bitmap.recycle()
        automation.executeShellCommand("cp ${file.absolutePath} /data/local/tmp/jeemi-${file.name}").use {
            FileInputStream(it.fileDescriptor).use { stream -> stream.readBytes() }
        }
    }

    @Test fun mixedYamlSurvivesModesMovesDeletionValidationAndSave() {
        ready()
        val original = yaml()
        waitApps()
        compose.onNodeWithTag("rule-app-toggle").assertIsOn()
        compose.onNode(hasText("App") and hasAnyAncestor(hasContentDescription(label(R.string.rule_behavior)))).assertDoesNotExist()
        assertEquals("classical", content().getString("behavior"))
        assertEquals(original, yaml())
        toggle().assertIsOff(); assertEquals(original, yaml())
        compose.onNodeWithTag("rule-app-list").assertDoesNotExist()
        compose.onNodeWithTag("rule-plain-yaml").assertTextContains(original)
        toggle().assertIsOn(); waitApps()
        compose.onNodeWithTag("rule-app-list").performScrollTo()
        compose.onNodeWithText("DOMAIN,example.invalid").assertExists()
        compose.onNodeWithText("IP-CIDR,192.0.2.0/24").assertExists()
        compose.waitUntil(10_000) { compose.onAllNodesWithTag("rule-app-missing-org.example.uninstalled").fetchSemanticsNodes().isNotEmpty() }
        val missingLayouts = mutableListOf<TextLayoutResult>()
        compose.onNodeWithTag("rule-app-missing-org.example.uninstalled").performSemanticsAction(SemanticsActions.GetTextLayoutResult) { it(missingLayouts) }
        assertTrue("Missing status breaks across too many lines", missingLayouts.single().lineCount <= 2)
        compose.onNodeWithTag("rule-app-missing-${app.packageName}").assertDoesNotExist()
        capture("mixed-apps")
        compose.onNodeWithTag("rule-app-up-2").performClick()
        compose.waitUntil(10_000) { yaml().indexOf("org.example.uninstalled") < yaml().indexOf("DOMAIN,example.invalid") }
        finish(); waitApps()
        val moved = runBlocking { editAppRuleDraft(yaml()) }
        assertEquals(listOf(0, 1, 4), moved.apps.map { it.index })
        assertEquals(listOf(0, 1, 2, 3, 4), moved.rows.map { it.index })
        assertEquals(2, moved.otherRules)
        compose.onNodeWithTag("rule-app-delete-1").performClick()
        compose.waitUntil(10_000) { !yaml().contains("org.example.uninstalled") }
        finish(); waitApps()
        expandYaml()
        val valid = yaml()
        compose.onNodeWithTag("rule-app-yaml").performScrollTo().performTextReplacement("payload: null")
        compose.waitUntil(10_000) { compose.onAllNodesWithText(label(R.string.app_rule_invalid)).fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithTag("rule-app-add").assertIsNotEnabled()
        compose.onNodeWithTag("rule-app-yaml").performTextReplacement(valid.replace("example.invalid", "edited.invalid"))
        waitApps()
        compose.onNodeWithTag("resource-save").assertIsDisplayed().performClick()
        compose.waitUntil(60_000) { model.editingResource.value == null || model.state.value.error != null }
        finish()
        val saved = app.repository.load().resources.single()
        val savedContent = JSONObject(saved.content)
        assertTrue(savedContent.isAppListEnabled())
        assertEquals("classical", savedContent.getString("behavior"))
        assertTrue(savedContent.getString("payloadYaml").contains("DOMAIN,edited.invalid # domain"))
        assertTrue(savedContent.getString("payloadYaml").contains("IP-CIDR,192.0.2.0/24"))
        compose.runOnIdle { model.openResource(saved) }
        waitApps(); expandYaml()
        compose.onNodeWithTag("rule-app-yaml").performScrollTo().assertTextContains(savedContent.getString("payloadYaml"))
        capture("mixed-yaml")
        compose.runOnIdle { model.closeResource() }
    }

    @Test fun pickerSearchNamesPackagesSystemAppsAndCancelKeepTransactions() {
        ready(); waitApps()
        val saved = app.repository.load()
        val original = yaml()
        compose.onNodeWithTag("rule-app-add").performScrollTo().performClick()
        val apps = runBlocking { model.installedApps.load() }
        val current = apps.single { it.packageName == app.packageName }
        compose.onNodeWithTag("rule-app-search").performTextReplacement(current.name)
        compose.waitUntil(15_000) { compose.onAllNodesWithTag("rule-app-pick-${app.packageName}").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithTag("rule-app-pick-${app.packageName}").assertIsOn().assertIsNotEnabled()
        compose.onNodeWithTag("rule-app-search").performTextReplacement("com.android.settings")
        compose.waitUntil(10_000) { compose.onAllNodesWithText(label(R.string.no_results)).fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithTag("rule-app-system").performClick().assertIsOn()
        compose.waitUntil(10_000) { compose.onAllNodesWithTag("rule-app-pick-com.android.settings").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithTag("rule-app-pick-com.android.settings").performClick().assertIsOn()
        capture("picker-system")
        val testPackage = InstrumentationRegistry.getInstrumentation().context.packageName
        compose.onNodeWithTag("rule-app-search").performTextReplacement(testPackage)
        compose.waitUntil(10_000) { compose.onAllNodesWithTag("rule-app-pick-$testPackage").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithTag("rule-app-pick-$testPackage").performClick().assertIsOn()
        compose.onNodeWithTag("rule-app-confirm").assertIsDisplayed().performClick()
        compose.waitUntil(10_000) { yaml().contains("PROCESS-NAME,$testPackage") }
        finish(); waitApps()
        assertTrue(yaml().contains("PROCESS-NAME,com.android.settings"))
        assertTrue(yaml().indexOf("com.android.settings") < yaml().indexOf(testPackage))
        assertEquals(saved, app.repository.load())
        compose.onNodeWithTag("rule-app-add").performScrollTo().performClick()
        compose.onNode(hasContentDescription(label(R.string.back)) and
            hasAnyAncestor(isDialog() and hasAnyDescendant(hasTestTag("rule-app-search")))).performClick()
        compose.onNodeWithTag("resource-save").assertIsDisplayed()
        compose.onNode(hasText(label(R.string.cancel)) and hasClickAction()).performClick()
        assertEquals(saved, app.repository.load())
        compose.runOnIdle { model.openResource(saved.resources.single()) }
        assertEquals(original, yaml())
        assertEquals("", content().optString("editorMode"))
        compose.runOnIdle { model.closeResource() }
    }

    @Test fun textOnlyPreferencePersistsAndLegacyAppPreferenceRemainsCompatible() {
        ready(); waitApps()
        val original = yaml()
        toggle().assertIsOff()
        compose.onNodeWithTag("resource-save").performClick()
        compose.waitUntil(60_000) { model.editingResource.value == null || model.state.value.error != null }
        finish()
        val saved = app.repository.load().resources.single()
        assertEquals("yaml", JSONObject(saved.content).getString("editorMode"))
        compose.runOnIdle { model.openResource(saved) }
        compose.onNodeWithTag("rule-app-toggle").assertIsOff()
        assertEquals(original, yaml())
        compose.onNodeWithTag("rule-app-list").assertDoesNotExist()
        toggle().assertIsOn(); waitApps()
        compose.onNode(hasText(label(R.string.cancel)) and hasClickAction()).performClick()
        assertEquals(saved, app.repository.load().resources.single())

        val legacy = saved.copy(content = JSONObject(saved.content).put("editorMode", "app").toString())
        val defaultList = saved.copy(id = "b".repeat(32), content = JSONObject(saved.content).apply { remove("editorMode") }.toString())
        val legacyList = legacy.copy(id = "c".repeat(32))
        val targets = listOf(saved, defaultList, legacyList)
        assertEquals(listOf(defaultList.id, legacyList.id, saved.id), connectionRuleSets(targets, "processName").map { it.id })
        assertEquals(defaultList.id, preferredAppRuleSet(targets, "processName"))
        assertEquals("", preferredAppRuleSet(listOf(saved), "processName"))
        assertEquals("", preferredAppRuleSet(targets, "domain"))
        compose.runOnIdle { model.openResource(legacy) }
        waitApps()
        compose.onNodeWithTag("rule-app-toggle").assertIsOn()
        assertEquals(original, yaml())
        compose.runOnIdle { model.closeResource() }
    }
}
