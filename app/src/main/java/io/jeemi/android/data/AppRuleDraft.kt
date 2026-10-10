package io.jeemi.android.data

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import mobile.Mobile
import org.json.JSONObject

internal data class AppRuleRow(val index: Int, val packageName: String, val rule: String)
internal data class AppRuleDraft(val yaml: String, val rows: List<AppRuleRow>, val otherRules: Int) {
    val apps = rows.filter { it.packageName.isNotEmpty() }
}

// Absent metadata and the previous App editor both default to the app list.
// The explicit YAML preference is saved with the resource, never in core YAML.
internal fun JSONObject.isAppListEnabled(): Boolean = optString("sourceType") == "inline" &&
    optString("behavior") == "classical" && optString("editorMode") in listOf("", "app")

internal suspend fun editAppRuleDraft(yaml: String, request: JSONObject = JSONObject().put("action", "view")): AppRuleDraft = withContext(Dispatchers.IO) {
    val result = JSONObject(Mobile.editAppRuleSet(yaml, request.toString()))
    val rows = result.getJSONArray("rows")
    AppRuleDraft(result.getString("payloadYaml"), List(rows.length()) {
        rows.getJSONObject(it).let { row -> AppRuleRow(row.getInt("index"), row.optString("packageName"), row.getString("rule")) }
    }, result.getInt("otherRuleCount"))
}
