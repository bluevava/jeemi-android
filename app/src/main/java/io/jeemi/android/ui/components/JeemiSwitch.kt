package io.jeemi.android.ui.components

import androidx.compose.material3.Switch
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import io.jeemi.android.ui.theme.jeemiSwitchColors

@Composable
internal fun JeemiSwitch(
    checked: Boolean,
    onCheckedChange: ((Boolean) -> Unit)?,
    modifier: Modifier = Modifier,
    thumbContent: (@Composable () -> Unit)? = null,
    enabled: Boolean = true,
) {
    Switch(checked = checked, onCheckedChange = onCheckedChange, modifier = modifier,
        thumbContent = thumbContent, enabled = enabled, colors = jeemiSwitchColors())
}
