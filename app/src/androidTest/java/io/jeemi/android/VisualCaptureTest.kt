package io.jeemi.android

import android.graphics.Bitmap
import androidx.appcompat.app.AppCompatDelegate
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.core.os.LocaleListCompat
import androidx.test.platform.app.InstrumentationRegistry
import io.jeemi.android.domain.Library
import org.junit.After
import org.junit.Rule
import org.junit.Test
import java.io.File

/** Synthetic fixtures only; screenshots are taken from the actual Compose UI. */
class VisualCaptureTest {
    @get:Rule(order = 0) val library = TestLibrary { app ->
        val profile = app.engine.normalize("Jeemi Demo", """
            proxies:
              - {name: Hong Kong 01, type: socks5, server: hk.example.invalid, port: 1080}
              - {name: Tokyo 02, type: socks5, server: jp.example.invalid, port: 1080}
              - {name: Singapore 03, type: socks5, server: sg.example.invalid, port: 1080}
            proxy-groups:
              - {name: Proxy selection, type: select, proxies: [Hong Kong 01, Tokyo 02, Singapore 03, DIRECT]}
              - {name: Media, type: select, proxies: [Proxy selection, Hong Kong 01, Tokyo 02]}
            rules: ['MATCH,Proxy selection']
        """.trimIndent()).copy(icon = "🌐", uploadedBytes = 536870912, downloadedBytes = 10737418240,
            totalBytes = 107374182400, expiresAt = 1798761600, updatedAt = 1788912000000)
        Library(subscriptions = listOf(profile), selectedId = profile.id)
    }
    @get:Rule(order = 1) val compose = createAndroidComposeRule<MainActivity>()
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    private fun text(id: Int) = compose.activity.getString(id)
    private fun click(id: Int) {
        val node = compose.onNode(hasText(text(id)) and hasClickAction())
        runCatching { node.performScrollTo() }; node.performClick()
    }
    private fun language(tag: String) {
        compose.activityRule.scenario.onActivity { AppCompatDelegate.setApplicationLocales(LocaleListCompat.forLanguageTags(tag)) }
        compose.waitUntil(15_000) { compose.activity.resources.configuration.locales[0].language == tag.substringBefore('-') }
        compose.waitForIdle()
    }
    @After fun restoreLanguage() { language("en") }
    private fun capture(name: String) {
        compose.waitForIdle(); instrumentation.waitForIdleSync(); Thread.sleep(250)
        val prefix = InstrumentationRegistry.getArguments().getString("visualPrefix", "phone")
        check(prefix.matches(Regex("[A-Za-z0-9_-]+")))
        val root = File(compose.activity.getExternalFilesDir(null), "review").apply { mkdirs() }
        val bitmap = requireNotNull(instrumentation.uiAutomation.takeScreenshot())
        val file = File(root, prefix + "-" + name + ".png")
        file.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        bitmap.recycle()
        // Gradle uninstalls its test application after the run. Keep only these
        // synthetic review images in the emulator's shell-owned temporary area.
        instrumentation.uiAutomation.executeShellCommand("cp " + file.absolutePath + " /data/local/tmp/jeemi-v2-" + file.name).use {
            java.io.FileInputStream(it.fileDescriptor).use { output -> output.readBytes() }
        }
    }
    @Test fun portraitPagesHelpAndFieldModalRemainUsable() {
        language("zh-CN")
        compose.waitUntil(15_000) { compose.onAllNodesWithText(text(R.string.runtime_stopped)).fetchSemanticsNodes().isNotEmpty() }
        capture("home-zh")
        compose.onNodeWithContentDescription(text(R.string.settings)).performClick()
        capture("settings-zh")
        compose.onNodeWithContentDescription(text(R.string.back)).performClick()
        click(R.string.subscriptions)
        compose.onNodeWithText("Proxy selection").assertIsDisplayed()
        capture("subscriptions-zh")
        compose.onNode(hasText("Proxy selection") and hasClickAction()).performClick()
        capture("nodes-zh")
        compose.onAllNodesWithContentDescription(text(R.string.subscription_shelf))[0].performClick()
        capture("subscriptions-expanded-zh")
        compose.onAllNodesWithContentDescription(text(R.string.subscription_shelf))[0].performClick()
        compose.onNodeWithContentDescription(text(R.string.search_nodes)).performClick()
        capture("search-zh")
        compose.onNodeWithContentDescription(text(R.string.search_nodes)).performClick()
        click(R.string.configuration); capture("configuration-zh"); click(R.string.new_resource)
        compose.onNode(hasText(text(R.string.name)) and hasSetTextAction() and hasAnyAncestor(isDialog())).performTextInput("Local config")
        click(R.string.add_field)
        compose.onNode(hasText(text(R.string.search_fields)) and hasSetTextAction()).performTextInput("/sniffer/enable")
        compose.onNode(hasText("sniffer.enable") and hasClickAction()).performClick()
        capture("field-modal-zh")
        compose.onNodeWithContentDescription(compose.activity.getString(R.string.help_for, "sniffer.enable")).performClick()
        compose.onNodeWithText(text(R.string.help_purpose)).assertIsDisplayed(); capture("field-help-zh"); click(R.string.close)
        language("en"); capture("field-modal-en")
        compose.onAllNodes(hasText(text(R.string.cancel)) and hasClickAction() and hasAnyAncestor(isDialog())).onLast().performClick()
        compose.onAllNodes(hasText(text(R.string.cancel)) and hasClickAction() and hasAnyAncestor(isDialog())).onLast().performClick()
        click(R.string.home); capture("home-en"); click(R.string.subscriptions); capture("subscriptions-en")
    }
}
