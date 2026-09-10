package io.jeemi.android

import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import org.junit.Rule
import org.junit.Test

/** Uses synthetic, non-routable data. Never downloads a real subscription or starts VPN. */
class WorkspaceUiTest {
    @get:Rule(order = 0) val library = TestLibrary()
    @get:Rule(order = 1) val compose = createAndroidComposeRule<MainActivity>()

    private fun text(id: Int) = compose.activity.getString(id)
    private fun clickLabel(id: Int) = compose.onNode(hasText(text(id)) and hasClickAction()).performClick()

    @Test fun importCreateAssociateAndPreviewKeepTheDraftAndOriginal() {
        compose.waitUntil(15_000) { compose.onAllNodesWithText(text(R.string.runtime_stopped)).fetchSemanticsNodes().isNotEmpty() }
        clickLabel(R.string.subscriptions)
        clickLabel(R.string.import_action)
        clickLabel(R.string.from_text)
        compose.onNode(hasText(text(R.string.subscription_name_optional)) and hasSetTextAction() and hasAnyAncestor(isDialog())).performTextInput("Mobile layout preview")
        val original = """
            mode: rule
            proxies:
              - { name: Hong Kong 01, type: socks5, server: hk.example.invalid, port: 1080 }
              - { name: Tokyo 02, type: socks5, server: jp.example.invalid, port: 1080 }
              - { name: Singapore 03, type: socks5, server: sg.example.invalid, port: 1080 }
            proxy-groups:
              - name: Proxy selection
                type: select
                proxies: [Hong Kong 01, Tokyo 02, Singapore 03, DIRECT]
            rules:
              - MATCH,Proxy selection
        """.trimIndent()
        compose.onNode(hasText(text(R.string.subscription_content)) and hasSetTextAction() and hasAnyAncestor(isDialog())).performTextInput(original)
        compose.onNode(hasContentDescription(compose.activity.getString(R.string.help_for, text(R.string.subscriptions))) and hasAnyAncestor(isDialog())).performClick()
        compose.onNodeWithText(text(R.string.help_purpose)).assertIsDisplayed()
        clickLabel(R.string.close)
        compose.onNode(hasSetTextAction() and hasText(original)).assertExists()
        compose.onNode(hasText(text(R.string.import_action)) and hasClickAction() and hasAnyAncestor(isDialog())).performClick()
        compose.waitUntil(15_000) { compose.onAllNodesWithText("Mobile layout preview").fetchSemanticsNodes().isNotEmpty() }
        compose.onAllNodesWithContentDescription(text(R.string.subscription_shelf))[0].performClick()
        compose.onNodeWithContentDescription(text(R.string.more_actions)).assertIsDisplayed()
        clickLabel(R.string.configuration)
        clickLabel(R.string.new_resource)
        compose.onNode(hasText(text(R.string.name)) and hasSetTextAction() and hasAnyAncestor(isDialog())).performTextInput("Local sniffer")
        compose.onNode(hasText(text(R.string.add_field)) and hasClickAction()).performScrollTo().performClick()
        compose.onNode(hasText(text(R.string.search_fields)) and hasSetTextAction()).performTextInput("/sniffer/enable")
        compose.onNode(hasText("sniffer.enable") and hasClickAction()).performClick()
        compose.onNode(hasText(text(R.string.apply_to_draft)) and hasClickAction()).performClick()
        compose.onNode(hasText(text(R.string.save)) and hasClickAction() and hasAnyAncestor(isDialog())).performClick()
        try {
            compose.waitUntil(15_000) { compose.onAllNodesWithText("Local sniffer").fetchSemanticsNodes().isNotEmpty() && compose.onAllNodes(isDialog()).fetchSemanticsNodes().isEmpty() }
        } catch (failure: Exception) {
            val bitmap = androidx.test.platform.app.InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot()
            java.io.File(compose.activity.getExternalFilesDir(null), "workspace-failure.png").outputStream().use {
                bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it)
            }
            bitmap.recycle()
            val state = androidx.lifecycle.ViewModelProvider(compose.activity)[io.jeemi.android.ui.JeemiViewModel::class.java].state.value
            throw AssertionError("Resource save: error=${state.error}, issue=${state.businessIssue?.code}, busy=${state.busy}", failure)
        }
        clickLabel(R.string.subscriptions)
        compose.onNodeWithContentDescription(text(R.string.more_actions)).performClick()
        compose.onNode(hasText(text(R.string.associations)) and hasAnyAncestor(isPopup())).performClick()
        compose.onNodeWithText("Local sniffer").performClick()
        compose.onNode(hasText(text(R.string.save)) and hasClickAction() and hasAnyAncestor(isDialog())).performClick()
        compose.waitUntil(15_000) { compose.onAllNodes(isDialog()).fetchSemanticsNodes().isEmpty() }
        compose.onNodeWithText(text(R.string.collapse_subscription_panel)).performClick()
        compose.onNodeWithContentDescription(text(R.string.configuration_preview)).performClick()
        compose.waitUntil(15_000) { compose.onAllNodes(hasText("sniffer:", substring = true)).fetchSemanticsNodes().isNotEmpty() }
        compose.onNode(hasText("sniffer:", substring = true) and hasText("MATCH,Proxy selection", substring = true)).assertExists()
        compose.onNodeWithContentDescription(text(R.string.back)).performClick()
    }
}
