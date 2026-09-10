package io.jeemi.android
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import org.junit.Rule
import org.junit.Test
class NavigationTest {
    @get:Rule(order = 0) val library = TestLibrary()
    @get:Rule(order = 1) val compose = createAndroidComposeRule<MainActivity>()
    @Test fun portraitNavigationAndSecondarySettings() {
        val context = compose.activity
        compose.waitUntil(15_000) { compose.onAllNodesWithText(context.getString(R.string.runtime_stopped)).fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithContentDescription(context.getString(R.string.vpn_start)).assertIsNotEnabled()
        compose.onNodeWithContentDescription(context.getString(R.string.vpn_quick_start)).assertIsDisplayed().assertIsNotEnabled()
        compose.onNodeWithText(context.getString(R.string.status_control)).assertDoesNotExist()
        compose.onNodeWithText(context.getString(R.string.current_subscription)).assertDoesNotExist()
        compose.onNodeWithText(context.getString(R.string.proxy_mode)).assertDoesNotExist()
        compose.onNodeWithText(context.getString(R.string.tun_stack)).assertDoesNotExist()
        compose.onNodeWithText(context.getString(R.string.about_jeemi_description)).assertIsDisplayed()
        compose.onNodeWithText(context.getString(R.string.add_subscription)).performClick()
        compose.onNode(hasSetTextAction() and hasText(context.getString(R.string.subscription_url))).assertIsDisplayed()
        compose.onNodeWithContentDescription(context.getString(R.string.back)).performClick()
        compose.onNode(hasText(context.getString(R.string.home)) and hasClickAction()).performClick()
        compose.onNodeWithText(context.getString(R.string.inherit)).assertDoesNotExist()
        compose.onNodeWithText(context.getString(R.string.language)).assertDoesNotExist()
        compose.onNodeWithContentDescription(context.getString(R.string.settings)).performClick()
        compose.onNodeWithText(context.getString(R.string.home_settings)).assertIsDisplayed()
        compose.onNodeWithContentDescription(context.getString(R.string.vpn_quick_start)).assertIsDisplayed()
        compose.onNodeWithText(context.getString(R.string.appearance)).assertIsDisplayed()
        compose.onNode(hasText(context.getString(R.string.cancel)) and hasClickAction()).performClick()
        compose.onNode(hasText(context.getString(R.string.subscriptions)) and hasClickAction()).performClick()
        compose.onNode(hasSetTextAction() and hasText(context.getString(R.string.subscription_url))).assertDoesNotExist()
        compose.onNodeWithContentDescription(context.getString(R.string.vpn_quick_start)).assertIsDisplayed()
        compose.onNodeWithText(context.getString(R.string.import_action)).assertIsDisplayed()
        compose.onNodeWithContentDescription(context.getString(R.string.search_nodes)).performClick()
        compose.onNode(hasSetTextAction() and hasText(context.getString(R.string.search_nodes))).assertIsDisplayed()
        compose.onNodeWithContentDescription(context.getString(R.string.hide_search)).performClick()
        compose.onNode(hasText(context.getString(R.string.configuration)) and hasClickAction()).performClick()
        compose.onNodeWithContentDescription(context.getString(R.string.vpn_quick_start)).assertIsDisplayed()
        compose.onNode(hasText(context.getString(R.string.local_configuration)) and hasClickAction()).assertIsDisplayed()
        compose.onNode(hasText(context.getString(R.string.more_navigation)) and hasClickAction()).performClick()
        compose.onNodeWithText(context.getString(R.string.logs)).assertDoesNotExist()
        compose.onNode(hasText(context.getString(R.string.tools)) and hasClickAction()).performClick()
        compose.onNodeWithText(context.getString(R.string.dns_domain)).assertIsDisplayed()
        compose.onNodeWithContentDescription(context.getString(R.string.vpn_quick_start)).assertIsDisplayed()
        compose.onNodeWithText(context.getString(R.string.logs)).assertDoesNotExist()
        compose.onNodeWithContentDescription(context.getString(R.string.settings)).assertDoesNotExist()
    }
}
