package io.jeemi.android.domain

import org.junit.Assert.*
import org.junit.Test
import java.util.Locale

class NodeNameSearchTest {
    private val names = listOf("HK GM 01", "JP gm 02", "HK GM EV 03", "JP GM ev 04", "HK 05", "US GM 06", "GM 07")
    private val group = ProxyGroup("HK JP GM selector", "select", names)
    private fun result(query: String) = NodeNameSearch(query).filter(listOf(group), names.toSet()).flatMap { it.members }

    @Test fun desktopConditionOrderKeepsTheSameUnionIntersectionAndExclusions() {
        listOf(" hk | jp & gm & !ev", " hk& gm & !ev  | jp ", "hk|jp&gm&!ev",
            "hk     | jp         & gm  &   !   ev  ", "hk!ev|jp&gm", "hk!ev&gm|jp",
            "hk&gm|jp!ev", "hk|jp!ev&gm", "&gm!ev|hk|jp", "!ev&gm|jp|hk").forEach {
            assertEquals(it, listOf("HK GM 01", "JP gm 02"), result(it))
        }
    }

    @Test fun desktopSingleMultipleNegativeAndDuplicateConditions() {
        mapOf(
            "hk|jp" to listOf(0, 1, 2, 3, 4), "hk&gm" to listOf(0, 2), "&gm&jp!ev" to listOf(1),
            "!ev!us" to listOf(0, 1, 4, 6), "hk|jp&gm&01!ev" to listOf(0), "hk&gm!ev!01" to emptyList(),
            "hk|hk&gm&gm!ev!ev" to listOf(0), "hk | " to listOf(0, 2, 4)
        ).forEach { (query, indexes) -> assertEquals(query, indexes.map(names::get), result(query)) }
    }

    @Test fun emptyOperatorsKeepTheOriginalViewAndDoNotForceExpansion() {
        val groups = listOf(group.copy(members = names + "DIRECT"))
        listOf("", " \n\t ", " | & ! ").forEach { query ->
            val search = NodeNameSearch(query)
            assertFalse(search.active)
            assertSame(groups, search.filter(groups, names.toSet()))
        }
    }

    @Test fun mobileInputAcceptsWideOperatorsAndTrimsTermEdgesBeforeMatchingDottedNames() {
        val nodes = listOf("Pro.HK.gm", "Pro.HK.ev", "Pro.JP.gm")
        fun found(query: String) = nodes.filter(NodeNameSearch(query)::matches)
        listOf("hk", "hk ", " hk ", "\u3000hk\u3000", "\u00a0hk\u00a0", "\ufeffhk\ufeff", "hk＆").forEach {
            assertEquals(it, nodes.take(2), found(it))
        }
        listOf("hk & gm", " hk ＆ gm ", "hk＆gm", "\u3000hk\u3000＆\u3000gm\u3000",
            "\ufeffhk\ufeff & \ufeffgm\ufeff", "hk ＆ gm ！ev").forEach {
            assertEquals(it, listOf(nodes[0]), found(it))
        }
        assertEquals(listOf(nodes[0], nodes[2]), found("hk｜jp ＆gm！ev"))
        assertEquals(listOf(nodes[0], nodes[2]), found("！ev"))
        assertFalse(NodeNameSearch("\u3000｜ ＆ ！\ufeff").active)
        // Preserve internal text instead of applying compatibility normalization to node names.
        assertFalse(NodeNameSearch("hk gm").matches("Pro.HK.gm"))
        assertFalse(NodeNameSearch("tokyo  node").matches("Tokyo node"))
    }

    @Test fun searchMatchesOnlyNodeNamesIncludingGeneratedAndProviderMembers() {
        val nodes = setOf("Landing ⇐ HK GM 01 · stable", "JP provider node")
        val selectors = listOf(ProxyGroup("Primary group", "select", nodes.toList() + listOf("Nested group", "DIRECT"), listOf("provider-alpha")))
        assertEquals(listOf("Landing ⇐ HK GM 01 · stable"), NodeNameSearch("landing & hk !ev").filter(selectors, nodes).single().members)
        assertEquals(listOf("JP provider node"), NodeNameSearch("jp & provider").filter(selectors, nodes).single().members)
        listOf("primary", "group|direct", "socks5|provider-alpha").forEach { assertTrue(NodeNameSearch(it).filter(selectors, nodes).isEmpty()) }
        assertEquals(nodes.toList(), NodeNameSearch("!missing").filter(selectors, nodes).single().members)
    }

    @Test fun literalSpacesUnicodeAndCaseDoNotDependOnDeviceLocale() {
        val initial = Locale.getDefault()
        try {
            Locale.setDefault(Locale.forLanguageTag("tr-TR"))
            assertTrue(NodeNameSearch(" id & gm").matches("ID GM"))
            assertTrue(NodeNameSearch("  TOKYO node  ").matches("Tokyo node"))
            assertFalse(NodeNameSearch("Tokyo     node").matches("Tokyo node"))
            assertTrue(NodeNameSearch("香港|日本&专线!到期").matches("🇯🇵 日本 专线 01"))
            assertFalse(NodeNameSearch("香港|日本&专线!到期").matches("香港 专线 到期"))
        } finally { Locale.setDefault(initial) }
    }

    @Test fun batchUsesExactlyVisibleRealNodesAndDoesNotFallbackOrChangePreviousBatch() {
        val selectors = listOf(group, group.copy(name = "Other"))
        val started = nodeTestTargets(NodeNameSearch("hk & gm & !ev | jp").filter(selectors, names.toSet()), names.toSet())
        assertEquals(listOf("HK GM 01", "JP gm 02"), started)
        assertEquals(emptyList<String>(), nodeTestTargets(NodeNameSearch("missing").filter(selectors, names.toSet()), names.toSet()))
        assertEquals(listOf("HK 05"), nodeTestTargets(NodeNameSearch("&hk!gm").filter(selectors, names.toSet()), names.toSet()))
        assertEquals(listOf("HK GM 01", "JP gm 02"), started)
        assertEquals(names, group.members)
    }
}
