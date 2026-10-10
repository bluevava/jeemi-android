package io.jeemi.android.domain

import org.junit.Assert.*
import org.junit.Test

class ConfigFieldTreeTest {
    private fun field(path: String, locked: Boolean = false, hidden: Boolean = false) =
        ConfigField(path, "dns", "boolean", "false", emptyList(), "", locked = locked, hidden = hidden)
    private val fields = listOf(field("/tcp-concurrent"), field("/dns/respect-rules"),
        field("/dns/enable", locked = true), field("/tun/stack", locked = true, hidden = true),
        field("/proxies/*/udp"), field("/name~1with~0escape"))
    private fun flatten(nodes: List<ConfigFieldNode>): List<ConfigFieldNode> = nodes.flatMap { listOf(it) + flatten(it.children) }

    @Test fun enabledFilterKeepsAncestorsAndExplicitLockedOverlay() {
        val tree = configFieldTree(fields)
        val filtered = filterConfigFieldTree(tree, setOf("/dns/respect-rules"), true, false, "", emptyMap())
        assertEquals(listOf("/dns", "/dns/respect-rules"), flatten(filtered).map { it.path })
        val locked = flatten(filterConfigFieldTree(tree, emptySet(), true, true, "", emptyMap()))
        assertEquals(listOf("/dns/enable", "/tun/stack"), locked.mapNotNull { it.field?.path })
        assertTrue(locked.filter { it.field != null }.all { it.field!!.locked })
    }
    @Test fun searchDescriptionKeepsOnlyMatchingBranchesAndParentSearchIncludesChildren() {
        val tree = configFieldTree(fields)
        val descriptions = mapOf("/dns/respect-rules" to "解析策略 resolution policy")
        val found = filterConfigFieldTree(tree, emptySet(), false, false, " RESOLUTION ", descriptions)
        assertEquals(listOf("/dns", "/dns/respect-rules"), flatten(found).map { it.path })
        val parent = filterConfigFieldTree(tree, emptySet(), false, true, "/dns", descriptions)
        assertEquals(2, parent.single().children.size)
        assertTrue(filterConfigFieldTree(tree, emptySet(), false, false, "not-a-field", descriptions).isEmpty())
    }
    @Test fun labelsDecodeWithoutChangingPointerOrWildcardIdentity() {
        val tree = configFieldTree(fields)
        assertEquals("name/with~escape", tree.last().label)
        assertEquals("/name~1with~0escape", tree.last().path)
        val proxy = tree.first { it.path == "/proxies" }.children.single()
        assertEquals("*", proxy.label)
        assertEquals("/proxies/*/udp", proxy.fields.single().path)
    }
    @Test fun unexposedFieldsDoNotLeakThroughParentSearch() {
        val nodes = configFieldTree(listOf(field("/internal/value", hidden = true)))
        assertTrue(filterConfigFieldTree(nodes, emptySet(), false, true, "/internal", emptyMap()).isEmpty())
    }
}
