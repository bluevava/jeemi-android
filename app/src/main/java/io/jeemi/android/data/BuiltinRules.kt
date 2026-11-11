package io.jeemi.android.data

import android.content.Context
import io.jeemi.android.R
import mobile.Mobile
import org.json.JSONObject

internal fun builtinRuleLabels(context: Context): String = JSONObject().apply {
    mapOf(
        "general" to R.string.builtin_general_name, "apps" to R.string.builtin_apps_name,
        "jeemi" to R.string.builtin_group_jeemi, "ai" to R.string.builtin_group_ai,
        "im" to R.string.builtin_group_im, "video" to R.string.builtin_group_video,
        "domestic" to R.string.builtin_group_domestic, "appDirect" to R.string.builtin_group_apps,
        "domesticRules" to R.string.builtin_rules_domestic, "appDirectRules" to R.string.builtin_rules_apps,
        "github" to R.string.builtin_rules_github, "apple" to R.string.builtin_rules_apple,
        "aiDomains" to R.string.builtin_rules_ai, "google" to R.string.builtin_rules_google,
        "telegramDomains" to R.string.builtin_rules_telegram, "telegramIP" to R.string.builtin_rules_telegram_ip,
        "discord" to R.string.builtin_rules_discord, "whatsapp" to R.string.builtin_rules_whatsapp,
        "line" to R.string.builtin_rules_line, "netflix" to R.string.builtin_rules_netflix,
        "spotify" to R.string.builtin_rules_spotify, "twitter" to R.string.builtin_rules_twitter,
        "instagram" to R.string.builtin_rules_instagram, "youtube" to R.string.builtin_rules_youtube,
    ).forEach { (key, label) -> put(key, context.getString(label)) }
}.toString()

internal val resourceEmojis: List<String> by lazy {
    org.json.JSONArray(Mobile.resourceEmojiChoices()).strings()
}
