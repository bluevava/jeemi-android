@file:OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
package io.jeemi.android.ui.components

import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.ExpandLess
import androidx.compose.material.icons.outlined.ExpandMore
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.jeemi.android.R
import io.jeemi.android.data.resourceEmojis

@Composable
internal fun ResourceEmojiField(value: String, enabled: Boolean, change: (String) -> Unit) {
    var expanded by rememberSaveable { mutableStateOf(false) }
    val label = stringResource(R.string.resource_emoji)
    Column {
        OutlinedButton(onClick = { expanded = !expanded }, enabled = enabled,
            modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp).testTag("resource-emoji-toggle"),
            shape = MaterialTheme.shapes.small) {
            Text(label, Modifier.weight(1f))
            Text(value.ifEmpty { stringResource(R.string.none_items) }, fontSize = 18.sp)
            Spacer(Modifier.width(8.dp))
            Icon(if (expanded) Icons.Outlined.ExpandLess else Icons.Outlined.ExpandMore, null, Modifier.size(18.dp))
        }
        if (expanded) {
            TextButton(onClick = { change("") }, enabled = enabled, shape = MaterialTheme.shapes.small,
                modifier = Modifier.heightIn(min = 48.dp).semantics { selected = value.isEmpty() }) {
                Text(stringResource(R.string.none_items))
            }
            FlowRow(Modifier.fillMaxWidth().testTag("resource-emoji-grid")) {
                resourceEmojis.forEach { emoji ->
                    val description = stringResource(R.string.select_resource_emoji, emoji)
                    TextButton(onClick = { change(emoji) }, enabled = enabled, shape = MaterialTheme.shapes.small,
                        contentPadding = PaddingValues(0.dp),
                        colors = ButtonDefaults.textButtonColors(containerColor = if (value == emoji)
                            MaterialTheme.colorScheme.secondaryContainer else androidx.compose.ui.graphics.Color.Transparent),
                        modifier = Modifier.sizeIn(minWidth = 48.dp, minHeight = 48.dp)
                            .testTag("resource-emoji-$emoji").semantics { contentDescription = description; selected = value == emoji }) {
                        Text(emoji, fontSize = 22.sp)
                    }
                }
            }
        }
    }
}
