package io.jeemi.android.data

import android.util.AtomicFile
import io.jeemi.android.domain.*
import io.jeemi.android.domain.AppTheme
import io.jeemi.android.domain.Library
import io.jeemi.android.domain.Preferences
import io.jeemi.android.domain.ProxyMode
import io.jeemi.android.domain.ProxyNode
import io.jeemi.android.domain.Subscription
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

// Original and normalized documents are separate. An atomic write commits the
// whole selection/metadata change; a failed write must not replace live state.
class LibraryRepository(directory: File) {
    private val file = AtomicFile(File(directory, "library-v1.json"))

    fun load(): Library {
        if (!file.baseFile.exists() && !File(file.baseFile.path + ".bak").exists()) return Library()
        val json = JSONObject(file.openRead().use { input ->
            val bytes = input.readBounded(MAX_LIBRARY_BYTES)
            bytes.toString(Charsets.UTF_8)
        })
        require(json.getInt("schemaVersion") in 1..5)
        val preferences = json.getJSONObject("preferences")
        val list = json.getJSONArray("subscriptions")
        val library = Library(
            subscriptions = List(list.length()) { index -> decode(list.getJSONObject(index)) },
            selectedId = json.optString("selectedId").takeIf { it.isNotEmpty() },
            preferences = Preferences(
                theme = AppTheme.valueOf(preferences.getString("theme")),
                mode = ProxyMode.valueOf(preferences.getString("mode")),
                overrideMode = preferences.getBoolean("overrideMode"),
                runtimeValues = preferences.optJSONObject("runtimeValues")?.let { values -> values.keys().asSequence().associateWith { values.getString(it) } } ?: emptyMap(),
                nodeSort = NodeSort.valueOf(preferences.optString("nodeSort", "ORIGINAL")),
                compactNodes = preferences.optBoolean("compactNodes"),
                showHiddenGroups = preferences.optBoolean("showHiddenGroups"),
                runtimeJson = preferences.optString("runtimeJson"),
                nodeDensity = NodeDensity.valueOf(preferences.optString("nodeDensity", if (preferences.optBoolean("compactNodes")) "SMALL" else "MEDIUM")),
                testConcurrency = preferences.optInt("testConcurrency", 8),
                connectionReset = preferences.optString("connectionReset", "selector"),
                geoMode = preferences.optString("geoMode", "mmdb"),
                geoLoader = preferences.optString("geoLoader", "memconservative"),
                tunStack = TunStack.valueOf(preferences.optString("tunStack", "GVISOR")),
            ),
            resources = json.optJSONArray("resources")?.let { items -> List(items.length()) { i ->
                val item = items.getJSONObject(i)
                LocalResource(item.getString("id"), item.getString("name"), ResourceKind.valueOf(item.getString("kind")),
                    item.getString("content"), item.optString("strategy", "auto"),
                    item.optInt("formatVersion", 1), item.optString("description"))
            } } ?: emptyList(),
            chainLibrary = mobile.Mobile.normalizeChainLibrary(json.optJSONObject("chainLibrary")?.toString().orEmpty()),
        )
        require(library.selectedId == null || library.selected != null)
        require(library.subscriptions.map { it.id }.distinct().size == library.subscriptions.size)
        require(library.resources.map { it.id }.distinct().size == library.resources.size)
        library.subscriptions.forEach { library.associating(it.id, it.handlerId, it.resourceIds) }
        library.subscriptions.forEach { require(it.chainRevision >= 0 && it.chainGroupIds.size <= 128 && it.chainGroupIds.distinct().size == it.chainGroupIds.size) }
        return library
    }

