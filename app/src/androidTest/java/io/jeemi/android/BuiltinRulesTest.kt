package io.jeemi.android

import androidx.appcompat.app.AppCompatDelegate
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.core.os.LocaleListCompat
import androidx.lifecycle.ViewModelProvider
import androidx.test.espresso.Espresso
import androidx.test.platform.app.InstrumentationRegistry
import io.jeemi.android.data.*
import io.jeemi.android.domain.*
import io.jeemi.android.ui.JeemiViewModel
import mobile.Mobile
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import java.io.File

class BuiltinRulesTest {
    @get:Rule(order = 0) val library = TestLibrary()
    @get:Rule(order = 1) val compose = createAndroidComposeRule<MainActivity>()
    private val model get() = ViewModelProvider(compose.activity)[JeemiViewModel::class.java]
    private val app get() = compose.activity.application as JeemiApplication
    private fun ready() { compose.waitUntil(20_000) { model.state.value.loaded }; assertNull(model.state.value.error) }
    private fun finish() { compose.waitUntil(60_000) { !model.state.value.busy && !model.state.value.resourceDownloading }; assertNull(model.state.value.error) }
    private fun click(label: Int) = compose.onNode(hasText(compose.activity.getString(label)) and hasClickAction()).performClick()
    private fun category(key: String) {
        compose.onNodeWithTag("resource-category-menu").performClick()
        compose.onNodeWithTag("resource-category-$key").performClick()
    }
    private fun capture(name: String) {
        if (!name.contains("editor-ime") && !name.startsWith("categories")) Espresso.closeSoftKeyboard()
        compose.waitForIdle()
        InstrumentationRegistry.getInstrumentation().waitForIdleSync()
        Thread.sleep(300)
        val prefix = InstrumentationRegistry.getArguments().getString("visualPrefix", "phone")
        require(prefix.matches(Regex("[A-Za-z0-9_-]+")))
        val directory = File(app.getExternalFilesDir(null), "builtin-rules").apply { mkdirs() }
        val bitmap = InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot()
        val file = File(directory, "$prefix-$name.png")
        file.outputStream().use { bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it) }; bitmap.recycle()
        InstrumentationRegistry.getInstrumentation().uiAutomation.executeShellCommand(
            "cp ${file.absolutePath} /data/local/tmp/jeemi-builtin-${file.name}").use { java.io.FileInputStream(it.fileDescriptor).use { input -> input.readBytes() } }
    }

    @Test fun bothLocalizedTemplatesComposeAndPassBundledCoreOffline() {
        ready()
        val source = "proxies: [{name: Fixture.Node, type: socks5, server: node.example.invalid, port: 1080}]\n" +
            "proxy-groups: [{name: Source, type: select, proxies: [Fixture.Node, DIRECT]}]\nrules: ['MATCH,Source']\n"
        val sub = app.engine.normalize("Fixture", source)
        val before = app.repository.load()
        for (language in listOf("zh", "en")) {
            val config = android.content.res.Configuration(app.resources.configuration).apply { setLocale(java.util.Locale.forLanguageTag(language)) }
            val context = app.createConfigurationContext(config)
            for (kind in listOf("general", "apps")) {
                val result = JSONObject(Mobile.prepareBuiltinRules(kind, "[]", builtinRuleLabels(context)))
                val resources = result.getJSONArray("resources").let { array -> List(array.length()) { resourceFromJson(array.getJSONObject(it)) } }
                val candidate = app.engine.project(sub.copy(handlerId = result.getString("configId")), Preferences(), resources)
                ChainCandidateValidator(app).use { it.validate(candidate.yaml) }
                assertEquals("🚀 Jeemi", candidate.fallbackOriginal)
                assertTrue(candidate.structure.groups.any { it.name == "🚀 Jeemi" })
                assertEquals(4, candidate.structure.groups.count { it.type == "select" })
                assertEquals(4, candidate.structure.groups.count { it.type == "url-test" })
                candidate.structure.groups.filter { it.type == "select" }.forEach { group ->
                    assertEquals(group.name + " Auto", group.members.first())
                    assertEquals(group.name + " Auto", group.defaultSelected)
                }
                assertEquals(kind == "apps", candidate.yaml.contains("PROCESS-NAME,com.tencent.mm,DIRECT"))
                val exported = Mobile.exportResourcePackage(resources.last().toJson().toString(), resources.toJson().toString())
                assertTrue(exported.contains("📷"))
            }
        }
        assertEquals(before, app.repository.load())
    }

    @Test fun compactLibraryFiltersAndEmojiDraftsInBothLanguages() {
        ready()
        val previous = AppCompatDelegate.getApplicationLocales()
        try {
            compose.runOnUiThread { AppCompatDelegate.setApplicationLocales(LocaleListCompat.forLanguageTags("zh-CN")) }
            compose.onNodeWithTag("navigation-CONFIG").performClick()
            compose.onNodeWithTag("builtin-rules").performClick()
            capture("templates-zh")
            compose.onNodeWithTag("builtin-general").performClick()
            compose.waitUntil(60_000) { model.editingResource.value != null || model.state.value.error != null }
            finish()
            assertEquals(21, model.state.value.library.resources.size)
            assertTrue(model.state.value.library.subscriptions.isEmpty())
            val config = model.editingResource.value!!
            compose.runOnUiThread { model.closeResource() }
            compose.onNodeWithTag("resource-category-menu").performClick()
            compose.onNodeWithTag("resource-category-CONFIG").assertIsSelected()
            capture("categories-zh")
            compose.onNodeWithTag("resource-category-GROUPS").performClick()
            capture("groups-zh")
            compose.onNodeWithTag("resource-filters-toggle").performClick()
            compose.onNodeWithTag("resource-config-filter").performClick()
            compose.onNodeWithText(config.name).performClick()
            compose.onNodeWithTag("resource-category-selector").performClick()
            compose.onNodeWithTag("resource-search").performTextInput("AI｜IM")
            compose.waitUntil(10_000) { compose.onAllNodesWithText(compose.activity.getString(R.string.resource_results, 2, 5)).fetchSemanticsNodes().size == 1 }
            capture("filters-zh")
            compose.onNodeWithTag("resource-search").performTextClearance()
            category("RULES")
            val rules = model.state.value.library.resources.filter { it.kind == ResourceKind.RULES }
            val rule = rules.first()
            compose.waitUntil(10_000) { compose.onAllNodesWithTag("resource-card-${rule.id}").fetchSemanticsNodes().isNotEmpty() }
            compose.onNodeWithTag("resource-list").performScrollToNode(hasTestTag("resource-card-${rules.last().id}"))
            compose.onNodeWithTag("resource-search").assertIsDisplayed()
            compose.onNodeWithTag("resource-filters-toggle").assertIsDisplayed()
            capture("rules-scrolled-zh")
            compose.onNodeWithTag("resource-search").performTextReplacement(rule.name)
            compose.waitUntil(10_000) { compose.onAllNodesWithText(compose.activity.getString(R.string.resource_results, 1, 15)).fetchSemanticsNodes().size == 1 }
            compose.onNodeWithTag("resource-card-${rule.id}").assertIsDisplayed()
            compose.onNodeWithTag("resource-search").performImeAction()
            compose.onNodeWithTag("resource-search").assertIsNotFocused()
            compose.onNodeWithTag("resource-search").performTextClearance()
            compose.waitUntil(10_000) { compose.onAllNodesWithText(compose.activity.getString(R.string.resource_results, 15, 15)).fetchSemanticsNodes().size == 1 }
            compose.onNodeWithTag("resource-card-${rule.id}").performClick()
            compose.onNodeWithTag("resource-emoji-toggle").performScrollTo().performClick()
            compose.onNodeWithTag("resource-emoji-🤖").performScrollTo().performClick()
            assertEquals("🤖", JSONObject(model.editingResource.value!!.content).getString("emoji"))
            capture("emoji-zh")
            compose.onNode(hasSetTextAction() and hasText(compose.activity.getString(R.string.name))).performScrollTo()
                .performTouchInput { click() }
            compose.onNodeWithTag("resource-save").assertIsDisplayed()
            compose.onNodeWithTag("resource-check").assertIsDisplayed()
            capture("editor-ime-zh")
            compose.runOnUiThread { AppCompatDelegate.setApplicationLocales(LocaleListCompat.forLanguageTags("en")) }
            assertEquals("🤖", JSONObject(model.editingResource.value!!.content).getString("emoji"))
            compose.onNodeWithTag("resource-save").assertIsDisplayed().performClick()
            finish()
            assertEquals("🤖", JSONObject(model.state.value.library.resources.first { it.id == rule.id }.content).getString("emoji"))
            compose.onNodeWithTag("resource-group-filter").performClick()
            val video = model.state.value.library.resources.first { it.kind == ResourceKind.GROUPS && JSONObject(it.content).optString("emoji") == "🎬" }
            compose.onNodeWithText("🎬 ${video.name}").performClick()
            Espresso.pressBack()
            compose.waitUntil(10_000) { compose.onAllNodesWithText(compose.activity.getString(R.string.resource_results, 5, 15)).fetchSemanticsNodes().size == 1 }
            capture("rules-en")
            compose.onNodeWithTag("resource-search").performTextInput("no-such-rule")
            compose.waitUntil(10_000) { compose.onAllNodesWithText(compose.activity.getString(R.string.no_results)).fetchSemanticsNodes().isNotEmpty() }
            compose.onNodeWithText(compose.activity.getString(R.string.no_results)).assertExists()
            compose.onNodeWithTag("resource-search").performTextClearance()
            val index = indexResourceLibrary(model.state.value.library.resources, model.state.value.resourceGraph)
            assertEquals(15, filterResourceLibrary(index, ResourceKind.RULES, "", config.id).size)
            // Explicitly creating the second template preserves all first-copy
            // resources; its editor can be cancelled without an automatic bind.
            compose.runOnUiThread { model.createBuiltinRules("apps", builtinRuleLabels(compose.activity)) }
            compose.waitUntil(60_000) { model.editingResource.value != null || model.state.value.error != null }
            finish()
            assertEquals(42, model.state.value.library.resources.size)
            assertTrue(model.state.value.library.subscriptions.isEmpty())
            compose.runOnUiThread { model.closeResource() }
        } finally { compose.runOnUiThread { AppCompatDelegate.setApplicationLocales(previous) } }
    }

    @Test fun automaticCompanionSettingSavesAndCancelsInBothLanguages() {
        ready()
        compose.onNodeWithTag("navigation-CONFIG").performClick()
        val previous = AppCompatDelegate.getApplicationLocales()
        try {
            compose.runOnUiThread { model.createBuiltinRules("apps", builtinRuleLabels(compose.activity)) }
            compose.waitUntil(60_000) { model.editingResource.value != null || model.state.value.error != null }
            finish()
            val group = model.state.value.library.resources.first { it.kind == ResourceKind.GROUPS && JSONObject(it.content).optBoolean("autoSelect") }
            for (language in listOf("zh-CN", "en")) {
                compose.runOnUiThread { model.closeResource(); AppCompatDelegate.setApplicationLocales(LocaleListCompat.forLanguageTags(language)) }
                compose.waitForIdle()
                compose.onNodeWithTag("navigation-CONFIG").performClick()
                compose.runOnUiThread { model.openSavedResource(group.id) }
                toggleCompanion()
                assertFalse(JSONObject(model.editingResource.value!!.content).optBoolean("autoSelect"))
                compose.onNodeWithTag("resource-save").performClick(); finish()
                assertFalse(JSONObject(model.state.value.library.resources.first { it.id == group.id }.content).optBoolean("autoSelect"))
                compose.runOnUiThread { model.openSavedResource(group.id) }
                toggleCompanion()
                assertTrue(JSONObject(model.editingResource.value!!.content).getBoolean("autoSelect"))
                capture("auto-selector-$language")
                compose.runOnUiThread { model.closeResource(); model.openSavedResource(group.id) }
                assertFalse(JSONObject(model.editingResource.value!!.content).optBoolean("autoSelect"))
                toggleCompanion()
                compose.onNodeWithTag("resource-save").performClick(); finish()
            }
        } finally { compose.runOnUiThread { model.closeResource(); AppCompatDelegate.setApplicationLocales(previous) } }
    }

    private fun toggleCompanion() {
        // SwitchRow's text is a label, not the click target. Match the actual
        // switch on that row, including when large fonts move the row on screen.
        compose.waitUntil(15_000) {
            compose.onAllNodesWithText(compose.activity.getString(R.string.selector_auto_companion)).fetchSemanticsNodes().size == 1
        }
        val label = compose.onNodeWithText(compose.activity.getString(R.string.selector_auto_companion))
            .performScrollTo().fetchSemanticsNode().boundsInRoot
        compose.onNode(isToggleable() and hasAnyAncestor(isDialog()) and SemanticsMatcher("companion switch row") {
            kotlin.math.abs(it.boundsInRoot.center.y - label.center.y) < 2f
        }).performClick()
    }

    @Test fun savedCardActionsUseCurrentResourceAndIgnoreDeletedIds() {
        ready()
        val initial = LocalResource("f".repeat(32), "Card fixture", ResourceKind.SCRIPT,
            "function main(config) { return config; }", formatVersion = 2)
        compose.runOnUiThread { model.openResource(initial); model.saveResource() }
        finish()
        val staleCard = model.state.value.library.resources.single()
        compose.runOnUiThread {
            model.openSavedResource(staleCard.id)
            model.editingResource.value = requireNotNull(model.editingResource.value).copy(description = "Latest saved value")
            model.saveResource()
        }
        finish()
        compose.runOnUiThread { model.openSavedResource(staleCard.id) }
        assertEquals("Latest saved value", model.editingResource.value?.description)
        compose.runOnUiThread { model.closeResource(); model.removeResource(staleCard.id) }
        finish()
        compose.runOnUiThread { model.openSavedResource(staleCard.id) }
        assertNull(model.editingResource.value)
        assertTrue(app.repository.load().resources.isEmpty())
    }
}
