package io.jeemi.android

import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.semantics.SemanticsActions
import androidx.lifecycle.ViewModelProvider
import io.jeemi.android.domain.*
import io.jeemi.android.runtime.ConnectionFeed
import io.jeemi.android.runtime.ConnectionRecord
import io.jeemi.android.ui.JeemiViewModel
import mobile.Mobile
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

class DiagnosticsUiTest {
    @get:Rule(order = 0) val library = TestLibrary { app ->
        val profile = app.engine.normalize("Fixture", "proxies: []\nproxy-groups: [{name: Choose, type: select, proxies: [DIRECT, REJECT]}]\nrules: ['MATCH,Choose']")
        Library(listOf(profile), profile.id, Preferences(runtimeJson = JSONObject(Mobile.runtimeDefaults()).put("logLevel", "info").toString()))
    }
    @get:Rule(order = 1) val compose = createAndroidComposeRule<MainActivity>()
    private fun ready() { compose.waitUntil(15000) { compose.onAllNodesWithText(compose.activity.getString(R.string.runtime_stopped)).fetchSemanticsNodes().isNotEmpty() } }
    private fun more(label: Int) {
        compose.onNode(hasText(compose.activity.getString(R.string.more_navigation)) and hasClickAction()).performClick()
        compose.onNode(hasText(compose.activity.getString(label)) and hasClickAction()).performClick()
    }
    @Test fun offlineChoicePersistsWithoutStartingCoreOrChangingCandidate() {
        ready(); val app = compose.activity.application as JeemiApplication
        val before = app.repository.load(); val candidate = app.engine.project(before.selected!!, before.preferences, before.resources).revision
        compose.onNode(hasText(compose.activity.getString(R.string.subscriptions)) and hasClickAction()).performClick()
        compose.onNodeWithText("Choose").performClick()
        compose.onNodeWithText("REJECT").performClick()
        compose.waitUntil(5000) { app.repository.load().selected?.selections?.get("Choose") == "REJECT" }
        val after = app.repository.load()
        assertEquals(RuntimeState.Stopped, app.runtime.state.value)
        assertEquals(candidate, app.engine.project(after.selected!!, after.preferences, after.resources).revision)
    }
    @Test fun connectionRowOpensDetailsAndSavesPackageRuleViaDesktopComposer() {
        ready()
        compose.runOnIdle {
            val model = ViewModelProvider(compose.activity)[JeemiViewModel::class.java]
            model.diagnostics.connections.value = ConnectionFeed(rows = listOf(ConnectionRecord("fixture", "example.com", "203.0.113.10", "443", "172.19.0.1", "44444",
                "tcp", "TUN", "org.example.browser", 12345, "Domain", "example.com", listOf("DIRECT"), 123, 456, "2026-09-09T00:00:00Z")))
        }
        more(R.string.connections)
        compose.onNodeWithTag("connection-details-fixture").performClick()
        compose.onNodeWithText(compose.activity.getString(R.string.connection_details)).assertIsDisplayed()
        compose.onNodeWithContentDescription(compose.activity.getString(R.string.add_connection_rule_for,
            compose.activity.getString(R.string.application_package))).performClick()
        compose.waitUntil(10000) { compose.onAllNodesWithText("- 'PROCESS-NAME,org.example.browser'").fetchSemanticsNodes().isNotEmpty() }
        // The platform dialog fade is outside the Compose clock. Invoke the
        // enabled button's action so its moving window cannot misplace a tap.
        compose.onNode(hasText(compose.activity.getString(R.string.save)) and hasClickAction())
            .assertIsEnabled().performSemanticsAction(SemanticsActions.OnClick) { it() }
        val app = compose.activity.application as JeemiApplication
        compose.waitUntil(10000) { app.repository.load().resources.any { it.kind == ResourceKind.RULES && it.content.contains("PROCESS-NAME,org.example.browser") } }
    }
    @Test fun moreExposesEnabledLogsAndDnsShowsValidationWithoutCore() {
        ready(); more(R.string.logs)
        compose.onNodeWithText(compose.activity.getString(R.string.follow_logs)).assertIsDisplayed()
        more(R.string.tools)
        compose.onNode(hasText(compose.activity.getString(R.string.dns_domain)) and hasSetTextAction()).performTextInput("not a domain")
        compose.onNodeWithContentDescription(compose.activity.getString(R.string.dns_query_action)).performClick()
        compose.waitUntil(5000) { compose.onAllNodesWithText(compose.activity.getString(R.string.dns_invalid_domain)).fetchSemanticsNodes().isNotEmpty() }
    }
}
