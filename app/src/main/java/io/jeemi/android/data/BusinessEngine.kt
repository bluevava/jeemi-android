package io.jeemi.android.data

import io.jeemi.android.domain.*
import org.json.JSONArray
import io.jeemi.android.domain.Preferences
import io.jeemi.android.domain.ProxyNode
import io.jeemi.android.domain.Subscription
import mobile.Mobile
import org.json.JSONObject
import java.util.UUID

interface BusinessEngine {
    fun normalize(name: String, original: String): Subscription
    fun compose(subscription: Subscription, preferences: Preferences, resources: List<LocalResource> = emptyList(), chainLibrary: String = ""): String
}

data class Candidate(
    val yaml: String, val structure: SubscriptionStructure,
    val providers: List<RuleProvider>, val fallbackOptions: List<String>,
    val fallbackOriginal: String, val fallbackMode: String, val fallbackSelector: String,
    val fallbackReset: Boolean,
    val chains: String = "", val normalization: String = "", val nodes: List<ProxyNode> = emptyList(),
) { val revision: String = io.jeemi.android.runtime.configurationRevision(yaml + "\u0000" +
    (if (normalization.isBlank()) "" else JSONObject(normalization).optString("fingerprint")) + "\u0000" +
    (if (chains.isBlank()) "" else JSONObject(chains).optString("fingerprint"))) }

class GoBusinessEngine : BusinessEngine {
    fun prepare(resource: LocalResource): JSONObject = JSONObject(Mobile.prepareResource(resource.kind.name, resource.content, resource.strategy))

    private fun applyResource(configuration: String, resource: LocalResource): String {
        val prepared = prepare(resource)
        return Mobile.composeConfiguration(configuration, prepared.getString("content"), prepared.getJSONArray("plan").toString())
    }

    fun catalog(): List<ConfigField> {
        val categories = JSONObject(Mobile.fieldCatalog()).getJSONArray("categories")
        return buildList {
            repeat(categories.length()) { index ->
                val category = categories.getJSONObject(index)
                val fields = category.getJSONArray("fields")
                repeat(fields.length()) { i ->
                    val field = fields.getJSONObject(i)
                        add(ConfigField(field.getString("path"), category.getString("id"), field.optString("editor"),
                            field.optString("exampleYaml"), field.optJSONArray("options").strings(),
                            field.optString("documentationUrl", category.getString("documentationUrl")),
                            field.optJSONArray("strategies").strings(), field.optString("defaultStrategy", "replace"),
                            field.optString("defaultConflictPolicy", "error"), field.optBoolean("locked"),
                            field.optBoolean("hidden"), field.optString("scope")))
                }
            }
        }
    }

    fun inspect(subscription: Subscription): SubscriptionStructure {
        val json = JSONObject(Mobile.inspectSubscription(subscription.normalized))
        return decodeStructure(json)
    }

    private fun decodeStructure(json: JSONObject): SubscriptionStructure {
        val groups = json.getJSONArray("groups")
        val providers = json.getJSONArray("providers")
        return SubscriptionStructure(List(groups.length()) { i -> groups.getJSONObject(i).let {
            ProxyGroup(it.getString("name"), it.getString("type"), it.getJSONArray("members").strings(), it.getJSONArray("providers").strings(), it.getBoolean("hidden"), it.optString("defaultSelected"), it.optString("icon"))
        } }, List(providers.length()) { i -> providers.getJSONObject(i).let {
            RuleProvider(it.getString("name"), it.getString("type"), it.getString("behavior"))
        } })
    }

    override fun normalize(name: String, original: String): Subscription {
        val result = JSONObject(Mobile.normalizeSubscription(original))
        val report = result.getJSONObject("report")
        val nodes = result.getJSONArray("nodes")
        return Subscription(
            id = UUID.randomUUID().toString(), name = name.trim(), original = original,
            normalized = result.getString("yaml"), format = report.getString("format"),
            requiredCore = report.optString("requiredCore"), skippedNodes = report.getInt("skippedNodes"),
            normalizationVersion = Mobile.subscriptionParserVersion().toInt(), normalizationReport = report.toString(),
            nodes = List(nodes.length()) { i ->
                nodes.getJSONObject(i).let { ProxyNode(it.getString("name"), it.getString("type")) }
            },
        )
    }

    override fun compose(subscription: Subscription, preferences: Preferences, resources: List<LocalResource>, chainLibrary: String): String {
        return project(subscription, preferences, resources, chainLibrary).yaml
    }

