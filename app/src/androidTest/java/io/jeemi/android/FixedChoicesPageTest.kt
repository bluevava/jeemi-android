package io.jeemi.android

import androidx.appcompat.app.AppCompatDelegate
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.text.TextLayoutResult
import androidx.core.os.LocaleListCompat
import androidx.lifecycle.ViewModelProvider
import androidx.test.platform.app.InstrumentationRegistry
import io.jeemi.android.domain.ResourceKind
import io.jeemi.android.ui.JeemiViewModel
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import java.io.File

class FixedChoicesPageTest {
    @get:Rule(order = 0) val library = TestLibrary(initial = ::resourceWorkspaceFixture)
    @get:Rule(order = 1) val compose = createAndroidComposeRule<MainActivity>()
    private val model get() = ViewModelProvider(compose.activity)[JeemiViewModel::class.java]
    private val app get() = compose.activity.application as JeemiApplication
    private fun label(id: Int) = compose.activity.getString(id)
    private fun option(field: Int, value: String) = compose.onNode(hasText(value) and
        hasAnyAncestor(hasContentDescription(label(field))) and hasClickAction())
    private fun choose(field: Int, value: String) = option(field, value).performScrollTo().performClick().assertIsSelected()
    private fun singleRow(field: Int, values: List<String>) {
        val tops = values.map { option(field, it).fetchSemanticsNode().positionInRoot.y }
        assertTrue("${label(field)} must stay in one row", tops.max() - tops.min() < 1f)
    }
    private fun content() = JSONObject(model.editingResource.value!!.content)
    private fun language(tag: String) {
        compose.runOnUiThread { AppCompatDelegate.setApplicationLocales(LocaleListCompat.forLanguageTags(tag)) }
        compose.waitForIdle()
    }
    private fun ready() {
        compose.waitUntil(20_000) { model.state.value.loaded }
        compose.onNodeWithTag("navigation-CONFIG").performClick()
    }
    private fun open(kind: ResourceKind) {
        compose.runOnUiThread { model.openResource(model.state.value.library.resources.first { it.kind == kind }) }
    }
    private fun capture(name: String) {
        compose.mainClock.advanceTimeBy(300)
        compose.waitForIdle()
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        instrumentation.waitForIdleSync()
        Thread.sleep(350)
        val prefix = InstrumentationRegistry.getArguments().getString("visualPrefix", "phone")
        require(prefix.matches(Regex("[A-Za-z0-9_-]+")))
        val directory = File(app.getExternalFilesDir(null), "fixed-choices").apply { mkdirs() }
        val file = File(directory, "$prefix-$name.png")
        instrumentation.uiAutomation.takeScreenshot().also { bitmap ->
            file.outputStream().use { bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it) }
            bitmap.recycle()
        }
        instrumentation.uiAutomation.executeShellCommand("cp " + file.absolutePath + " /data/local/tmp/jeemi-choices-" + file.name).use {
            java.io.FileInputStream(it.fileDescriptor).use { output -> output.readBytes() }
        }
    }

    @Test fun ruleSourceBehaviorAndHttpFormatSwitchWithoutMenusAndKeepDraftAcrossLanguages() {
        ready()
        val previous = AppCompatDelegate.getApplicationLocales()
        val saved = app.repository.load()
        val current = model.state.value.library
        try {
            language("zh-CN"); open(ResourceKind.RULES)
            option(R.string.source_type, label(R.string.source_inline)).assertIsSelected()
            option(R.string.rule_behavior, "classical").assertIsSelected()
            choose(R.string.rule_behavior, "domain")
            choose(R.string.source_type, label(R.string.source_http))
            choose(R.string.rule_format, "mrs")
            singleRow(R.string.source_type, listOf(label(R.string.source_inline), label(R.string.source_http)))
            singleRow(R.string.rule_behavior, listOf("classical", "domain", "ipcidr"))
            singleRow(R.string.rule_format, listOf("yaml", "text", "mrs"))
            assertEquals("http", content().getString("sourceType"))
            assertEquals("domain", content().getString("behavior"))
            assertEquals("mrs", content().getString("format"))
            compose.onAllNodes(isPopup()).assertCountEquals(0)
            capture("rules-zh")
            language("en")
            option(R.string.source_type, label(R.string.source_http)).assertIsSelected()
            option(R.string.rule_behavior, "domain").assertIsSelected()
            option(R.string.rule_format, "mrs").assertIsSelected()
            singleRow(R.string.rule_behavior, listOf("classical", "domain", "ipcidr"))
            capture("rules-en")
            choose(R.string.rule_format, "yaml")
            choose(R.string.rule_behavior, "classical")
            option(R.string.rule_format, "mrs").assertDoesNotExist()
            choose(R.string.source_type, label(R.string.source_inline))
            compose.onNodeWithTag("resource-save").assertIsDisplayed()
            compose.onNode(hasText(label(R.string.cancel)) and hasClickAction() and hasAnyAncestor(isDialog())).performClick()
            assertEquals(saved, app.repository.load())
            assertEquals(current, model.state.value.library)
        } finally { compose.runOnUiThread { AppCompatDelegate.setApplicationLocales(previous) } }
    }

    @Test fun groupOutputAndFourSelectorTypesSaveWhileResourceNamesRemainMenus() {
        ready()
        val previous = AppCompatDelegate.getApplicationLocales()
        try {
            language("zh-CN"); open(ResourceKind.GROUPS)
            choose(R.string.selector_type, "load-balance")
            singleRow(R.string.selector_type, listOf("select", "url-test", "fallback", "load-balance"))
            val layouts = mutableListOf<TextLayoutResult>()
            compose.onNodeWithText("load-balance", useUnmergedTree = true)
                .performSemanticsAction(SemanticsActions.GetTextLayoutResult) { it(layouts) }
            if (app.resources.configuration.fontScale == 1f && layouts.single().lineCount == 2)
                assertEquals("load-", "load-balance".take(layouts.single().getLineEnd(0, visibleEnd = true)))
            assertEquals("load-balance", content().getString("type"))
            capture("selector-zh")
            choose(R.string.selector_type, "select")
            compose.onNode(hasText(label(R.string.rule_set_references)) and hasClickAction()).performScrollTo().performClick()
            choose(R.string.rule_output, label(R.string.output_provider))
            singleRow(R.string.rule_output, listOf(label(R.string.output_inline), label(R.string.output_provider)))
            assertEquals("rule-set", content().getString("ruleOutput"))
            capture("group-output-zh")
            language("en")
            option(R.string.rule_output, label(R.string.output_provider)).assertIsSelected()
            capture("group-output-en")
            compose.onNodeWithTag("resource-save").performClick()
            compose.waitUntil(60_000) { model.editingResource.value == null || model.state.value.error != null }
            assertNull(model.state.value.error)
            val group = app.repository.load().resources.first { it.kind == ResourceKind.GROUPS }
            assertEquals("rule-set", JSONObject(group.content).getString("ruleOutput"))
            open(ResourceKind.CONFIG)
            compose.onNode(hasText(label(R.string.local_routing)) and hasClickAction()).performScrollTo().performClick()
            choose(R.string.local_match, label(R.string.target_selector))
            compose.onNode(hasText(label(R.string.target_selector)) and hasClickAction() and
                !hasAnyAncestor(hasContentDescription(label(R.string.local_match)))).performScrollTo().performClick()
            compose.onNode(hasText(group.name) and hasAnyAncestor(isPopup())).performClick()
            assertEquals(group.id, content().getJSONObject("resourcePlan").getJSONObject("match").getString("selectorId"))
        } finally { compose.runOnUiThread { AppCompatDelegate.setApplicationLocales(previous) } }
    }
}
