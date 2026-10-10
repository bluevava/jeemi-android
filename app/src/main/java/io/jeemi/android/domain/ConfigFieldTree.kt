package io.jeemi.android.domain

/** JSON Pointer identities stay intact; labels alone decode escaped path segments. */
internal data class ConfigFieldNode(
    val path: String,
    val label: String,
    val field: ConfigField?,
    val children: List<ConfigFieldNode>,
) {
    val fields: List<ConfigField> get() = listOfNotNull(this.field) + children.flatMap { it.fields }
}

internal fun configFieldTree(fields: List<ConfigField>): List<ConfigFieldNode> {
    fun build(prefix: String, remaining: List<ConfigField>): List<ConfigFieldNode> = remaining
        .groupBy { it.path.removePrefix("$prefix/").substringBefore('/') }
        .map { (segment, members) ->
            val path = "$prefix/$segment"
            ConfigFieldNode(path, segment.replace("~1", "/").replace("~0", "~"),
                members.firstOrNull { it.path == path }, build(path, members.filter { it.path != path }))
        }
    return build("", fields)
}

/** Locked fields are an explicit read-only overlay, also when filtering local enabled fields. */
internal fun filterConfigFieldTree(
    nodes: List<ConfigFieldNode>, enabledPaths: Set<String>, onlyEnabled: Boolean, showLocked: Boolean,
    query: String, descriptions: Map<String, String>,
): List<ConfigFieldNode> {
    val term = query.trim()
    fun filter(node: ConfigFieldNode, parentMatches: Boolean): ConfigFieldNode? {
        val matches = parentMatches || node.path.contains(term, ignoreCase = true) ||
            descriptions[node.path].orEmpty().contains(term, ignoreCase = true)
        val field = node.field?.takeIf {
            (if (it.locked) showLocked else !it.hidden && (!onlyEnabled || it.path in enabledPaths)) && matches
        }
        val children = node.children.mapNotNull { filter(it, matches) }
        return if (field == null && children.isEmpty()) null else node.copy(field = field, children = children)
    }
    return nodes.mapNotNull { filter(it, false) }
}
