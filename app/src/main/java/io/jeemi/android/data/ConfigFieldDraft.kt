package io.jeemi.android.data

import org.json.JSONArray
import org.json.JSONObject

internal fun JSONObject.configFields(key: String): List<JSONObject> = optJSONArray(key)?.let { array ->
    List(array.length()) { array.getJSONObject(it) }
}.orEmpty()

/** Move the original entry between the sparse plan and retained drafts, without changing its value. */
internal fun JSONObject.setConfigFieldEnabled(paths: Set<String>, enabled: Boolean): JSONObject = apply {
    val active = configFields("fields")
    val inactive = configFields("disabledFields")
    val moving = (if (enabled) inactive else active).filter { it.getString("path") in paths }
    put("fields", JSONArray(if (enabled) active + moving else active.filterNot { it.getString("path") in paths }))
    put("disabledFields", JSONArray(if (enabled) inactive.filterNot { it.getString("path") in paths } else inactive + moving))
}
