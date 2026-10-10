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
import mobile.Mobile
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import java.io.File

private val configID = "a".repeat(32)
private val groupID = "b".repeat(32)
private val rulesID = "c".repeat(32)
private val scriptID = "d".repeat(32)

internal fun resourceWorkspaceFixture(app: JeemiApplication): Library {
    fun resource(id: String, name: String, kind: ResourceKind, content: String) =
        app.engine.prepareTyped(LocalResource(id, name, kind, content, formatVersion = 2))
    val rule = resource(rulesID, "Rules fixture", ResourceKind.RULES,
        """{"sourceType":"inline","behavior":"classical","format":"yaml","payloadYaml":"payload:\n  - DOMAIN,example.invalid\n"}""")
    val group = resource(groupID, "Local selector", ResourceKind.GROUPS,
        """{"kind":"selector","type":"select","ruleOutput":"inline","ruleSetReferences":[{"ruleSetId":"$rulesID"}]}""")
    val config = resource(configID, "Config fixture", ResourceKind.CONFIG,
        """{"fields":[],"resourcePlan":{"version":1,"strategyGroupIds":["$groupID"],"ruleStrategy":"append_before_terminal","match":{"mode":"none"}}}""")
    val script = resource(scriptID, "Script fixture", ResourceKind.SCRIPT, "function main(config) { return config; }")
    val chain = JSONObject(Mobile.mutateChainLibrary(EMPTY_CHAIN_LIBRARY,
        """{"action":"save_group","revision":0,"kind":"manual","name":"Landing fixture"}""")).getJSONObject("library").toString()
    val source = "proxies: [{name: Fixture.Node, type: socks5, server: node.example.invalid, port: 1080}]\n" +
        "proxy-groups: [{name: Route, type: select, proxies: [Fixture.Node, DIRECT]}]\nrules: ['MATCH,Route']\n"
    val first = app.engine.normalize("First subscription", source).copy(handlerId = configID)
    val other = app.engine.normalize("Other subscription", source).copy(handlerId = configID)
    return Library(listOf(first, other), first.id, resources = listOf(config, group, rule, script), chainLibrary = chain)
}

