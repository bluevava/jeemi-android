package io.jeemi.android.domain

import java.util.Locale

/** Desktop selectorSearch.ts: operators label the next term. Union first,
 * then every required term, then exclusions; this is not a Boolean AST. */
internal class NodeNameSearch(query: String) {
    private val any = mutableListOf<String>()
    private val all = mutableListOf<String>()
    private val exclude = mutableListOf<String>()

    init {
        var operator = '|'
        var start = 0
        fun append(end: Int) {
            // Include the BOM trimmed by desktop JavaScript, alongside regular,
            // non-breaking and ideographic edge spaces. Internal text stays literal.
            val term = query.substring(start, end).trim { it.isWhitespace() || it == '\ufeff' }.lowercase(Locale.ROOT)
            if (term.isNotEmpty()) when (operator) {
                '&' -> all.add(term)
                '!' -> exclude.add(term)
                else -> any.add(term)
            }
        }
        query.forEachIndexed { index, char ->
            // Chinese IMEs may commit full-width punctuation. Parse aliases without
            // rewriting the input field or normalizing the user's node names.
            val next = when (char) { '|', '｜' -> '|'; '&', '＆' -> '&'; '!', '！' -> '!'; else -> null }
            if (next != null) {
                append(index)
                operator = next
                start = index + 1
            }
        }
        append(query.length)
    }

    val active: Boolean get() = any.isNotEmpty() || all.isNotEmpty() || exclude.isNotEmpty()

    fun matches(name: String): Boolean {
        val normalized = name.lowercase(Locale.ROOT)
        return (any.isEmpty() || any.any(normalized::contains)) &&
            all.all(normalized::contains) && exclude.none(normalized::contains)
    }

    fun filter(selectors: List<ProxyGroup>, nodeNames: Set<String>): List<ProxyGroup> {
        if (!active) return selectors
        return selectors.mapNotNull { selector ->
            val members = selector.members.filter { it in nodeNames && matches(it) }
            if (members.isEmpty()) null else selector.copy(members = members)
        }
    }
}

/** Batch tests consume the rendered result. No-match searches never fall back
 * to all nodes; a returned list is independent from the next query. */
internal fun nodeTestTargets(selectors: List<ProxyGroup>, nodeNames: Set<String>): List<String> =
    selectors.flatMap { it.members }.filter { it in nodeNames }.distinct()

internal val builtinProxyNames = setOf("DIRECT", "REJECT", "REJECT-DROP", "PASS", "COMPATIBLE", "GLOBAL")