    fun reparse(subscription: Subscription): Subscription {
        if (subscription.normalizationVersion == Mobile.subscriptionParserVersion().toInt()) return subscription
        val parsed = normalize(subscription.name, subscription.original)
        return subscription.copy(normalized = parsed.normalized, format = parsed.format, requiredCore = parsed.requiredCore,
            skippedNodes = parsed.skippedNodes, nodes = parsed.nodes, normalizationVersion = parsed.normalizationVersion,
            normalizationReport = parsed.normalizationReport)
    }

    fun normalizePreferences(preferences: Preferences): Preferences {
        val runtime = if (preferences.runtimeJson.isBlank())
            Mobile.upgradeRuntimePreferences(JSONObject(preferences.runtimeValues).toString())
        else Mobile.normalizeRuntimePreferences(preferences.runtimeJson)
        require(preferences.testConcurrency in 1..32)
        require(preferences.connectionReset in listOf("off", "selector", "all"))
        return preferences.copy(runtimeJson = runtime, overrideMode = true)
    }

    fun prepareTyped(resource: LocalResource): LocalResource {
        val normalized = if (resource.formatVersion >= 2 && resource.kind == ResourceKind.GROUPS) {
            val content = JSONObject(resource.content)
            content.optJSONObject("filter")?.let { filter ->
                filter.put("namePatterns", JSONArray(filter.optJSONArray("namePatterns").strings().map { it.trim() }.filter { it.isNotBlank() }))
            }
            resource.copy(content = content.toString())
        } else resource
        return resourceFromJson(JSONObject(Mobile.prepareStructuredResource(normalized.toJson().toString())))
    }

    fun validateResources(resources: List<LocalResource>) = Mobile.validateResourceLibrary(resources.toJson().toString())

    fun project(subscription: Subscription, preferences: Preferences, resources: List<LocalResource>, chainLibrary: String = ""): Candidate {
        val normalized = normalizePreferences(preferences)
        val request = JSONObject().put("configuration", subscription.normalized)
            .put("original", subscription.original)
            .put("chainLibrary", JSONObject(chainLibrary.ifBlank { "{\"version\":1,\"revision\":0,\"groups\":[]}" }))
            .put("chainGroupIds", JSONArray(subscription.chainGroupIds)).put("chainRevision", subscription.chainRevision)
            .put("resources", resources.toJson()).put("handlerId", subscription.handlerId ?: "")
            .put("attached", JSONArray(subscription.resourceIds)).put("runtime", JSONObject(normalized.runtimeJson))
            .put("mode", preferences.mode.configValue).put("disabledProviders", JSONArray(subscription.disabledProviders))
            .put("fallback", JSONObject().put("mode", subscription.fallbackMode).put("selector", subscription.fallbackSelector))
            .put("legacyValues", JSONObject(preferences.runtimeValues))
            .put("geoMode", preferences.geoMode).put("geoLoader", preferences.geoLoader)
        val result = JSONObject(Mobile.composeWorkspace(request.toString()))
        val fallback = result.getJSONObject("fallback")
        val selected = fallback.getJSONObject("selection")
        val providers = result.getJSONArray("providers")
        return Candidate(result.getString("yaml"), decodeStructure(result.getJSONObject("structure")),
            List(providers.length()) { i -> providers.getJSONObject(i).let {
                RuleProvider(it.getString("name"), it.optString("type"), it.optString("behavior")) } },
            fallback.getJSONArray("selectors").strings(), fallback.optString("originalTarget"),
            selected.getString("mode"), selected.optString("selector"), fallback.getBoolean("reset"),
            result.getJSONObject("chains").toString(), result.optJSONObject("normalization")?.toString().orEmpty(),
            result.getJSONArray("nodes").let { array -> List(array.length()) { i -> array.getJSONObject(i).let { ProxyNode(it.getString("name"), it.getString("type")) } } })
    }
}

internal fun JSONArray?.strings(): List<String> = this?.let { array -> List(array.length()) { array.getString(it) } } ?: emptyList()
internal fun LocalResource.toJson() = JSONObject().put("id", id).put("name", name).put("kind", kind.name)
    .put("content", content).put("strategy", strategy).put("description", description).put("formatVersion", formatVersion)
internal fun List<LocalResource>.toJson() = JSONArray().apply { this@toJson.forEach { put(it.toJson()) } }
internal fun resourceFromJson(json: JSONObject) = LocalResource(json.getString("id"), json.getString("name"),
    ResourceKind.valueOf(json.getString("kind")), json.getString("content"), json.optString("strategy", "auto"),
    json.optInt("formatVersion", 1), json.optString("description"))
