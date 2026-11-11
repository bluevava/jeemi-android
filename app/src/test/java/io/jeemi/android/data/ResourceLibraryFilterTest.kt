package io.jeemi.android.data

import io.jeemi.android.domain.LocalResource
import io.jeemi.android.domain.ResourceKind
import org.junit.Assert.*
import org.junit.Test

class ResourceLibraryFilterTest {
    private fun row(id: String, name: String, kind: ResourceKind, configs: Set<String> = emptySet(),
        owners: Set<String> = emptySet(), category: String = "", text: String = name) =
        ResourceLibraryRow(LocalResource(id, name, kind, "{}"), groupKind = category,
            searchText = text, configurations = configs, owners = owners)

    @Test fun filtersIntersectConfigurationCategoryAndMobileSearch() {
        val rows = listOf(row("a", "Pro.HK.gm", ResourceKind.GROUPS, setOf("c"), category = "selector"),
            row("b", "IM", ResourceKind.GROUPS, setOf("c"), category = "selector"),
            row("d", "Direct", ResourceKind.GROUPS, setOf("c"), category = "rule"),
            row("e", "Unlinked", ResourceKind.GROUPS, category = "selector"))
        assertEquals(listOf("a", "b"), filterResourceLibrary(rows, ResourceKind.GROUPS, "Pro.HK.gm　｜ IM　", "c", "selector").map { it.resource.id })
        assertEquals(listOf("a"), filterResourceLibrary(rows, ResourceKind.GROUPS, "Pro.HK.gm ", "c", "selector").map { it.resource.id })
        assertEquals(listOf("e"), filterResourceLibrary(rows, ResourceKind.GROUPS, "", UNLINKED_RESOURCES).map { it.resource.id })
        assertTrue(filterResourceLibrary(rows, ResourceKind.GROUPS, "missing", "c").isEmpty())
    }

    @Test fun ownerMultiselectIsUnionThenIntersectsConfigAndPayloadSearch() {
        val rows = listOf(row("a", "One", ResourceKind.RULES, setOf("c"), setOf("g1"), text = "One DOMAIN,example.com"),
            row("b", "Two", ResourceKind.RULES, setOf("c", "c2"), setOf("g2")),
            row("d", "Free", ResourceKind.RULES))
        assertEquals(listOf("a", "b", "d"), filterResourceLibrary(rows, ResourceKind.RULES, "", groups = listOf("g1", "g2", UNLINKED_RESOURCES)).map { it.resource.id })
        assertEquals(listOf("b"), filterResourceLibrary(rows, ResourceKind.RULES, "", "c2", groups = listOf("g1", "g2")).map { it.resource.id })
        assertEquals(listOf("a"), filterResourceLibrary(rows, ResourceKind.RULES, "example.com！other", "c").map { it.resource.id })
        assertTrue(filterResourceLibrary(rows, ResourceKind.RULES, "", "c", groups = listOf(UNLINKED_RESOURCES)).isEmpty())
    }

    @Test fun relationsIncludeProxyAndDisabledMatchWithoutGuessingFromText() {
        val resources = listOf(
            LocalResource("c1", "Config", ResourceKind.CONFIG, "{}"),
            LocalResource("c2", "Retained", ResourceKind.CONFIG, "{}"),
            LocalResource("c3", "Included", ResourceKind.CONFIG, "{}"),
            LocalResource("g", "Selector", ResourceKind.GROUPS, "{}"),
            LocalResource("r", "Rules", ResourceKind.RULES, "{}"),
            LocalResource("s", "Script", ResourceKind.SCRIPT, "c1 g r"),
            LocalResource("free", "c1 g r", ResourceKind.GROUPS, "{}"))
        val graph = ResourceGraph(edges = listOf(
            ResourceGraphEdge("resource:c1", "resource:g", "proxy", true),
            ResourceGraphEdge("resource:c2", "resource:g", "match", false),
            ResourceGraphEdge("resource:c3", "resource:g", "group", false),
            ResourceGraphEdge("resource:c3", "resource:g", "proxy", false),
            ResourceGraphEdge("resource:g", "resource:r", "rules", true)))
        val relations = resourceLibraryRelations(resources, graph)
        assertEquals(setOf("c1", "c2", "c3"), relations.getValue("g").configurations)
        assertEquals(setOf("c1", "c2", "c3"), relations.getValue("r").configurations)
        assertEquals(setOf("g"), relations.getValue("r").owners)
        assertTrue(relations.getValue("s").configurations.isEmpty())
        assertTrue(relations.getValue("free").configurations.isEmpty())
    }

    @Test fun legacyRuleYamlIsSearchableButScriptContentIsNot() {
        val resources = listOf(
            LocalResource("r", "Legacy", ResourceKind.RULES, "- DOMAIN,legacy.example,DIRECT", formatVersion = 1),
            LocalResource("s", "Script", ResourceKind.SCRIPT, "function main(c) { c.note = 'legacy.example'; return c; }"))
        val indexed = indexResourceLibrary(resources, ResourceGraph())
        assertEquals(listOf("r"), filterResourceLibrary(indexed, ResourceKind.RULES, "legacy.example　").map { it.resource.id })
        assertTrue(filterResourceLibrary(indexed, ResourceKind.SCRIPT, "legacy.example").isEmpty())
        val unavailable = indexResourceLibrary(resources, ResourceGraph(available = false))
        assertEquals(1, filterResourceLibrary(unavailable, ResourceKind.RULES, "legacy.example").size)
        assertTrue(filterResourceLibrary(unavailable, ResourceKind.RULES, "", UNLINKED_RESOURCES).isEmpty())
        assertTrue(filterResourceLibrary(unavailable, ResourceKind.RULES, "", groups = listOf(UNLINKED_RESOURCES)).isEmpty())
    }
}
