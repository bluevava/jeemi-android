package io.jeemi.android

import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.dp
import androidx.test.platform.app.InstrumentationRegistry
import io.jeemi.android.ui.ProxyNodeCard
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
}