    fun save(library: Library) {
        val preferences = library.preferences
        val json = JSONObject().put("schemaVersion", 5).put("selectedId", library.selectedId ?: "")
            .put("chainLibrary", JSONObject(mobile.Mobile.normalizeChainLibrary(library.chainLibrary)))
            .put("preferences", JSONObject().put("theme", preferences.theme.name)
                .put("mode", preferences.mode.name)
                .put("overrideMode", preferences.overrideMode)
                .put("runtimeValues", JSONObject(preferences.runtimeValues))
                .put("nodeSort", preferences.nodeSort.name).put("compactNodes", preferences.compactNodes)
                .put("showHiddenGroups", preferences.showHiddenGroups)
                .put("runtimeJson", preferences.runtimeJson).put("nodeDensity", preferences.nodeDensity.name)
                .put("testConcurrency", preferences.testConcurrency).put("connectionReset", preferences.connectionReset)
                .put("geoMode", preferences.geoMode).put("geoLoader", preferences.geoLoader).put("tunStack", preferences.tunStack.name))
            .put("resources", JSONArray().apply { library.resources.forEach { item ->
                put(JSONObject().put("id", item.id).put("name", item.name).put("kind", item.kind.name)
                    .put("content", item.content).put("strategy", item.strategy)
                    .put("formatVersion", item.formatVersion).put("description", item.description))
            } })
            .put("subscriptions", JSONArray().apply { library.subscriptions.forEach { put(encode(it)) } })
        val bytes = json.toString().toByteArray(Charsets.UTF_8)
        require(bytes.size <= MAX_LIBRARY_BYTES)
        val stream = file.startWrite()
        try {
            stream.write(bytes)
            file.finishWrite(stream)
        } catch (failure: Exception) {
            file.failWrite(stream)
            throw failure
        }
    }

    private fun encode(item: Subscription): JSONObject = JSONObject()
        .put("id", item.id).put("name", item.name).put("original", item.original)
        .put("normalized", item.normalized).put("format", item.format)
        .put("description", item.description).put("sourceUrl", item.sourceUrl).put("updatedAt", item.updatedAt)
        .put("handlerId", item.handlerId ?: "").put("resourceIds", JSONArray(item.resourceIds))
        .put("icon", item.icon).put("fallbackMode", item.fallbackMode).put("fallbackSelector", item.fallbackSelector)
        .put("selections", JSONObject(item.selections))
        .put("normalizationVersion", item.normalizationVersion).put("normalizationReport", item.normalizationReport)
        .put("chainGroupIds", JSONArray(item.chainGroupIds)).put("chainRevision", item.chainRevision)
        .put("iconImage", item.iconImage).put("ruleProviderCount", item.ruleProviderCount)
        .put("disabledProviders", JSONArray(item.disabledProviders))
        .put("uploadedBytes", item.uploadedBytes).put("downloadedBytes", item.downloadedBytes)
        .put("totalBytes", item.totalBytes).put("expiresAt", item.expiresAt)
        .put("requiredCore", item.requiredCore).put("skippedNodes", item.skippedNodes)
        .put("nodes", JSONArray().apply { item.nodes.forEach { put(JSONObject().put("name", it.name).put("type", it.type)) } })

    private fun decode(item: JSONObject): Subscription {
        val nodes = item.getJSONArray("nodes")
        return Subscription(item.getString("id"), item.getString("name"), item.getString("original"),
            item.getString("normalized"), item.getString("format"), item.getString("requiredCore"),
            item.getInt("skippedNodes"), List(nodes.length()) { index ->
                nodes.getJSONObject(index).let { ProxyNode(it.getString("name"), it.getString("type")) }
            }, description = item.optString("description"), sourceUrl = item.optString("sourceUrl"),
            updatedAt = item.optLong("updatedAt"), handlerId = item.optString("handlerId").takeIf { it.isNotEmpty() },
            resourceIds = item.optJSONArray("resourceIds")?.let { ids -> List(ids.length()) { ids.getString(it) } } ?: emptyList(),
            icon = item.optString("icon", "🌐"), fallbackMode = item.optString("fallbackMode", "none"),
            fallbackSelector = item.optString("fallbackSelector"), disabledProviders = item.optJSONArray("disabledProviders").strings(),
            uploadedBytes = item.optionalLong("uploadedBytes"), downloadedBytes = item.optionalLong("downloadedBytes"),
            totalBytes = item.optionalLong("totalBytes"), expiresAt = item.optionalLong("expiresAt"),
            selections = item.optJSONObject("selections")?.let { choices -> choices.keys().asSequence().associateWith { choices.getString(it) } } ?: emptyMap(),
            iconImage = item.optString("iconImage"), ruleProviderCount = item.optInt("ruleProviderCount"),
            normalizationVersion = item.optInt("normalizationVersion"), normalizationReport = item.optString("normalizationReport"),
            chainGroupIds = item.optJSONArray("chainGroupIds").strings(), chainRevision = item.optInt("chainRevision"))
    }

    companion object { const val MAX_LIBRARY_BYTES = 32 * 1024 * 1024 }
}

private fun JSONObject.optionalLong(key: String): Long? = if (has(key) && !isNull(key)) getLong(key) else null
