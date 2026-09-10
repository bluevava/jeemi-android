package io.jeemi.android
import android.content.pm.ActivityInfo
import android.content.res.Configuration
import androidx.appcompat.app.AppCompatDelegate
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.core.os.LocaleListCompat
import io.jeemi.android.data.GoBusinessEngine
import io.jeemi.android.ui.FieldHelpTopics
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Rule
import org.junit.Test

class LocaleAndPortraitTest {
    @get:Rule(order = 0) val library = TestLibrary()
    @get:Rule(order = 1) val compose = createAndroidComposeRule<MainActivity>()
    @Before fun english() = changeLanguage("en")
    @After fun restoreEnglish() = changeLanguage("en")
    private fun changeLanguage(tag: String) {
        compose.activityRule.scenario.onActivity { AppCompatDelegate.setApplicationLocales(LocaleListCompat.forLanguageTags(tag)) }
        compose.waitUntil(15_000) { compose.activity.resources.configuration.locales[0].language == tag.substringBefore('-') }
        compose.waitForIdle()
    }
    private fun text(id: Int) = compose.activity.getString(id)
    private fun click(id: Int) {
        val node = compose.onNode(hasText(text(id)) and hasClickAction())
        runCatching { node.performScrollTo() }
        node.performClick()
    }
    private fun dialogButton(id: Int) = compose.onAllNodes(hasText(text(id)) and hasClickAction() and hasAnyAncestor(isDialog())).onLast()
    private fun waitLoaded() = compose.waitUntil(15_000) { compose.onAllNodesWithText(text(R.string.runtime_stopped)).fetchSemanticsNodes().isNotEmpty() }
    private fun chooseChinese() {
        compose.onNodeWithContentDescription(text(R.string.settings)).performClick()
        click(R.string.language)
        compose.onNode(hasText(text(R.string.language)) and hasText(text(R.string.language_english)) and hasClickAction()).performClick()
        compose.onNode(hasText("简体中文") and hasAnyAncestor(isPopup())).performClick()
    }
    @Test fun languageDraftCancelAndSave() {
        waitLoaded()
        chooseChinese()
        compose.onNode(hasText(text(R.string.cancel)) and hasClickAction()).performClick()
        assertEquals("en", compose.activity.resources.configuration.locales[0].language)
        chooseChinese()
        compose.onNode(hasText(text(R.string.save)) and hasClickAction()).performClick()
        compose.waitUntil(15_000) { compose.onAllNodesWithText("状态控制").fetchSemanticsNodes().isNotEmpty() }
        assertEquals("zh", AppCompatDelegate.getApplicationLocales()[0]?.language)
    }
    @Test fun portraitHasNoRemovedHomeControlsAndCoreCheckLivesInSettings() {
        waitLoaded()
        assertEquals(ActivityInfo.SCREEN_ORIENTATION_PORTRAIT, compose.activity.requestedOrientation)
        assertEquals(Configuration.ORIENTATION_PORTRAIT, compose.activity.resources.configuration.orientation)
        listOf(R.string.traffic, R.string.home_intro, R.string.core_pending, R.string.inherit, R.string.language, R.string.appearance, R.string.core_check).forEach {
            compose.onNodeWithText(text(it)).assertDoesNotExist()
        }
        compose.onNodeWithContentDescription(text(R.string.settings)).performClick()
        click(R.string.core_management)
        click(R.string.core_check)
        compose.waitUntil(15_000) { compose.onAllNodes(hasText("Mihomo Meta", substring = true)).fetchSemanticsNodes().isNotEmpty() }
    }
    @Test fun exposedFieldsHaveCompleteSpecificBilingualHelp() {
        val fields = GoBusinessEngine().catalog().filterNot { it.hidden }
        assertTrue(fields.isNotEmpty())
        assertEquals(emptyList<String>(), fields.filterNot { it.path in FieldHelpTopics }.map { it.path })
        for (tag in listOf("en", "zh-CN")) {
            val config = Configuration(compose.activity.resources.configuration).apply { setLocale(java.util.Locale.forLanguageTag(tag)) }
            val context = compose.activity.createConfigurationContext(config)
            fields.forEach { field -> val help = FieldHelpTopics.getValue(field.path)
                assertTrue(context.getString(help.purpose).isNotBlank()); assertTrue(context.getString(help.scenarios).isNotBlank()); assertTrue(context.getString(help.cautions).isNotBlank())
            }
        }
    }
    @Test fun nestedFieldDraftSurvivesLocaleAndActivityRecreation() {
        waitLoaded(); click(R.string.configuration); click(R.string.new_resource)
        compose.onNode(hasText(text(R.string.name)) and hasSetTextAction() and hasAnyAncestor(isDialog())).performTextInput("Field draft")
        dialogButton(R.string.add_field).performScrollTo().performClick()
        compose.onNode(hasText(text(R.string.search_fields)) and hasSetTextAction()).performTextInput("keep-alive-interval")
        compose.onNode(hasText("keep-alive-interval") and hasClickAction() and !hasSetTextAction()).performScrollTo().performClick()
        compose.onNode(hasSetTextAction() and hasAnyAncestor(isDialog()) and hasText("15")).performTextReplacement("42")
        changeLanguage("zh-CN")
        compose.activityRule.scenario.recreate()
        compose.waitForIdle()
        compose.onNode(hasSetTextAction() and hasText("42")).assertExists()
        dialogButton(R.string.apply_to_draft).performClick()
        compose.waitUntil(15_000) { compose.onAllNodes(hasText("keep-alive-interval") and hasClickAction() and !hasSetTextAction()).fetchSemanticsNodes().isNotEmpty() }
        compose.onNode(hasText("keep-alive-interval") and hasClickAction() and !hasSetTextAction()).performScrollTo().performClick()
        compose.onNode(hasSetTextAction() and hasText("42")).assertExists()
        dialogButton(R.string.cancel).performClick()
        compose.onNode(hasSetTextAction() and hasText("Field draft")).assertExists()
    }
    @Test fun systemLanguageChangesKeepTheActivityAndDraft() {
        waitLoaded(); click(R.string.subscriptions); click(R.string.import_action); click(R.string.from_text)
        compose.onNode(hasText(text(R.string.subscription_name_optional)) and hasSetTextAction() and hasAnyAncestor(isDialog())).performTextInput("Retained draft")
        val originalActivity = compose.activity
        repeat(12) { index ->
            changeLanguage(if (index % 2 == 0) "zh-CN" else "en")
            assertSame(originalActivity, compose.activity)
            compose.onNode(hasSetTextAction() and hasText("Retained draft")).assertExists()
        }
    }
}
