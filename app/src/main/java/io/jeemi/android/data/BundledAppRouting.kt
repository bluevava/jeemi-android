package io.jeemi.android.data

import android.content.Context
import android.content.res.Configuration
import androidx.appcompat.app.AppCompatDelegate
import io.jeemi.android.R
import io.jeemi.android.domain.*
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

/** Seed editable resources once. Never overwrite user resources, bind subscriptions or resurrect deletions. */
internal fun withBundledAppRouting(library: Library, context: Context, engine: GoBusinessEngine): Library {
    if (library.bundledRoutingVersion >= 1 || library.resources.size > 93) return library
    val locale = AppCompatDelegate.getApplicationLocales()[0]
    val localized = if (locale == null) context else context.createConfigurationContext(
        Configuration(context.resources.configuration).apply { setLocale(locale) })
    val usedNames = library.resources.flatMap { resource ->
        val emoji = if (resource.kind == ResourceKind.GROUPS && resource.formatVersion >= 2)
            runCatching { JSONObject(resource.content).optString("emoji") }.getOrDefault("") else ""
        listOf(resource.name, listOf(emoji, resource.name).filter(String::isNotBlank).joinToString(" "))
    }.toMutableSet()
    fun name(label: Int, emoji: String = ""): String {
        val base = localized.getString(label)
        val value = generateSequence(1) { it + 1 }.map {
            if (it == 1) base else localized.getString(R.string.bundled_routing_duplicate_name, base, it)
        }.first { it !in usedNames && "$emoji $it".trim() !in usedNames }
        usedNames += value
        usedNames += "$emoji $value".trim()
        return value
    }
    val usedIds = library.resources.map { it.id }.toMutableSet()
    fun id(): String = generateSequence { UUID.randomUUID().toString().replace("-", "") }.first { usedIds.add(it) }
    val configId = id(); val selectorId = id(); val directGroupId = id(); val blockGroupId = id()
    val directId = id(); val proxyId = id(); val blockId = id()
    val configName = name(R.string.bundled_routing_name)
    val selectorName = name(R.string.bundled_routing_selector, "🚀")
    val directName = name(R.string.bundled_routing_direct_group)
    val blockName = name(R.string.bundled_routing_block_group)
    fun resource(id: String, name: String, kind: ResourceKind, content: JSONObject) =
        engine.prepareTyped(LocalResource(id, name, kind, content.toString(), formatVersion = 2))
    fun ruleSet(id: String, label: Int) = resource(id, name(label), ResourceKind.RULES,
        JSONObject().put("sourceType", "inline").put("behavior", "classical").put("format", "yaml").put("payloadYaml", "payload:\n"))
    fun group(id: String, name: String, ruleId: String, policy: String) = resource(id, name, ResourceKind.GROUPS,
        JSONObject().put("kind", if (policy.isEmpty()) "selector" else "rule").put("type", "select")
            .put("emoji", if (policy.isEmpty()) "🚀" else "").put("includeAllProviders", policy.isEmpty())
            .put("ruleOutput", "inline").put("policy", JSONObject().put("mode", policy))
            .put("ruleSetReferences", JSONArray().put(JSONObject().put("ruleSetId", ruleId))))
    val preset = listOf(
        resource(configId, configName, ResourceKind.CONFIG, JSONObject().put("fields", JSONArray()).put("resourcePlan",
            JSONObject().put("version", 1).put("ruleStrategy", "replace").put("rulesDisabled", false)
                .put("strategyGroupIds", JSONArray(listOf(blockGroupId, directGroupId, selectorId)))
                .put("disabledStrategyGroupIds", JSONArray()).put("defaultProxySelectorId", selectorId)
                .put("match", JSONObject().put("mode", "selector").put("selectorId", selectorId)))),
        group(selectorId, selectorName, proxyId, ""),
        group(directGroupId, directName, directId, "direct"),
        group(blockGroupId, blockName, blockId, "reject"),
        ruleSet(directId, R.string.bundled_routing_direct),
        ruleSet(proxyId, R.string.bundled_routing_proxy),
        ruleSet(blockId, R.string.bundled_routing_block),
    )
    // New IDs and names cannot change existing associations or candidates. Validate the isolated graph before saving.
    engine.validateResources(preset)
    return library.copy(resources = library.resources + preset, bundledRoutingVersion = 1)
}
