package io.jeemi.android.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import io.jeemi.android.domain.AppTheme

private val Light = lightColorScheme(
    primary = Color(0xFF5C47C9), onPrimary = Color.White,
    primaryContainer = Color(0xFFEEEBFA), onPrimaryContainer = Color(0xFF5C47C9),
    background = Color(0xFFF3F3F5), onBackground = Color(0xFF202024),
    surface = Color.White, onSurface = Color(0xFF202024),
    surfaceVariant = Color(0xFFF6F6F7), onSurfaceVariant = Color(0xFF5F5F69),
    surfaceContainer = Color.White, surfaceContainerLow = Color(0xFFF6F6F7),
    outline = Color(0xFFDDDDE2), outlineVariant = Color(0xFFDDDDE2),
    error = Color(0xFFB83243), secondary = Color(0xFF5F5F69),
)
private val Dark = darkColorScheme(
    primary = Color(0xFFB6A7FF), onPrimary = Color(0xFF201A36),
    primaryContainer = Color(0xFF343044), onPrimaryContainer = Color(0xFFB6A7FF),
    background = Color(0xFF171719), onBackground = Color(0xFFEFEFF3),
    surface = Color(0xFF242428), onSurface = Color(0xFFEFEFF3),
    surfaceVariant = Color(0xFF2B2B30), onSurfaceVariant = Color(0xFFB5B5C0),
    surfaceContainer = Color(0xFF242428), surfaceContainerLow = Color(0xFF2B2B30),
    outline = Color(0xFF42424B), outlineVariant = Color(0xFF42424B),
    error = Color(0xFFFF98A4), secondary = Color(0xFFB5B5C0),
)

@Composable
fun isJeemiDark(theme: AppTheme): Boolean = when (theme) {
    AppTheme.SYSTEM -> isSystemInDarkTheme()
    AppTheme.LIGHT -> false
    AppTheme.DARK -> true
}

@Composable
fun JeemiTheme(theme: AppTheme, content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = if (isJeemiDark(theme)) Dark else Light,
        shapes = JeemiShapes.Material,
        content = content,
    )
}
