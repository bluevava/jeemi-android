package io.jeemi.android

import androidx.compose.foundation.layout.Column
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.test.platform.app.InstrumentationRegistry
import io.jeemi.android.domain.AppTheme
import io.jeemi.android.ui.components.FeatureHelp
import io.jeemi.android.ui.components.HelpContent
import io.jeemi.android.ui.theme.JeemiTheme
import org.junit.Rule
import org.junit.Test

class FeatureHelpTest {
    @get:Rule val compose = createComposeRule()

    @Test fun closingThreeSectionHelpPreservesTheFormDraft() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        compose.setContent {
            JeemiTheme(AppTheme.LIGHT) {
                var draft by remember { mutableStateOf("") }
                Column {
                    OutlinedTextField(draft, { draft = it }, label = { Text("Draft") })
                    FeatureHelp(HelpContent(R.string.proxy_mode, R.string.mode_purpose, R.string.mode_scenarios, R.string.mode_cautions))
                }
            }
        }
        compose.onNode(hasSetTextAction()).performTextInput("keep this draft")
        compose.onNodeWithContentDescription(context.getString(R.string.help_for, context.getString(R.string.proxy_mode))).performClick()
        compose.onNodeWithText(context.getString(R.string.help_purpose)).assertIsDisplayed()
        compose.onNodeWithText(context.getString(R.string.help_scenarios)).assertIsDisplayed()
        compose.onNodeWithText(context.getString(R.string.help_cautions)).assertExists()
        compose.onNodeWithText(context.getString(R.string.close)).performClick()
        compose.onNodeWithText("keep this draft").assertIsDisplayed()
    }
}
