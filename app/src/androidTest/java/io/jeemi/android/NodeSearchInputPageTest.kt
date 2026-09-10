package io.jeemi.android

import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.lifecycle.ViewModelProvider
import androidx.test.platform.app.InstrumentationRegistry
import io.jeemi.android.domain.Library
import io.jeemi.android.ui.JeemiViewModel
import org.junit.Rule
import org.junit.Test

class NodeSearchInputPageTest {
    @get:Rule(order = 0) val library = TestLibrary { app ->
        val profile = app.engine.normalize("Input fixture", """
            proxies:
              - {name: Pro.HK.gm, type: socks5, server: 192.0.2.1, port: 1080}
              - {name: Pro.HK.ev, type: socks5, server: 192.0.2.2, port: 1080}
              - {name: Pro.JP.gm, type: socks5, server: 192.0.2.3, port: 1080}
            proxy-groups:
              - {name: Main, type: select, proxies: [Pro.HK.gm, Pro.HK.ev, Pro.JP.gm]}
            rules: ['MATCH,Main']
        """.trimIndent())
        Library(listOf(profile), profile.id)
    }
    @get:Rule(order = 1) val compose = createAndroidComposeRule<MainActivity>()
    private fun label(id: Int) = compose.activity.getString(id)
    private fun input() = compose.onNode(hasSetTextAction() and hasText(label(R.string.search_nodes)))
    private fun results(vararg expected: String) {
        compose.waitForIdle()
        listOf("Pro.HK.gm", "Pro.HK.ev", "Pro.JP.gm").forEach { name ->
            if (name in expected) compose.onNodeWithText(name).assertIsDisplayed()
            else compose.onNodeWithText(name).assertDoesNotExist()
        }
    }

    @Test fun incrementalSpacesAndFullWidthOperatorsFilterActualCards() {
        val model = ViewModelProvider(compose.activity)[JeemiViewModel::class.java]
        compose.waitUntil(15000) { model.state.value.loaded }
        compose.onNode(hasText(label(R.string.subscriptions)) and hasClickAction()).performClick()
        compose.onNodeWithContentDescription(label(R.string.search_nodes)).performClick()
        input().performTextReplacement("hk")
        results("Pro.HK.gm", "Pro.HK.ev")
        // Names use dots: an untrimmed trailing space cannot accidentally match.
        input().performTextInput(" ")
        results("Pro.HK.gm", "Pro.HK.ev")
        input().performTextInput("& gm")
        results("Pro.HK.gm")
        listOf(" hk ", "\u3000hk\u3000", "\u00a0hk\u00a0", "\ufeffhk\ufeff", "hk＆").forEach {
            input().performTextReplacement(it)
            results("Pro.HK.gm", "Pro.HK.ev")
        }
        listOf("hk & gm", "hk＆gm", "\u3000hk\u3000＆\u3000gm\u3000", "hk ＆ gm ！ev").forEach {
            input().performTextReplacement(it)
            results("Pro.HK.gm")
            input().assertTextContains(it)
        }
        input().performTextReplacement("hk｜jp ＆ gm ！ev")
        results("Pro.HK.gm", "Pro.JP.gm")
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val prefix = InstrumentationRegistry.getArguments().getString("visualPrefix", "portrait")
        require(prefix.matches(Regex("[A-Za-z0-9_-]+")))
        val directory = java.io.File(compose.activity.getExternalFilesDir(null), "search-ime").apply { mkdirs() }
        instrumentation.uiAutomation.takeScreenshot().also { bitmap ->
            java.io.File(directory, "$prefix-fullwidth.png").outputStream().use {
                bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it)
            }
            bitmap.recycle()
        }
        input().performTextReplacement("｜ ＆ ！ ")
        compose.onNodeWithText("Main").assertExists()
        compose.onNodeWithText(label(R.string.no_results)).assertDoesNotExist()
    }
}