class ResourceWorkspaceTest {
    @get:Rule(order = 0) val library = TestLibrary(initial = ::resourceWorkspaceFixture)
    @get:Rule(order = 1) val compose = createAndroidComposeRule<MainActivity>()
    private val model get() = ViewModelProvider(compose.activity)[JeemiViewModel::class.java]
    private val app get() = compose.activity.application as JeemiApplication
    private fun ready() { compose.waitUntil(20_000) { model.state.value.loaded } }
    private fun finish() { compose.waitUntil(60_000) { !model.state.value.busy && !model.state.value.resourceDownloading } }
    private fun click(id: Int) {
        val nav = when (id) { R.string.configuration -> "CONFIG"; R.string.subscriptions -> "SUBSCRIPTIONS"; else -> null }
        if (nav != null) compose.onNodeWithTag("navigation-$nav").performClick()
        else compose.onNode(hasText(compose.activity.getString(id)) and hasClickAction()).performClick()
    }
    private fun capture(name: String) {
        compose.waitForIdle()
        InstrumentationRegistry.getInstrumentation().waitForIdleSync()
        Thread.sleep(350) // Android window/IME animations are outside the Compose test clock.
        val prefix = InstrumentationRegistry.getArguments().getString("visualPrefix", "phone")
        require(prefix.matches(Regex("[A-Za-z0-9_-]+")))
        val directory = File(app.getExternalFilesDir(null), "resource-workspace").apply { mkdirs() }
        val bitmap = InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot()
        val file = File(directory, "$prefix-$name.png")
        file.outputStream().use { bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it) }
        bitmap.recycle()
        InstrumentationRegistry.getInstrumentation().uiAutomation.executeShellCommand(
            "cp " + file.absolutePath + " /data/local/tmp/jeemi-resource-" + file.name).use {
            java.io.FileInputStream(it.fileDescriptor).use { output -> output.readBytes() }
        }
    }

    @Test fun editorHelpLanguageAndPreviewKeepDraftWithVisibleFooter() {
        ready()
        val previousLocale = AppCompatDelegate.getApplicationLocales()
        try {
            compose.runOnUiThread { AppCompatDelegate.setApplicationLocales(LocaleListCompat.forLanguageTags("zh-CN")) }
            click(R.string.subscriptions)
            compose.onAllNodesWithContentDescription(compose.activity.getString(R.string.subscription_shelf))[0].performClick()
            capture("bindings-zh")
            click(R.string.configuration)
            capture("library-zh")
            compose.runOnUiThread { model.openResource(model.state.value.library.resources.first { it.id == configID }) }
            compose.onNode(hasSetTextAction() and hasText(compose.activity.getString(R.string.description))).apply {
                performTouchInput { click() }
                performTextInput("Draft retained")
            }
            compose.onNodeWithTag("resource-save").assertIsDisplayed()
            compose.onNodeWithTag("resource-check").assertIsDisplayed()
            capture("editor-ime-zh")
            compose.onNode(hasContentDescription(compose.activity.getString(R.string.help_for, compose.activity.getString(R.string.local_configuration))) and hasAnyAncestor(isDialog())).performClick()
            click(R.string.close)
            assertEquals("Draft retained", model.editingResource.value?.description)
            compose.runOnUiThread { AppCompatDelegate.setApplicationLocales(LocaleListCompat.forLanguageTags("en")) }
            compose.onNodeWithTag("resource-save").assertIsDisplayed()
            capture("editor-en")
            compose.onNodeWithTag("resource-check").performClick()
            compose.waitUntil(60_000) { model.state.value.resourceCheck != null || model.state.value.error != null }
            assertNull(model.state.value.error)
            assertEquals("Draft retained", model.editingResource.value?.description)
            capture("check-en")
            compose.onAllNodesWithTag("relationships-resource:$configID").onLast().performClick()
            compose.onNodeWithText("Local selector").assertExists()
            capture("relationships-en")
            assertEquals("", app.repository.load().resources.first { it.id == configID }.description)
            compose.runOnUiThread { model.dismissResourceCheck() }
            for ((id, name) in listOf(groupID to "group-editor-en", rulesID to "rules-editor-en", scriptID to "script-editor-en")) {
                compose.runOnUiThread { model.openResource(model.state.value.library.resources.first { it.id == id }) }
                compose.onNodeWithTag("resource-check").assertIsDisplayed()
                compose.onNodeWithTag("resource-save").assertIsDisplayed()
                capture(name)
            }
            compose.runOnUiThread { model.closeResource() }
            click(R.string.chain_proxies)
            capture("chains-en")
            compose.runOnUiThread { model.openChainGroup(model.state.value.chains.groups.single()) }
            compose.onNodeWithTag("chain-check").assertIsDisplayed()
            compose.onNodeWithTag("chain-save").assertIsDisplayed()
            capture("chain-editor-en")
        } finally {
            compose.runOnUiThread { AppCompatDelegate.setApplicationLocales(previousLocale) }
        }
    }

    @Test fun indirectPreviewChecksBothSubscriptionsWithoutSavingAndRejectsCoreInvalidDraft() {
        ready()
        val before = model.state.value.library
        assertEquals(before.subscriptions.map { it.id }, model.state.value.resourceGraph.affected("resource:$rulesID"))
        compose.runOnUiThread { model.previewSavedResource(rulesID) }
        compose.waitUntil(60_000) { model.state.value.resourceCheck != null || model.state.value.error != null }
        assertNull(model.state.value.error)
        val check = requireNotNull(model.state.value.resourceCheck)
        assertEquals(2, check.candidates.size)
        assertTrue(check.candidates.all { it.passed })
        assertEquals(before, model.state.value.library)
        assertEquals(before.resources, app.repository.load().resources)
        compose.runOnUiThread {
            model.dismissResourceCheck()
            val original = before.resources.first { it.id == configID }
            model.openResource(original)
            model.editingResource.value = original.copy(formatVersion = 1,
                content = "sniffer: {enable: true, sniff: {TLS: {ports: [invalid-port]}}}")
            model.previewResource()
        }
        compose.waitUntil(60_000) { model.state.value.resourceCheck != null || model.state.value.error != null }
        assertNull("The resource passes composition; core validation must detect the invalid port", model.state.value.error)
        assertTrue(requireNotNull(model.state.value.resourceCheck).candidates.all { !it.passed })
        compose.runOnUiThread { model.dismissResourceCheck(); model.saveResource() }
        finish()
        assertNotNull(model.state.value.error)
        assertEquals(before, model.state.value.library)
        assertNotNull(model.editingResource.value)
        assertEquals(before.resources, app.repository.load().resources)
        assertEquals(RuntimeState.Stopped, model.runtime.value)
    }

    @Test fun otherCardBindingsAreIndependentAndDoNotSelectTheCard() {
        ready()
        val previousLocale = AppCompatDelegate.getApplicationLocales()
        try {
            compose.runOnUiThread { AppCompatDelegate.setApplicationLocales(LocaleListCompat.forLanguageTags("zh-CN")) }
            click(R.string.subscriptions)
            compose.onAllNodesWithContentDescription(compose.activity.getString(R.string.subscription_shelf))[0].performClick()
            val initial = model.state.value.library
            val other = initial.subscriptions[1]
            val action = "binding-action-${other.id}-SCRIPT"
            compose.onNodeWithTag(action).performScrollTo().performClick()
            compose.onNodeWithText("Script fixture").performClick()
            click(R.string.save)
            compose.waitUntil(60_000) { model.state.value.library.subscriptions[1].handlerId == scriptID }
            assertEquals(initial.selectedId, model.state.value.library.selectedId)
            assertEquals(configID, model.state.value.library.subscriptions[0].handlerId)
            compose.onNodeWithTag("binding-action-${other.id}-CHAIN").performScrollTo().performClick()
            compose.onNodeWithText("Landing fixture").performClick()
            click(R.string.save)
            compose.waitUntil(60_000) { model.state.value.library.subscriptions[1].chainGroupIds.isNotEmpty() }
            compose.onNodeWithTag(action).performScrollTo().performClick()
            compose.waitUntil(60_000) { model.state.value.library.subscriptions[1].handlerId == null }
            assertEquals(1, model.state.value.library.subscriptions[1].chainGroupIds.size)
            assertEquals(initial.resources, model.state.value.library.resources)
            assertEquals(initial.selectedId, model.state.value.library.selectedId)
            compose.runOnUiThread { AppCompatDelegate.setApplicationLocales(LocaleListCompat.forLanguageTags("en")) }
            compose.onNodeWithTag("binding-action-${other.id}-CHAIN").performScrollTo().performClick()
            compose.waitUntil(60_000) { model.state.value.library.subscriptions[1].chainGroupIds.isEmpty() }
            assertEquals(RuntimeState.Stopped, model.runtime.value)
        } finally {
            compose.runOnUiThread { AppCompatDelegate.setApplicationLocales(previousLocale) }
        }
    }

    @Test fun staleResourceDraftCannotOverwriteAnotherSaveAndChainPreviewDoesNotCommit() {
        ready()
        val saved = model.state.value.library.resources.first { it.id == configID }
        compose.runOnUiThread {
            model.openResource(saved)
            model.editingResource.value = saved.copy(description = "first edit")
            model.saveResource()
        }
        finish()
        assertNull(model.state.value.error)
        val next = model.state.value.library.resources.first { it.id == configID }
        assertEquals("first edit", next.description)
        compose.runOnUiThread { model.openResource(next); model.removeResource(configID) }
        finish()
        assertNull(model.state.value.error)
        compose.runOnUiThread { model.saveResource() }
        finish()
        assertNotNull("An old editor must not recreate a removed shared resource", model.state.value.error)
        assertFalse(model.state.value.library.resources.any { it.id == configID })
        assertNotNull(model.editingResource.value)
        compose.runOnUiThread { model.dismissError(); model.closeResource() }
        compose.runOnUiThread {
            model.openChainGroup(model.state.value.chains.groups.single())
            model.chainDraft.value = model.chainDraft.value!!.copy(name = "Unsaved chain name")
            model.previewChainEditor()
        }
        compose.waitUntil(30_000) { model.state.value.resourceCheck != null || model.state.value.error != null }
        assertNull(model.state.value.error)
        assertEquals("Landing fixture", model.state.value.chains.groups.single().name)
        assertEquals("Unsaved chain name", model.state.value.resourceCheck!!.graph.node(model.state.value.resourceCheck!!.key)?.name)
        assertNotNull(model.chainDraft.value)
    }
}
