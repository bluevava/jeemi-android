package io.jeemi.android.domain

import org.junit.Assert.*
import org.junit.Test

class SelectorNavigationTest {
    private val root = ProxyGroup("Root", "select", listOf("Hidden", "DIRECT"))
    private val child = ProxyGroup("Hidden", "select", listOf("HK", "JP"), hidden = true, defaultSelected = "HK")
    private val groups = listOf(root, child).associateBy { it.name }

    @Test fun browsingHiddenGroupsNeverChangesChoicesAndInvalidEdgesStop() {
        val choices = mapOf("Root" to "DIRECT")
        assertEquals(listOf(root, child), selectorPath(root, listOf("Hidden", "Root"), groups))
        assertEquals(listOf(root), selectorPath(root, listOf("Missing", "Hidden"), groups))
        assertEquals(listOf("DIRECT"), selectorEgress(root, groups, choices, true).names)
        val revised = root.copy(members = listOf("DIRECT"))
        assertEquals(listOf(revised), selectorPath(revised, listOf("Hidden"), groups))
    }
    @Test fun liveEgressFollowsFullSnapshotAndOfflineUsesValidManualPreferences() {
        assertEquals(SelectorEgress(listOf("Hidden", "JP"), EgressEnd.NODE),
            selectorEgress(root, groups, mapOf("Root" to "Hidden", "Hidden" to "JP"), true))
        assertEquals(SelectorEgress(listOf("Hidden", "HK"), EgressEnd.NODE),
            selectorEgress(root, groups, mapOf("Hidden" to "Deleted"), false))
        assertEquals(EgressEnd.UNAVAILABLE, selectorEgress(root, groups, emptyMap(), true).end)
        assertEquals(EgressEnd.UNAVAILABLE, selectorEgress(root, groups, mapOf("Root" to "Deleted"), true).end)
    }
    @Test fun automaticBalancedRelayAndCyclesHaveHonestEndStates() {
        val automatic = child.copy(type = "url-test")
        assertEquals(EgressEnd.AUTOMATIC, selectorEgress(automatic, groups, mapOf("Hidden" to "JP"), false).end)
        assertEquals(listOf("JP"), selectorEgress(automatic, groups, mapOf("Hidden" to "JP"), true).names)
        assertEquals(EgressEnd.BALANCED, selectorEgress(child.copy(type = "load-balance"), groups, emptyMap(), true).end)
        assertEquals(EgressEnd.RELAY, selectorEgress(child.copy(type = "relay"), groups, emptyMap(), true).end)
        val cycle = child.copy(members = listOf("Root"))
        assertEquals(EgressEnd.CYCLE, selectorEgress(root, groups + ("Hidden" to cycle), mapOf("Root" to "Hidden", "Hidden" to "Root"), true).end)
    }
    @Test fun providerMembersUseActualMembershipAndSearchNeverInventsGroupResults() {
        val live = child.copy(members = listOf("Provider.HK"))
        assertEquals(listOf("Provider.HK"), selectorEgress(live, groups, mapOf("Hidden" to "Provider.HK"), true).names)
        assertTrue(NodeNameSearch("Hidden").filter(listOf(root, child), setOf("HK", "JP")).isEmpty())
        assertEquals(listOf("HK"), nodeTestTargets(NodeNameSearch("h").filter(listOf(root, child), setOf("HK", "JP")), setOf("HK", "JP")))
    }
    @Test fun terminalNodeExcludesIntermediateGroupsAndUnresolvedExits() {
        assertEquals("JP", selectorEgress(root, groups, mapOf("Root" to "Hidden", "Hidden" to "JP"), true).nodeName)
        assertEquals("HK", selectorEgress(root, groups, emptyMap(), false).nodeName)
        val automatic = child.copy(type = "url-test")
        assertEquals("JP", selectorEgress(root, groups + ("Hidden" to automatic),
            mapOf("Root" to "Hidden", "Hidden" to "JP"), true).nodeName)
        for (end in EgressEnd.entries.filter { it != EgressEnd.NODE }) {
            assertNull(SelectorEgress(listOf("Hidden"), end).nodeName)
        }
    }
}
