package io.jeemi.android

import android.graphics.Bitmap
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.Column
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.dp
import androidx.test.platform.app.InstrumentationRegistry
import io.jeemi.android.ui.ProxyNodeCard
import io.jeemi.android.ui.SelectorGroupCard
import io.jeemi.android.ui.theme.JeemiTheme
import io.jeemi.android.data.SelectorIcons
import io.jeemi.android.domain.AppTheme
import io.jeemi.android.domain.ProxyGroup
import kotlinx.coroutines.CompletableDeferred
import java.io.File
import java.util.concurrent.atomic.AtomicInteger
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test

class NodeCardTest {
    @get:Rule val compose = createComposeRule()
    @Test fun smallCardRetainsIndependentNodeAndLatencyActions() {
        val name = "A long synthetic node name that must remain one line"
        var selections = 0; var tests = 0
        compose.setContent { MaterialTheme {
            ProxyNodeCard(name, "Shadowsocks", 156, 3, false, true, true, false, Modifier.width(102.dp),
                { selections++ }, { tests++ })
        } }
        compose.onNodeWithText(name).performClick()
        val label = InstrumentationRegistry.getInstrumentation().targetContext.getString(R.string.test_node, name)
        compose.onNodeWithContentDescription(label).performClick()
        compose.onNodeWithText("156 ms").assertIsDisplayed()
        compose.runOnIdle { assertEquals(1, selections); assertEquals(1, tests) }
    }

    @Test fun nestedCardSharesNodeGeometryAndItsBadgeOnlyBrowses() {
        val group = ProxyGroup("🏡 A long nested selector name", "select", listOf("HK"))
        var columns by mutableIntStateOf(3)
        val icons = SelectorIcons(fetch = { null })
        var selections = 0; var opens = 0
        compose.setContent { JeemiTheme(AppTheme.SYSTEM) { Column {
            val width = when (columns) { 1 -> 300.dp; 2 -> 152.dp; else -> 102.dp }
            SelectorGroupCard(group, 156, columns, false, true, true, Modifier.width(width).testTag("nested-card"),
                icons, { selections++ }, { opens++ })
            ProxyNodeCard("A long synthetic node name", "Shadowsocks", 156, columns, false, true, true, false,
                Modifier.width(width).testTag("node-card"), {}, {})
        } } }
        for (count in listOf(3, 2, 1)) {
            compose.runOnIdle { columns = count }
            val nested = compose.onNodeWithTag("nested-card").fetchSemanticsNode().boundsInRoot
            val node = compose.onNodeWithTag("node-card").fetchSemanticsNode().boundsInRoot
            assertEquals(node.height, nested.height, 0.1f)
            assertEquals(node.width, nested.width, 0.1f)
            compose.onNodeWithText(group.name).performClick()
            val label = InstrumentationRegistry.getInstrumentation().targetContext.getString(R.string.open_nested_selector, group.name)
            compose.onNodeWithContentDescription(label).performClick()
        }
        compose.runOnIdle { assertEquals(3, selections); assertEquals(3, opens) }
    }

    @Test fun nestedIconMatchesTheNameFontAndMissingImagesReserveNoSpace() {
        val loaded = CompletableDeferred<Bitmap?>()
        val calls = AtomicInteger()
        val icons = SelectorIcons(fetch = {
            calls.incrementAndGet()
            if (it.endsWith("/a.png")) loaded.await() else null
        })
        var group by mutableStateOf(ProxyGroup("🏡 Nested selector", "select", listOf("HK"),
            icon = "https://images.example.invalid/a.png"))
        var columns by mutableIntStateOf(3)
        compose.setContent { JeemiTheme(AppTheme.SYSTEM) { Column {
            val width = when (columns) { 1 -> 300.dp; 2 -> 152.dp; else -> 102.dp }
            SelectorGroupCard(group, 156, columns, false, true, true,
                Modifier.width(width).testTag("nested-card"), icons, {}, {})
            ProxyNodeCard("HK.Test", "Shadowsocks", 156, columns, false, true, true, false,
                Modifier.width(width).testTag("node-card"), {}, {})
        } } }
        fun nameBounds() = compose.onNodeWithText(group.name, useUnmergedTree = true).fetchSemanticsNode().boundsInRoot
        fun assertNoIcon() {
            compose.onNodeWithTag("node-name-icon", useUnmergedTree = true).assertDoesNotExist()
            val nodeName = compose.onNodeWithText("HK.Test", useUnmergedTree = true).fetchSemanticsNode().boundsInRoot
            assertEquals(nodeName.left, nameBounds().left, 0.1f)
        }
        assertNoIcon() // A pending image has no placeholder or separate emoji/default icon.
        loaded.complete(Bitmap.createBitmap(64, 64, Bitmap.Config.ARGB_8888).apply { eraseColor(0xff9933cc.toInt()) })
        compose.waitUntil(5000) { compose.onAllNodesWithTag("node-name-icon", useUnmergedTree = true).fetchSemanticsNodes().size == 1 }
        for (count in listOf(3, 2, 1)) {
            compose.runOnIdle { columns = count }
            val layouts = mutableListOf<TextLayoutResult>()
            compose.onNodeWithText(group.name, useUnmergedTree = true)
                .performSemanticsAction(SemanticsActions.GetTextLayoutResult) { it(layouts) }
            val expected = with(compose.density) { layouts.single().layoutInput.style.fontSize.toPx() }
            val icon = compose.onNodeWithTag("node-name-icon", useUnmergedTree = true).fetchSemanticsNode().boundsInRoot
            assertEquals(expected, icon.width, 0.6f)
            assertEquals(expected, icon.height, 0.6f)
            val nested = compose.onNodeWithTag("nested-card").fetchSemanticsNode().boundsInRoot
            val node = compose.onNodeWithTag("node-card").fetchSemanticsNode().boundsInRoot
            assertEquals(node.height, nested.height, 0.1f)
        }
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val prefix = InstrumentationRegistry.getArguments().getString("visualPrefix", "phone")!!
        require(prefix.matches(Regex("[A-Za-z0-9_-]+")))
        val file = File(instrumentation.targetContext.getExternalFilesDir(null), "nested-icons-$prefix.png")
        val capture = compose.onRoot().captureToImage().asAndroidBitmap()
        file.outputStream().use { capture.compress(Bitmap.CompressFormat.PNG, 100, it) }
        instrumentation.uiAutomation.executeShellCommand("cp ${file.absolutePath} /data/local/tmp/${file.name}").use {
            java.io.FileInputStream(it.fileDescriptor).use { input -> input.readBytes() }
        }
        compose.runOnIdle { group = group.copy(icon = "https://images.example.invalid/missing.png") }
        compose.waitUntil(5000) { calls.get() == 2 }
        assertNoIcon()
        for (address in listOf("file:///invalid.png", "")) {
            compose.runOnIdle { group = group.copy(icon = address) }
            assertNoIcon()
        }
        assertEquals(2, calls.get())
    }
}
