package io.jeemi.android

import androidx.appcompat.app.AppCompatDelegate
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.core.os.LocaleListCompat
import androidx.lifecycle.ViewModelProvider
import androidx.test.platform.app.InstrumentationRegistry
import io.jeemi.android.domain.*
import io.jeemi.android.ui.JeemiViewModel
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import java.io.File

class HomeControlsTest {
    @get:Rule(order = 0) val library = TestLibrary { app ->
        val subscription = app.engine.normalize("Home fixture", "rules: ['MATCH,DIRECT']\n")
        Library(listOf(subscription), subscription.id)
    }
    @get:Rule(order = 1) val compose = createAndroidComposeRule<MainActivity>()
    private val model get() = ViewModelProvider(compose.activity)[JeemiViewModel::class.java]
    private val app get() = compose.activity.application as JeemiApplication
    private fun text(id: Int) = compose.activity.getString(id)
    private fun saved() {
        compose.waitUntil(30_000) { !model.state.value.busy }
        compose.waitForIdle()
        assertNull(model.state.value.error)
    }
    private fun capture(name: String) {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        compose.waitForIdle()
        Thread.sleep(350)
        instrumentation.waitForIdleSync()
        val bitmap = instrumentation.uiAutomation.takeScreenshot()
        val file = File(app.getExternalFilesDir(null), "home-$name.png")
        file.outputStream().use { bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it) }
        bitmap.recycle()
        instrumentation.uiAutomation.executeShellCommand("cp ${file.absolutePath} /data/local/tmp/jeemi-home-$name.png").use {
            java.io.FileInputStream(it.fileDescriptor).use { input -> input.readBytes() }
        }
    }

    @Test fun stackAndThemePersistOnHomeWithCollapsedRuntimeAndBilingualLayout() {
        compose.waitUntil(20_000) { model.state.value.loaded }
        assertEquals(TunStack.MIPS, model.state.value.library.preferences.tunStack)
        val previous = AppCompatDelegate.getApplicationLocales()
        try {
            for (language in listOf("zh-CN", "en")) {
                compose.runOnUiThread { AppCompatDelegate.setApplicationLocales(LocaleListCompat.forLanguageTags(language)) }
                compose.waitForIdle()
                compose.onNodeWithTag("home-stack-MIPS").performScrollTo().assertIsSelected()
                compose.onNodeWithText(text(R.string.runtime_basics)).assertDoesNotExist()
                val tops = TunStack.entries.map { compose.onNodeWithTag("home-stack-${it.name}").fetchSemanticsNode().positionInRoot.y }
                assertTrue("stack must occupy one row", tops.max() - tops.min() < 1f)
                val themeTops = AppTheme.entries.map { compose.onNodeWithTag("home-theme-${it.name}").fetchSemanticsNode().positionInRoot.y }
                assertTrue("appearance must occupy one row", themeTops.max() - themeTops.min() < 1f)
                compose.onNodeWithTag("home-theme-SYSTEM").assertTextContains(if (language == "en") "System" else "系统")
                capture(language)
                for (stack in listOf(TunStack.SYSTEM, TunStack.GVISOR, TunStack.MIXED, TunStack.MIPS)) {
                    compose.onNodeWithTag("home-stack-${stack.name}").performScrollTo().performClick()
                    compose.waitUntil(30_000) { model.state.value.library.preferences.tunStack == stack || model.state.value.error != null }
                    saved()
                    compose.onNodeWithTag("home-stack-${stack.name}").assertIsSelected()
                    assertEquals(stack, app.repository.load().preferences.tunStack)
                    assertTrue(model.state.value.candidate!!.yaml.contains("stack: ${stack.configValue}"))
                }
                val revision = model.state.value.candidate!!.revision
                compose.onNodeWithTag("home-theme-DARK").performScrollTo().performClick()
                compose.waitUntil(30_000) { model.state.value.library.preferences.theme == AppTheme.DARK }
                saved()
                assertEquals(AppTheme.DARK, app.repository.load().preferences.theme)
                assertEquals(revision, model.state.value.candidate!!.revision)
                capture("dark-$language")
                compose.onNodeWithTag("home-theme-SYSTEM").performScrollTo().performClick()
                compose.waitUntil(30_000) { model.state.value.library.preferences.theme == AppTheme.SYSTEM }
                saved()
            }
            compose.onNodeWithContentDescription(text(R.string.settings)).performClick()
            compose.onNodeWithText(text(R.string.appearance)).assertDoesNotExist()
            compose.onNode(hasText(text(R.string.cancel)) and hasClickAction()).performClick()
            compose.onNodeWithText(text(R.string.about_jeemi)).performScrollTo().assertIsDisplayed()
        } finally { compose.runOnUiThread { AppCompatDelegate.setApplicationLocales(previous) } }
    }
}
