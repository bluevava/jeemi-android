package io.jeemi.android.domain

enum class AppTheme { SYSTEM, LIGHT, DARK }
enum class ProxyMode(val configValue: String) { RULE("rule"), GLOBAL("global"), DIRECT("direct") }
enum class Destination { HOME, SUBSCRIPTIONS, CONFIG, MORE }
enum class ToolPage { CONNECTIONS, LOGS, DNS, CORE, GEO, ABOUT }
enum class ResourceKind { CONFIG, SCRIPT, GROUPS, RULES }
enum class NodeSort { ORIGINAL, NAME, TYPE, DELAY }
enum class NodeDensity { LARGE, MEDIUM, SMALL }
const val EMPTY_CHAIN_LIBRARY = "{\"version\":1,\"revision\":0,\"groups\":[]}"

data class LocalResource(val id: String, val name: String, val kind: ResourceKind,
    val content: String, val strategy: String = "auto", val formatVersion: Int = 1,
    val description: String = "", val sourceUrl: String = "")
data class ProxyGroup(val name: String, val type: String, val members: List<String>,
    val providers: List<String> = emptyList(), val hidden: Boolean = false, val defaultSelected: String = "",
    val icon: String = "")
data class RuleProvider(val name: String, val type: String, val behavior: String)
data class SubscriptionStructure(val groups: List<ProxyGroup> = emptyList(), val providers: List<RuleProvider> = emptyList())
data class ConfigField(val path: String, val category: String, val editor: String, val example: String,
    val options: List<String>, val documentation: String, val strategies: List<String> = listOf("replace"),
    val defaultStrategy: String = "replace", val defaultConflict: String = "error",
    val locked: Boolean = false, val hidden: Boolean = false, val scope: String = "")

data class ProxyNode(val name: String, val type: String)

data class Subscription(
    val id: String,
    val name: String,
    val original: String,
    val normalized: String,
    val format: String,
    val requiredCore: String,
    val skippedNodes: Int,
    val nodes: List<ProxyNode>,
    val description: String = "",
    val sourceUrl: String = "",
    val updatedAt: Long = 0,
    val handlerId: String? = null,
    val resourceIds: List<String> = emptyList(),
    val icon: String = "🌐",
    val fallbackMode: String = "none",
    val fallbackSelector: String = "",
    val disabledProviders: List<String> = emptyList(),
    val uploadedBytes: Long? = null,
    val downloadedBytes: Long? = null,
    val totalBytes: Long? = null,
    val expiresAt: Long? = null,
    val iconImage: String = "",
    val ruleProviderCount: Int = 0,
    val selections: Map<String, String> = emptyMap(),
    val normalizationVersion: Int = 0,
    val normalizationReport: String = "",
    val chainGroupIds: List<String> = emptyList(),
    val chainRevision: Int = 0,
)

data class Preferences(
    val theme: AppTheme = AppTheme.SYSTEM,
    val mode: ProxyMode = ProxyMode.RULE,
    val overrideMode: Boolean = true,
    val runtimeValues: Map<String, String> = emptyMap(),
    val nodeSort: NodeSort = NodeSort.ORIGINAL,
    val compactNodes: Boolean = false,
    val showHiddenGroups: Boolean = false,
    val runtimeJson: String = "",
    val nodeDensity: NodeDensity = NodeDensity.MEDIUM,
    val testConcurrency: Int = 8,
    val connectionReset: String = "selector",
    val geoMode: String = "mmdb",
    val geoLoader: String = "memconservative",
    val externalUIEnabled: Boolean = false,
    val externalUIVersion: String = "",
)

data class Library(
    val subscriptions: List<Subscription> = emptyList(),
    val selectedId: String? = null,
    val preferences: Preferences = Preferences(),
    val resources: List<LocalResource> = emptyList(),
    val chainLibrary: String = EMPTY_CHAIN_LIBRARY,
) {
    val selected: Subscription? get() = subscriptions.firstOrNull { it.id == selectedId }
    fun selecting(id: String): Library {
        require(subscriptions.any { it.id == id })
        return copy(selectedId = id)
    }
    fun removing(id: String): Library = copy(
        subscriptions = subscriptions.filterNot { it.id == id },
        selectedId = selectedId.takeUnless { it == id },
    )
    fun removingResource(id: String): Library = copy(resources = resources.filterNot { it.id == id },
        subscriptions = subscriptions.map { it.copy(handlerId = it.handlerId.takeUnless { value -> value == id },
            resourceIds = it.resourceIds.filterNot { value -> value == id }) })

    fun associating(id: String, handlerId: String?, resourceIds: List<String>): Library {
        require(subscriptions.any { it.id == id })
        require(handlerId == null || resources.any { it.id == handlerId && it.kind in listOf(ResourceKind.CONFIG, ResourceKind.SCRIPT) })
        require(resourceIds.distinct().size == resourceIds.size && resourceIds.all { resourceId ->
            resources.any { it.id == resourceId && it.kind in listOf(ResourceKind.GROUPS, ResourceKind.RULES) }
        })
        return copy(subscriptions = subscriptions.map { if (it.id == id) it.copy(handlerId = handlerId, resourceIds = resourceIds) else it })
    }
}

// The UI must not turn a desired setting into an active-core status.
sealed interface RuntimeState {
    data object Stopped : RuntimeState
    data object Starting : RuntimeState
    data object Restarting : RuntimeState
    data object Stopping : RuntimeState
    data class Running(val activeRevision: String) : RuntimeState
    data class Failed(val reason: String) : RuntimeState
}

val RuntimeState.transitioning: Boolean get() = this == RuntimeState.Starting ||
    this == RuntimeState.Restarting || this == RuntimeState.Stopping
