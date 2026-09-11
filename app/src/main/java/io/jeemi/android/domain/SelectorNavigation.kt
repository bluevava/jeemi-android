package io.jeemi.android.domain

// Adapted from desktop selectorNavigation.ts / selectorEgress.ts. Navigation
// never mutates a selection; egress always follows the complete snapshot.
internal enum class EgressEnd { NODE, BALANCED, RELAY, UNAVAILABLE, CYCLE, AUTOMATIC }
internal data class SelectorEgress(val names: List<String>, val end: EgressEnd) {
    val nodeName: String? get() = names.lastOrNull().takeIf { end == EgressEnd.NODE }
}

internal fun selectorPath(root: ProxyGroup, requested: List<String>, groups: Map<String, ProxyGroup>): List<ProxyGroup> {
    val path = mutableListOf(root)
    val seen = mutableSetOf(root.name)
    for (name in requested) {
        val child = groups[name] ?: break
        if (name !in path.last().members || !seen.add(name)) break
        path += child
    }
    return path
}

internal fun selectorChoice(group: ProxyGroup, choices: Map<String, String>, live: Boolean): String? {
    if (live) return choices[group.name]?.takeIf { it in group.members }
    if (group.type != "select") return null
    return choices[group.name]?.takeIf { it in group.members }
        ?: group.defaultSelected.takeIf { it in group.members } ?: group.members.firstOrNull()
}

internal fun selectorEgress(root: ProxyGroup, groups: Map<String, ProxyGroup>, choices: Map<String, String>, live: Boolean): SelectorEgress {
    var current = root
    val names = mutableListOf<String>()
    val seen = mutableSetOf<String>()
    while (seen.add(current.name)) {
        if (current.type == "load-balance") return SelectorEgress(names, EgressEnd.BALANCED)
        if (current.type == "relay") return SelectorEgress(names, EgressEnd.RELAY)
        if (!live && current.type != "select") return SelectorEgress(names, EgressEnd.AUTOMATIC)
        val selected = selectorChoice(current, choices, live) ?: return SelectorEgress(names, EgressEnd.UNAVAILABLE)
        names += selected
        current = groups[selected] ?: return SelectorEgress(names, EgressEnd.NODE)
    }
    return SelectorEgress(names, EgressEnd.CYCLE)
}

internal fun isScriptUrl(value: String): Boolean =
    value.trim().let { it.startsWith("http://", true) || it.startsWith("https://", true) }
