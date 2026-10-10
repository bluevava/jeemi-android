package io.jeemi.android.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SwitchColors
import androidx.compose.material3.SwitchDefaults
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance

/** Solid state colors keep switches legible on cards, dialogs and highlighted field rows. */
@Composable
internal fun jeemiSwitchColors(): SwitchColors {
    val scheme = MaterialTheme.colorScheme
    val dark = scheme.surface.luminance() < 0.5f
    val track = if (dark) Color(0xFF36363E) else Color(0xFFECECEF)
    val disabledTrack = if (dark) Color(0xFF6E6487) else Color(0xFF9185B5)
    return SwitchDefaults.colors(
        checkedThumbColor = scheme.onPrimary,
        checkedTrackColor = scheme.primary,
        checkedBorderColor = scheme.primary,
        checkedIconColor = scheme.primary,
        uncheckedThumbColor = scheme.onSurfaceVariant,
        uncheckedTrackColor = track,
        uncheckedBorderColor = if (dark) Color(0xFF92929F) else Color(0xFF7F7F8A),
        uncheckedIconColor = scheme.surface,
        disabledCheckedThumbColor = if (dark) Color(0xFFDDD9EA) else Color(0xFFFAFAFC),
        disabledCheckedTrackColor = disabledTrack,
        disabledCheckedBorderColor = disabledTrack,
        disabledCheckedIconColor = if (dark) Color(0xFF403A52) else Color(0xFF5D5574),
        disabledUncheckedThumbColor = if (dark) Color(0xFF858591) else Color(0xFF93939F),
        disabledUncheckedTrackColor = track,
        disabledUncheckedBorderColor = if (dark) Color(0xFF666672) else Color(0xFFA1A1AC),
        disabledUncheckedIconColor = if (dark) Color(0xFF171719) else Color(0xFF242428),
    )
}
