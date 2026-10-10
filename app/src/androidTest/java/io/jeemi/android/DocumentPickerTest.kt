package io.jeemi.android

import android.accessibilityservice.AccessibilityService
import android.graphics.Rect
import android.provider.Settings
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.lifecycle.ViewModelProvider
import androidx.test.platform.app.InstrumentationRegistry
import io.jeemi.android.domain.ResourceKind
import io.jeemi.android.ui.JeemiViewModel
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import java.io.FileInputStream

class DocumentPickerTest {
    @get:Rule(order = 0) val library = TestLibrary(initial = ::resourceWorkspaceFixture)
    @get:Rule(order = 1) val compose = createAndroidComposeRule<MainActivity>()
    private val model get() = ViewModelProvider(compose.activity)[JeemiViewModel::class.java]
    private val automation get() = InstrumentationRegistry.getInstrumentation().uiAutomation
    private fun click(id: Int) = compose.onNode(hasText(compose.activity.getString(id)) and hasClickAction())
        .performScrollTo().performClick()
    private fun eventually(condition: () -> Boolean) {
        val deadline = android.os.SystemClock.elapsedRealtime() + 15_000
        while (!condition()) {
            check(android.os.SystemClock.elapsedRealtime() < deadline) { "document_picker_timeout" }
            Thread.sleep(100)
        }
    }
    private fun back(attempt: Int) {
        val gestures = Settings.Secure.getInt(compose.activity.contentResolver, "navigation_mode", 0) == 2
        if (!gestures || attempt == 0) {
            assertTrue(automation.performGlobalAction(AccessibilityService.GLOBAL_ACTION_BACK))
        } else {
            val bounds = Rect().also { automation.rootInActiveWindow!!.getBoundsInScreen(it) }
            val from = if (attempt == 1) bounds.left + 1 else bounds.right - 1
            val to = if (attempt == 1) bounds.left + bounds.width() / 3 else bounds.right - bounds.width() / 3
            automation.executeShellCommand("input swipe $from ${bounds.centerY()} $to ${bounds.centerY()} 300").use {
                FileInputStream(it.fileDescriptor).use { output -> output.readBytes() }
            }
        }
    }

    @Test fun systemBackWithoutSelectingKeepsDraftAndAllowsReopening() {
        compose.waitUntil(20_000) { model.state.value.loaded }
        compose.onNodeWithTag("navigation-CONFIG").performClick()
        val before = model.state.value.library
        val repository = (compose.activity.application as JeemiApplication).repository
        val savedBefore = repository.load()
        val draft = before.resources.first { it.kind == ResourceKind.CONFIG }.copy(description = "Unsaved picker draft")
        compose.runOnUiThread { model.openResource(draft) }
        click(R.string.resource_package)
        repeat(3) { attempt ->
            click(R.string.import_package)
            eventually { automation.rootInActiveWindow?.packageName?.toString()?.endsWith("documentsui") == true }
            back(attempt)
            eventually { automation.rootInActiveWindow?.packageName == compose.activity.packageName }
            compose.onNodeWithTag("resource-save").assertIsDisplayed().assertIsEnabled()
            assertEquals(draft, model.editingResource.value)
            assertEquals(before, model.state.value.library)
            assertFalse(model.state.value.busy)
            assertNull(model.state.value.error)
            assertNull(model.state.value.packagePreview)
            assertEquals(savedBefore, repository.load())
        }
        compose.onNode(hasText(compose.activity.getString(R.string.cancel)) and hasClickAction()).performClick()
        compose.waitUntil { model.editingResource.value == null }
        compose.onNodeWithTag("navigation-CONFIG").assertIsSelected()
    }
}
