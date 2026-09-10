package io.jeemi.android.data

import mobile.Mobile
import org.json.JSONObject

data class ChainNode(val id: String, val name: String, val type: String, val sourceId: String)
data class ChainSource(val id: String, val label: String, val filter: String, val updatedAt: String, val nodeCount: Int, val report: String)
data class ChainGroup(val id: String, val name: String, val kind: String, val selectorFilter: String, val nodeFilter: String,
    val nodes: List<ChainNode>, val sources: List<ChainSource>)
data class ChainState(val revision: Int = 0, val groups: List<ChainGroup> = emptyList())
data class ChainDraft(val mode: String, val revision: Int, val groupId: String = "", val id: String = "",
    val name: String = "", val kind: String = "manual", val selectorFilter: String = "", val nodeFilter: String = "",
    val contents: String = "", val url: String = "", val filter: String = "")
data class BusinessIssue(val code: String, val line: Int = 0)
data class ChainFailure(val groupId: String, val sourceId: String, val issue: BusinessIssue)

fun chainState(raw: String): ChainState {
    val json = JSONObject(Mobile.chainProxyState(raw))
    val groups = json.getJSONArray("groups")
    return ChainState(json.getInt("revision"), List(groups.length()) { index ->
        val group = groups.getJSONObject(index)
        val nodes = group.getJSONArray("nodes"); val sources = group.getJSONArray("sources")
        ChainGroup(group.getString("id"), group.getString("name"), group.getString("kind"), group.getString("selectorFilter"),
            group.getString("nodeFilter"), List(nodes.length()) { i -> nodes.getJSONObject(i).let {
                ChainNode(it.getString("id"), it.getString("name"), it.getString("type"), it.optString("sourceId"))
            } }, List(sources.length()) { i -> sources.getJSONObject(i).let {
                ChainSource(it.getString("id"), it.getString("label"), it.getString("filter"), it.getString("updatedAt"),
                    it.getInt("nodeCount"), it.getJSONObject("report").toString())
            } })
    })
}

// Error text from JNI/HTTP is never displayed; retain only a fixed code/position.
fun businessIssue(error: Exception): BusinessIssue? {
    val text = error.message.orEmpty()
    Regex("chain_proxy:([a-z_]+)").find(text)?.let { return BusinessIssue("chain_" + it.groupValues[1]) }
    Regex("subscription normalization: ([a-z_]+) \\(line ([0-9]+),").find(text)?.let {
        return BusinessIssue("conversion_" + it.groupValues[1], it.groupValues[2].toIntOrNull() ?: 0)
    }
    return null
}

internal fun changedChainGroups(previous: String, next: String): Set<String> {
    fun entries(raw: String): Map<String, String> {
        val groups = JSONObject(raw.ifBlank { io.jeemi.android.domain.EMPTY_CHAIN_LIBRARY }).getJSONArray("groups")
        return (0 until groups.length()).associate { groups.getJSONObject(it).let { g -> g.getString("id") to g.toString() } }
    }
    val before = entries(previous); val after = entries(next)
    return (before.keys + after.keys).filter { before[it] != after[it] }.toSet()
}
