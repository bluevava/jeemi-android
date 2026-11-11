package io.jeemi.android.data

import io.jeemi.android.domain.LocalResource
import io.jeemi.android.domain.NodeNameSearch
import io.jeemi.android.domain.ResourceKind
import org.json.JSONObject

internal const val ALL_RESOURCES = ""
internal const val UNLINKED_RESOURCES = "unlinked"
private val configurationEdgeRoles = setOf("group", "proxy", "match")

internal data class ResourceLibraryRow(
    val resource: LocalResource,
    val emoji: String = "",
    val groupKind: String = "",
    val type: String = "",
    val policy: String = "",
    val source: String = "",
    val behavior: String = "",
    val references: Int = 0,
    val entries: Int = 0,
    val fields: Int = 0,
    val searchText: String = resource.name,
    val configurations: Set<String> = emptySet(),
    val owners: Set<String> = emptySet(),
    val relationsAvailable: Boolean = true,
)

internal data class ResourceLibraryRelations(val configurations: Set<String>, val owners: Set<String>)

// Relations come only from the engine's stable-ID graph, including disabled
// edges. A name or an ID mentioned in a description never creates a relation.
internal fun resourceLibraryRelations(resources: List<LocalResource>, graph: ResourceGraph): Map<String, ResourceLibraryRelations> {
    val kinds = resources.associate { "resource:${it.id}" to it.kind }
    val configsByGroup = graph.edges.filter { it.role in configurationEdgeRoles && kinds[it.from] == ResourceKind.CONFIG }
        .groupBy({ it.to }, { it.from.removePrefix("resource:") })
    val ownersByRule = graph.edges.filter { it.role == "rules" && kinds[it.from] == ResourceKind.GROUPS }
        .groupBy({ it.to }, { it.from.removePrefix("resource:") })
    return resources.associate { item ->
        val owners = ownersByRule["resource:${item.id}"].orEmpty().toSet()
        val groups = if (item.kind == ResourceKind.GROUPS) setOf(item.id) else owners
        val configurations = groups.flatMap { configsByGroup["resource:$it"].orEmpty() }.toSet()
        item.id to ResourceLibraryRelations(configurations, owners)
    }
}

internal fun indexResourceLibrary(resources: List<LocalResource>, graph: ResourceGraph): List<ResourceLibraryRow> {
    val relations = resourceLibraryRelations(resources, graph)
    return resources.map { item ->
        val content = if (item.formatVersion >= 2 && item.kind != ResourceKind.SCRIPT) JSONObject(item.content) else null
        val plan = content?.optJSONObject("resourcePlan")
        val emoji = content?.optString("emoji").orEmpty()
        val payload = if (item.kind != ResourceKind.RULES) "" else if (item.formatVersion < 2) item.content
            else content?.optString("payloadYaml").orEmpty()
        val relation = relations.getValue(item.id)
        ResourceLibraryRow(item, emoji, content?.optString("kind").orEmpty(), content?.optString("type").orEmpty(),
            content?.optJSONObject("policy")?.optString("mode").orEmpty(), content?.optString("sourceType").orEmpty(), content?.optString("behavior").orEmpty(),
            content?.optJSONArray("ruleSetReferences")?.length() ?: plan?.optJSONArray("strategyGroupIds")?.length() ?: 0,
            content?.optJSONArray("payload")?.length() ?: 0, content?.optJSONArray("fields")?.length() ?: 0,
            listOf(item.name, emoji, payload).filter(String::isNotEmpty).joinToString(" "),
            relation.configurations, relation.owners, graph.available)
    }
}

internal fun filterResourceLibrary(rows: List<ResourceLibraryRow>, kind: ResourceKind, query: String,
    configuration: String = ALL_RESOURCES, groupKind: String = ALL_RESOURCES,
    groups: List<String> = emptyList()): List<ResourceLibraryRow> {
    val matcher = NodeNameSearch(query)
    return rows.filter { row ->
        row.resource.kind == kind && matcher.matches(row.searchText) &&
            (row.relationsAvailable || (configuration == ALL_RESOURCES && groups.isEmpty())) &&
            (configuration == ALL_RESOURCES || if (configuration == UNLINKED_RESOURCES) row.configurations.isEmpty() else configuration in row.configurations) &&
            (kind != ResourceKind.GROUPS || groupKind == ALL_RESOURCES || row.groupKind == groupKind) &&
            (kind != ResourceKind.RULES || groups.isEmpty() || row.owners.any(groups::contains) ||
                (UNLINKED_RESOURCES in groups && row.owners.isEmpty()))
    }
}
