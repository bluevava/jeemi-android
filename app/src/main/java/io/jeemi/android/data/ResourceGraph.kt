package io.jeemi.android.data

import io.jeemi.android.domain.Library
import mobile.Mobile
import org.json.JSONArray
import org.json.JSONObject

data class ResourceGraphNode(val key: String, val id: String, val kind: String, val name: String,
    val missing: Boolean, val subscriptions: List<String>)
data class ResourceGraphEdge(val from: String, val to: String, val role: String, val enabled: Boolean)
data class ResourceGraph(val nodes: List<ResourceGraphNode> = emptyList(), val edges: List<ResourceGraphEdge> = emptyList(),
    val available: Boolean = true) {
    fun node(key: String) = nodes.firstOrNull { it.key == key }
    fun affected(key: String) = node(key)?.subscriptions.orEmpty()
}

internal fun resourceGraph(library: Library): ResourceGraph {
    val subscriptions = JSONArray().apply { library.subscriptions.forEach {
        put(JSONObject().put("id", it.id).put("name", it.name).put("handlerId", it.handlerId.orEmpty())
            .put("resourceIds", JSONArray(it.resourceIds)).put("chainGroupIds", JSONArray(it.chainGroupIds)))
    } }
    val chains = JSONArray().apply { chainState(library.chainLibrary).groups.forEach {
        put(JSONObject().put("id", it.id).put("name", it.name))
    } }
    val result = JSONObject(Mobile.inspectResourceGraph(library.resources.toJson().toString(), subscriptions.toString(), chains.toString()))
    val nodes = result.getJSONArray("nodes")
    val edges = result.getJSONArray("edges")
    return ResourceGraph(List(nodes.length()) { nodes.getJSONObject(it).let { row ->
        ResourceGraphNode(row.getString("key"), row.getString("id"), row.getString("kind"), row.getString("name"),
            row.getBoolean("missing"), row.getJSONArray("subscriptions").strings())
    } }, List(edges.length()) { edges.getJSONObject(it).let { row ->
        ResourceGraphEdge(row.getString("from"), row.getString("to"), row.getString("role"), row.getBoolean("enabled"))
    } })
}

data class CandidateCheck(val id: String, val name: String, val passed: Boolean, val issue: BusinessIssue? = null)
// Retain an immutable source snapshot, not up to 50 expanded candidate documents.
data class ResourceCheck(val graph: ResourceGraph, val key: String, val candidates: List<CandidateCheck>, val library: Library)
