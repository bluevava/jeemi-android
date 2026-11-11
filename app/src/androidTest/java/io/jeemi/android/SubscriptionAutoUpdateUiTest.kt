package io.jeemi.android

import androidx.appcompat.app.AppCompatDelegate
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.core.os.LocaleListCompat
import androidx.lifecycle.ViewModelProvider
import io.jeemi.android.domain.*
import io.jeemi.android.ui.JeemiViewModel
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

class SubscriptionAutoUpdateUiTest {
    @get:Rule(order = 0) val library = TestLibrary { app ->
        val item = app.engine.normalize("Auto update demo", "proxies: [{name: Example, type: socks5, server: example.invalid, port: 1080}]\nrules: ['MATCH,DIRECT']")
            .copy(sourceUrl = "https://example.invalid/subscription", updatedAt = 1_800_000_000_000,
                autoUpdateIntervalMinutes = 60)
        Library(listOf(item), item.id)
    }
    @get:Rule(order = 1) val compose = createAndroidComposeRule<MainActivity>()
    private fun text(id: Int) = compose.activity.getString(id)
    private fun click(id: Int) = compose.onNode(hasText(text(id)) and hasClickAction()).performClick()
    private fun capture(name: String) {
        compose.waitForIdle()
        // Android window exit animations can outlive Compose's idle state.
        android.os.SystemClock.sleep(400)
        val bitmap = androidx.test.platform.app.InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot()
        java.io.File(compose.activity.getExternalFilesDir(null), "auto-update-$name.png").outputStream().use {
            bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it)
        }
        bitmap.recycle()
    }

    @Test fun intervalsAreDraftedInCompactBilingualImportAndEditDialogs() {
        val model = ViewModelProvider(compose.activity)[JeemiViewModel::class.java]
        compose.waitUntil(15_000) { model.state.value.loaded }
        val originalTime = model.state.value.library.selected!!.updatedAt
        try {
            for (language in listOf("zh-CN", "en")) {
                compose.runOnUiThread { AppCompatDelegate.setApplicationLocales(LocaleListCompat.forLanguageTags(language)) }
                compose.waitForIdle()
                compose.onNodeWithTag("navigation-SUBSCRIPTIONS").performClick()
                if (compose.onAllNodesWithContentDescription(text(R.string.more_actions)).fetchSemanticsNodes().isEmpty()) {
                    compose.onAllNodesWithContentDescription(text(R.string.subscription_shelf)).onFirst().performClick()
                }
                compose.onNodeWithContentDescription(text(R.string.more_actions)).performClick()
                click(R.string.edit)
                compose.onNode(hasText(text(R.string.subscription_update_interval)) and hasClickAction()).performScrollTo().performClick()
                for (id in listOf(R.string.subscription_update_10m, R.string.subscription_update_30m,
                    R.string.subscription_update_1h, R.string.subscription_update_3h, R.string.subscription_update_12h)) {
                    compose.onAllNodesWithText(text(id)).onLast().assertExists()
                }
                capture("$language-options")
                compose.onAllNodesWithText(text(R.string.subscription_update_3h)).onLast().performClick()
                compose.onNodeWithContentDescription(compose.activity.getString(R.string.help_for,
                    text(R.string.subscription_update_interval))).performClick()
                compose.onNodeWithText(text(R.string.subscription_update_purpose)).assertExists()
                click(R.string.close)
                compose.onNodeWithText(text(R.string.subscription_update_3h)).assertExists()
                capture("$language-edit")
                click(R.string.save)
                compose.waitUntil(15_000) { compose.onAllNodes(isDialog()).fetchSemanticsNodes().isEmpty() }
                assertEquals(180, model.state.value.library.selected!!.autoUpdateIntervalMinutes)
                assertEquals(originalTime, model.state.value.library.selected!!.updatedAt)

                click(R.string.import_action)
                compose.onNodeWithText(text(R.string.subscription_update_1h)).assertExists()
                click(R.string.subscription_update_interval)
                compose.onAllNodesWithText(text(R.string.subscription_update_10m)).onLast().performClick()
                click(R.string.from_text)
                compose.onNodeWithText(text(R.string.subscription_update_interval)).assertDoesNotExist()
                click(R.string.from_url)
                compose.onNodeWithText(text(R.string.subscription_update_10m)).assertExists()
                compose.onNode(hasContentDescription(text(R.string.back)) and hasAnyAncestor(isDialog())).performClick()
                click(R.string.import_action)
                compose.onNodeWithText(text(R.string.subscription_update_1h)).assertExists()
                compose.onNode(hasContentDescription(text(R.string.back)) and hasAnyAncestor(isDialog())).performClick()
            }
        } finally {
            compose.runOnUiThread { AppCompatDelegate.setApplicationLocales(LocaleListCompat.getEmptyLocaleList()) }
        }
    }
}
