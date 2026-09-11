package io.jeemi.android

import android.graphics.Bitmap
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import androidx.lifecycle.compose.LocalLifecycleOwner
import io.jeemi.android.data.GoBusinessEngine
import io.jeemi.android.data.SelectorIcons
import io.jeemi.android.domain.*
import io.jeemi.android.ui.SelectorHeader
import io.jeemi.android.ui.selectorTitle
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import java.util.concurrent.atomic.AtomicInteger

class SelectorIconTest {
    @get:Rule val compose = createComposeRule()

    @Test fun imageWinsThenChangedOrInvalidIconFallsBackWithoutRenamingTheGroup() {
        val loaded = CompletableDeferred<Bitmap?>()
        val calls = AtomicInteger()
        val icons = SelectorIcons(fetch = { calls.incrementAndGet(); if (it.endsWith("/a.png")) loaded.await() else null })
        val name = "🇨🇳 Domestic"
        var group by mutableStateOf(ProxyGroup(name, "select", listOf("DIRECT"), icon = "https://images.example.invalid/a.png"))
        var toggles = 0
        compose.setContent { MaterialTheme { Column(Modifier.width(320.dp)) {
            SelectorHeader(group, "DIRECT", false, false, icons) { toggles++ }
        } } }
        compose.onNodeWithText("🇨🇳", useUnmergedTree = true).assertIsDisplayed()
        compose.onNodeWithText("Domestic", useUnmergedTree = true).assertIsDisplayed()
        val bitmap = Bitmap.createBitmap(64, 64, Bitmap.Config.ARGB_8888).apply { eraseColor(0xff9933cc.toInt()) }
        loaded.complete(bitmap)
        compose.waitUntil(5000) { compose.onAllNodesWithTag("selector-icon-image", useUnmergedTree = true).fetchSemanticsNodes().size == 1 }
        compose.onNodeWithText("🇨🇳", useUnmergedTree = true).assertDoesNotExist()
        compose.onNodeWithContentDescription(name).performClick()
        compose.runOnIdle {
            assertEquals(name, group.name)
            assertEquals(1, toggles)
            group = group.copy(icon = "https://images.example.invalid/missing.png")
        }
        compose.waitUntil(5000) { calls.get() == 2 }
        compose.onNodeWithTag("selector-icon-image", useUnmergedTree = true).assertDoesNotExist()
        compose.onNodeWithText("🇨🇳", useUnmergedTree = true).assertIsDisplayed()
        compose.runOnIdle {
            group = group.copy(icon = "[image](https://images.example.invalid/a.png)")
        }
        compose.onNodeWithTag("selector-icon-image", useUnmergedTree = true).assertDoesNotExist()
        compose.onNodeWithText("🇨🇳", useUnmergedTree = true).assertIsDisplayed()
        compose.runOnIdle { group = group.copy(icon = "") }
        compose.onNodeWithText("🇨🇳", useUnmergedTree = true).assertIsDisplayed()
        compose.runOnIdle { group = group.copy(name = "Domestic") }
        compose.onNodeWithText("Domestic", useUnmergedTree = true).assertIsDisplayed()
        assertEquals(2, calls.get())
    }

    @Test fun backgroundAndLeavingTheTitleCancelPendingImages() {
        val owner = object : LifecycleOwner { override val lifecycle = LifecycleRegistry(this) }
        val started = AtomicInteger()
        val stopped = AtomicInteger()
        val icons = SelectorIcons(fetch = {
            started.incrementAndGet()
            try { awaitCancellation() } finally { stopped.incrementAndGet() }
        })
        var visible by mutableStateOf(true)
        compose.runOnUiThread { owner.lifecycle.currentState = Lifecycle.State.RESUMED }
        compose.setContent { CompositionLocalProvider(LocalLifecycleOwner provides owner) {
            MaterialTheme { if (visible) SelectorHeader(
                ProxyGroup("🏡 Domestic", "select", listOf("DIRECT"), icon = "https://images.example.invalid/slow.png"),
                null, false, false, icons) { }
            }
        } }
        compose.waitUntil(5000) { started.get() == 1 }
        compose.runOnUiThread { owner.lifecycle.currentState = Lifecycle.State.CREATED }
        compose.waitUntil(5000) { stopped.get() == 1 }
        compose.runOnUiThread { owner.lifecycle.currentState = Lifecycle.State.RESUMED }
        compose.waitUntil(5000) { started.get() == 2 }
        compose.runOnIdle { visible = false }
        compose.waitUntil(5000) { stopped.get() == 2 }
        compose.runOnUiThread { owner.lifecycle.currentState = Lifecycle.State.DESTROYED }
    }

