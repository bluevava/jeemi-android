package io.jeemi.android.ui

import androidx.appcompat.app.AppCompatDelegate
import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalConfiguration
import androidx.core.os.LocaleListCompat
import io.jeemi.android.R

// AppCompat owns locale persistence, including the Android 13 system language picker.
// Keeping one store prevents a saved business preference from undoing a system change.
internal enum class AppLanguage(val tag: String, val label: Int) {
    SYSTEM("", R.string.follow_system),
    CHINESE("zh-CN", R.string.language_chinese),
    ENGLISH("en", R.string.language_english);

    fun apply() = AppCompatDelegate.setApplicationLocales(LocaleListCompat.forLanguageTags(tag))
}

@Composable
internal fun selectedAppLanguage(): AppLanguage {
    // Subscribe to configuration changes, including changes from Android settings.
    LocalConfiguration.current
    return when (AppCompatDelegate.getApplicationLocales().get(0)?.language) {
        "zh" -> AppLanguage.CHINESE
        "en" -> AppLanguage.ENGLISH
        else -> AppLanguage.SYSTEM
    }
}
