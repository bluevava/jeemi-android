@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package io.jeemi.android.ui.components

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.LineBreak
import androidx.compose.ui.text.style.Hyphens
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/** One row of choices; long labels adapt inside their button without changing the draft. */
@Composable
internal fun SegmentedChoice(label: String, value: String, options: List<Pair<String, String>>,
    modifier: Modifier = Modifier, enabled: Boolean = true, showLabel: Boolean = true,
    optionTagPrefix: String? = null, select: (String) -> Unit) {
    if (options.isEmpty()) return
    val style = MaterialTheme.typography.labelLarge
    val measurer = rememberTextMeasurer()
    val density = LocalDensity.current
    Column(modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        if (showLabel) Text(label, style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant)
        BoxWithConstraints(Modifier.fillMaxWidth().semantics { contentDescription = label }) {
            val layout = remember(options, constraints.maxWidth, style, density, measurer) {
                // Keep font scaling. Reduce only the label's base size (normally 14 -> 12sp),
                // and scroll at extreme sizes instead of truncating text or shrinking touch targets.
                val styles = (0..2).map { (style.fontSize.value - it).coerceAtLeast(12f) }.distinct()
                    .map { style.copy(fontSize = it.sp, lineHeight = (it + 4).sp,
                        lineBreak = LineBreak.Heading, hyphens = Hyphens.Auto) }
                val inset = with(density) { 12.dp.roundToPx() } + 2 // horizontal padding + rounding guard
                val minimum = with(density) { 48.dp.roundToPx() }
                fun measure(text: String, candidate: TextStyle, width: Int, lines: Int = 2) =
                    measurer.measure(text, candidate, constraints = Constraints(maxWidth = width.coerceAtLeast(1)),
                        maxLines = lines, softWrap = lines > 1)
                var itemWidth = maxOf(minimum, constraints.maxWidth / options.size)
                for ((_, text) in options) {
                    if (!measure(text, styles.last(), itemWidth - inset).hasVisualOverflow) continue
                    var low = itemWidth - inset
                    var high = measurer.measure(text, styles.last(), softWrap = false).size.width.coerceAtLeast(low)
                    while (low < high) {
                        val middle = (low + high) / 2
                        if (measure(text, styles.last(), middle).hasVisualOverflow) low = middle + 1 else high = middle
                    }
                    itemWidth = maxOf(itemWidth, low + inset)
                }
                val textWidth = itemWidth - inset
                val labels = options.map { (_, text) ->
                    styles.firstOrNull { !measure(text, it, textWidth, 1).hasVisualOverflow }
                        ?: styles.firstOrNull { !measure(text, it, textWidth).hasVisualOverflow } ?: styles.last()
                }
                val height = maxOf(minimum, options.indices.maxOf { measure(options[it].second, labels[it], textWidth).size.height } +
                    with(density) { 4.dp.roundToPx() })
                ChoiceLayout(maxOf(constraints.maxWidth, itemWidth * options.size), textWidth, height, labels)
            }
            val scroll = rememberScrollState()
            Box(Modifier.fillMaxWidth().then(
                if (layout.width > constraints.maxWidth) Modifier.horizontalScroll(scroll) else Modifier)) {
                SingleChoiceSegmentedButtonRow(Modifier.width(with(density) { layout.width.toDp() })) {
                    options.forEachIndexed { index, option ->
                        SegmentedButton(selected = value == option.first,
                            onClick = { if (value != option.first) select(option.first) },
                            enabled = enabled, shape = SegmentedButtonDefaults.itemShape(index, options.size),
                            contentPadding = PaddingValues(horizontal = 6.dp, vertical = 2.dp),
                            modifier = Modifier.height(with(density) { layout.height.toDp() }).then(
                                optionTagPrefix?.let { Modifier.testTag(it + option.first) } ?: Modifier),
                            icon = {}) {
                            Text(option.second, modifier = Modifier.width(with(density) { layout.textWidth.toDp() }),
                                style = layout.styles[index], textAlign = TextAlign.Center, maxLines = 2)
                        }
                    }
                }
            }
        }
    }
}

private data class ChoiceLayout(val width: Int, val textWidth: Int, val height: Int, val styles: List<TextStyle>)