    @Test fun titlesKeepCompleteFlagAndJoinedEmojiAndDoNotTreatNumbersAsIcons() {
        assertEquals("🇨🇳" to "Domestic", selectorTitle("🇨🇳Domestic"))
        assertEquals("👨‍👩‍👧‍👦" to "Family", selectorTitle("👨‍👩‍👧‍👦 Family"))
        assertEquals("" to "1 Domestic", selectorTitle("1 Domestic"))
        assertEquals("" to "Domestic", selectorTitle("Domestic"))
    }

    @Test fun bridgeCarriesTheFinalIconAndLeavesOriginalAndCandidateIdentityIntact() {
        val engine = GoBusinessEngine()
        val original = "proxy-groups: [{name: '🏡 Domestic', type: select, icon: 'https://images.example.invalid/source.png', proxies: [DIRECT]}]\nrules: ['MATCH,🏡 Domestic']\n"
        val item = engine.normalize("Icon fixture", original)
        assertEquals("https://images.example.invalid/source.png", engine.inspect(item).groups.single().icon)
        val script = LocalResource("icons", "Icons", ResourceKind.SCRIPT,
            "function main(config) { config['proxy-groups'][0].icon = 'https://images.example.invalid/final.png'; return config; }")
        val selected = item.copy(handlerId = script.id)
        val before = engine.project(selected, Preferences(), listOf(script))
        assertEquals("https://images.example.invalid/final.png", before.structure.groups.single().icon)
        assertEquals("🏡 Domestic", before.structure.groups.single().name)
        assertEquals(original, selected.original)
        assertEquals(before.revision, engine.project(selected, Preferences(), listOf(script)).revision)
    }

    @Test fun cacheCoalescesUrlsBoundsEntriesAndExpiresFailures() = runBlocking {
        val calls = AtomicInteger()
        var now = 0L
        val bitmap = Bitmap.createBitmap(64, 64, Bitmap.Config.ARGB_8888)
        val icons = SelectorIcons(fetch = { calls.incrementAndGet(); if (it.endsWith("missing")) null else bitmap }, clock = { now })
        val url = "https://images.example.invalid/a"
        coroutineScope { List(10) { async { icons.load(url) } }.awaitAll().forEach { assertSame(bitmap, it) } }
        assertEquals(1, calls.get())
        repeat(2) { assertNull(icons.load("https://images.example.invalid/missing")) }
        assertEquals(2, calls.get())
        now = 60_001
        assertNull(icons.load("https://images.example.invalid/missing"))
        assertEquals(3, calls.get())
        repeat(64) { icons.load("https://images.example.invalid/entry$it") }
        icons.load(url)
        assertEquals(68, calls.get())
        assertFalse("Eviction must not recycle a bitmap retained by a visible row", bitmap.isRecycled)
        for (invalid in listOf("", "🏡", "file:///a.png", "data:image/png;base64,AA", "https://user:password@images.example.invalid/a", "[image]($url)")) {
            assertNull(icons.load(invalid))
        }
        assertEquals(68, calls.get())
    }

    @Test fun cancellationDoesNotCacheAMissAndMemoryFailurePausesNewLoads() = runBlocking {
        val started = CompletableDeferred<Unit>()
        val calls = AtomicInteger()
        var now = 0L
        val icons = SelectorIcons(fetch = {
            when (calls.incrementAndGet()) {
                1 -> { started.complete(Unit); awaitCancellation() }
                2 -> throw OutOfMemoryError("synthetic")
                else -> null
            }
        }, clock = { now })
        val url = "https://images.example.invalid/a"
        val job = launch { icons.load(url) }
        started.await(); job.cancelAndJoin()
        assertNull(icons.load(url))
        assertEquals(2, calls.get())
        assertNull(icons.load("https://images.example.invalid/b"))
        assertEquals(2, calls.get())
        now = 60_001
        assertNull(icons.load(url))
        assertEquals(3, calls.get())
    }
}
