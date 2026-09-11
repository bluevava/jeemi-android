package io.jeemi.android.ui

import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import io.jeemi.android.ui.theme.JeemiShapes
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.jeemi.android.R

@Composable
internal fun ProxyNodeCard(name: String, type: String, delay: Int?, columns: Int, chosen: Boolean,
    selectable: Boolean, testable: Boolean, testing: Boolean, modifier: Modifier, select: () -> Unit, test: () -> Unit,
    delayActionLabel: String? = null, nameIcon: ImageBitmap? = null) {
    val small = columns == 3
    val nameStyle = MaterialTheme.typography.bodyMedium.copy(fontSize = if (small) 11.sp else 13.sp,
        lineHeight = if (small) 15.sp else 18.sp)
    val label = delayActionLabel ?: stringResource(R.string.test_node, name)
    val dark = MaterialTheme.colorScheme.surface.luminance() < .5f
    val tone = when { delay == null -> MaterialTheme.colorScheme.onSurfaceVariant
        delay < 0 || delay > 500 -> if (dark) Color(0xffff9898) else Color(0xffb3261e)
        delay <= 200 -> if (dark) Color(0xff7fe0ab) else Color(0xff176940)
        else -> if (dark) Color(0xffffd777) else Color(0xff805900) }
    Card(modifier.semantics { selected = chosen }.clickable(enabled = selectable, role = Role.Button, onClick = select),
        shape = JeemiShapes.Node,
        colors = CardDefaults.cardColors(containerColor = if (chosen) MaterialTheme.colorScheme.secondaryContainer else MaterialTheme.colorScheme.surface)) {
        // The two hit targets share the same 52dp row instead of stacking a
        // 48dp test target below the name. Typography grows with font scaling.
        Box(Modifier.fillMaxWidth().heightIn(min = 52.dp)) {
            Column(Modifier.fillMaxWidth().heightIn(min = 52.dp).padding(start = if (small) 6.dp else 10.dp, end = 4.dp, top = 5.dp, bottom = 6.dp),
                verticalArrangement = Arrangement.SpaceBetween) {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    if (nameIcon != null) {
                        val iconSize = with(LocalDensity.current) { nameStyle.fontSize.toDp() }
                        Image(nameIcon, null, Modifier.size(iconSize).testTag("node-name-icon"))
                        Spacer(Modifier.width(4.dp))
                    }
                    Text(name, Modifier.weight(1f), maxLines = 1, softWrap = false, overflow = TextOverflow.Ellipsis,
                        style = nameStyle)
                }
                Row(Modifier.fillMaxWidth().padding(end = 48.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(type.ifBlank { "—" }, Modifier.weight(1f), maxLines = 1, softWrap = false, overflow = TextOverflow.Ellipsis,
                    style = MaterialTheme.typography.labelSmall.copy(fontSize = if (small) 9.sp else 10.sp, lineHeight = 15.sp), color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
                Box(Modifier.align(Alignment.BottomEnd).sizeIn(minWidth = 48.dp, minHeight = 48.dp).semantics { contentDescription = label }
                    .clickable(enabled = testable && !testing, role = Role.Button, onClick = test), contentAlignment = Alignment.Center) {
                    Surface(modifier = Modifier.align(Alignment.BottomCenter).padding(bottom = 6.dp), color = tone.copy(alpha = .12f), contentColor = tone, shape = JeemiShapes.Badge) {
                        if (testing) Box(Modifier.padding(6.dp), contentAlignment = Alignment.Center) { CircularProgressIndicator(Modifier.size(12.dp), strokeWidth = 1.5.dp, color = tone) }
                        else Text(if (delay == null || delay < 0) "—" else delay.toString() + " ms", Modifier.padding(horizontal = 3.dp, vertical = 1.dp),
                            maxLines = 1, softWrap = false, style = MaterialTheme.typography.labelSmall.copy(fontSize = if (small) 9.sp else 10.sp, lineHeight = 13.sp))
                    }
                }
        }
    }
}
