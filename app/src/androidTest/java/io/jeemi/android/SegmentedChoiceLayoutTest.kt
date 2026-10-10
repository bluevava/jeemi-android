package io.jeemi.android

import android.content.res.Configuration
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.test.platform.app.InstrumentationRegistry
import io.jeemi.android.domain.AppTheme
import io.jeemi.android.ui.components.SegmentedChoice
import io.jeemi.android.ui.theme.JeemiTheme
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import java.util.Locale

class SegmentedChoiceLayoutTest {
    @get:Rule val compose = createComposeRule()

    @Test fun bilingualLabelsFitInsideOneRowAtNormalAndLargePhoneTextSizes() {
        val options = mutableStateOf(listOf("0" to "System", "1" to "Light", "2" to "Dark"))
        val selected = mutableStateOf("0")
        val width = mutableStateOf(296.dp)
        val scale = mutableStateOf(1f)
        val enabled = mutableStateOf(true)
        compose.setContent {
            CompositionLocalProvider(LocalDensity provides Density(LocalDensity.current.density, scale.value)) {
                JeemiTheme(AppTheme.LIGHT) {
                    Box(Modifier.width(width.value).verticalScroll(rememberScrollState())) {
                        SegmentedChoice("fixture", selected.value, options.value, enabled = enabled.value,
                            optionTagPrefix = "option-") { selected.value = it }
                    }
                }
            }
        }
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        for (language in listOf("en", "zh-CN")) {
            val localized = context.createConfigurationContext(Configuration(context.resources.configuration).apply {
                setLocale(Locale.forLanguageTag(language))
            })
            fun labels(vararg ids: Int) = ids.map { localized.getString(it) }
            val fields = listOf(
                labels(R.string.system_theme, R.string.light_theme, R.string.dark_theme),
                listOf("system", "gVisor", "mips", "mixed"),
                listOf("select", "url-test", "fallback", "load-balance"),
                listOf("classical", "domain", "ipcidr"),
                listOf("yaml", "text", "mrs"),
                labels(R.string.source_inline, R.string.source_http),
                labels(R.string.output_inline, R.string.output_provider),
                labels(R.string.reset_off, R.string.reset_selector, R.string.reset_all),
                labels(R.string.strategy_append_before_terminal, R.string.strategy_prepend, R.string.strategy_rebuild),
                labels(R.string.follow_system, R.string.language_chinese, R.string.language_english),
                labels(R.string.geo_memory, R.string.geo_standard))
            for ((available, fontScale) in listOf(296.dp to 1f, 256.dp to 1.5f, 256.dp to 2f)) {
                for (field in fields) {
                    compose.runOnIdle {
                        options.value = field.mapIndexed { index, text -> index.toString() to text }
                        selected.value = "0"; width.value = available; scale.value = fontScale
                    }
                    val tops = field.indices.map { compose.onNodeWithTag("option-$it").fetchSemanticsNode().positionInRoot.y }
                    assertTrue("$language/$fontScale/$field split into rows", tops.max() - tops.min() < 1f)
                    for ((index, text) in field.withIndex()) {
                        val button = compose.onNodeWithTag("option-$index").performScrollTo()
                        button.assertHeightIsAtLeast(48.dp).assertWidthIsAtLeast(48.dp)
                        val layouts = mutableListOf<TextLayoutResult>()
                        compose.onNodeWithText(text, useUnmergedTree = true)
                            .performSemanticsAction(SemanticsActions.GetTextLayoutResult) { it(layouts) }
                        assertEquals(1, layouts.size)
                        assertFalse("$language/$fontScale/$field clips $text", layouts.single().hasVisualOverflow)
                        assertTrue(layouts.single().lineCount <= 2)
                        assertEquals(fontScale, layouts.single().layoutInput.density.fontScale, 0.001f)
                    }
                    compose.onNodeWithTag("option-${field.lastIndex}").performScrollTo().performClick().assertIsSelected()
                }
            }
        }
        compose.runOnIdle { enabled.value = false }
        compose.onNodeWithTag("option-0").assertIsNotEnabled()
    }
}
