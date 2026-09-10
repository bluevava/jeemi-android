package io.jeemi.android.domain

import io.jeemi.android.runtime.*
import org.junit.Assert.*
import org.junit.Test

class DiagnosticsTest {
    @Test fun desiredChoiceFallsBackOnlyWhenMemberDisappears() {
        val members = listOf("DIRECT", "🇯🇵 Exact · Name", "Auto")
        assertEquals("🇯🇵 Exact · Name", preferredSelection(members, "🇯🇵 Exact · Name", "DIRECT"))
        assertEquals("Auto", preferredSelection(members, "gone", "Auto"))
        assertEquals("DIRECT", preferredSelection(members, "gone", "gone"))
        assertNull(preferredSelection(emptyList(), "old", "old"))
    }
    private fun connection(id: String) = ConnectionRecord(id, "example.com", "203.0.113.1", "443", "172.19.0.1", "12345", "tcp", "TUN",
        "org.example.browser", 12345, "ProcessName", "org.example.browser", listOf("Node", "Group"), 1024, 2048, "2026-09-09T00:00:00Z")
    @Test fun snapshotsRetainBoundedEndedConnectionsWithoutLosingActiveCounters() {
        val previous = List(250) { connection(it.toString()) }
        val updated = connection("0").copy(download = 9999)
        val next = mergeConnections(previous, listOf(updated))
        assertEquals(101, next.size)
        assertEquals(9999, next.first().download)
        assertFalse(next.first().ended)
        assertTrue(next.drop(1).all { it.ended })
        assertEquals(1, next.count { it.id == "0" })
    }
    @Test fun connectionSearchIncludesPackageAndRoutingChain() {
        val row = connection("one")
        assertTrue(row.matches("EXAMPLE.BROWSER")); assertTrue(row.matches("group"))
        assertFalse(row.matches("unrelated"))
    }
    @Test fun recentEndingsEvictOldHistoryButKeepRepeatedAppsAndTargets() {
        var feed = emptyList<ConnectionRecord>()
        repeat(500) { batch ->
            val active = List(20) { connection("$batch-$it") }
            feed = mergeConnections(feed, active)
            assertEquals(20, feed.count { !it.ended })
            assertTrue(feed.count { it.ended } <= ENDED_CONNECTION_LIMIT)
        }
        val history = feed.filter { it.ended }
        assertEquals(100, history.size)
        assertEquals("498-0", history.first().id)
        assertEquals("494-19", history.last().id)
        assertEquals(1, history.map { it.process to it.target }.distinct().size)
        assertEquals(100, history.map { it.id }.distinct().size)
    }
    @Test fun omittedLiveRecordsAreNotEndedAndBulkCloseIsImmediatelyBounded() {
        val previous = List(2000) { connection(it.toString()) }
        val next = mergeConnections(previous, previous.take(100), previous.mapTo(HashSet()) { it.id })
        assertEquals(100, next.size)
        assertTrue(next.none { it.ended })
        val closed = markClosedConnections(previous, previous.drop(5).mapTo(HashSet()) { it.id })
        assertEquals(5, closed.count { !it.ended })
        assertEquals(100, closed.count { it.ended })
        assertEquals("5", closed.first { it.ended }.id)
        val reopened = mergeConnections(closed, listOf(connection("5")))
        assertEquals(1, reopened.count { it.id == "5" })
        assertFalse(reopened.first().ended)
    }
    @Test fun tableProjectionSearchesLocalizedNamesAndSortsActualRates() {
        val rows = listOf(connection("slow").copy(downloadRate = 2.0), connection("fast").copy(downloadRate = 8.0),
            connection("unknown"), connection("closed").copy(ended = true))
        val filtered = connectionTableRows(rows, "浏览器", false, "download", true, mapOf("org.example.browser" to "浏览器"))
        assertEquals(listOf("fast", "slow", "unknown"), filtered.map { it.id })
        assertTrue(connectionTableRows(rows, "nomatch", false, "target", false, emptyMap()).isEmpty())
        assertEquals(listOf("closed"), connectionTableRows(rows, "", true, "start", true, emptyMap()).map { it.id })
    }
    @Test fun copyableLogsRedactAuthenticationWithoutRemovingDiagnosticContext() {
        val value = redactLog("GET https://user:pass@example.test/path?token=private&mode=probe failed")
        assertFalse(value.contains("user:pass")); assertFalse(value.contains("private"))
        assertTrue(value.contains("example.test/path")); assertTrue(value.contains("mode=probe"))
    }
    @Test fun ratesUseElapsedTimeAndNeverTreatTotalsAsSpeed() {
        val first = connection("one")
        val next = first.copy(upload = first.upload + 4096, download = first.download + 8192)
        val sampled = connectionRates(listOf(first), listOf(next), 2_000_000_000).single()
        assertEquals(2048.0, sampled.uploadRate!!, 0.01)
        assertEquals(4096.0, sampled.downloadRate!!, 0.01)
        assertEquals(next.download, sampled.download)
        assertNull(connectionRates(null, listOf(next), null).single().uploadRate)
        assertNull(connectionRates(listOf(first), listOf(next.copy(id = "new")), 1_000_000_000).single().downloadRate)
        assertNull(connectionRates(listOf(first), listOf(next.copy(start = "other-session")), 1_000_000_000).single().downloadRate)
        assertNull(connectionRates(listOf(first), listOf(next.copy(upload = 0)), 1_000_000_000).single().uploadRate)
        assertNull(connectionRates(listOf(first), listOf(next), 0).single().downloadRate)
        assertEquals(0.0, connectionRates(listOf(first), listOf(first), 1_000_000_000).single().uploadRate!!, 0.0)
        assertEquals(0.0, mergeConnections(listOf(sampled), emptyList()).single().uploadRate!!, 0.0)
    }
    @Test fun ruleAndOutboundPresentationMatchDesktop() {
        val base = connection("one")
        assertEquals("PROCESS-NAME,org.example.browser", base.matchedRule())
        assertEquals("Applications", base.copy(rule = "RuleSet", rulePayload = "Applications").matchedRule())
        assertEquals("RULE-SET", base.copy(rule = "RuleSet", rulePayload = "").matchedRule())
        for (type in listOf("Match", "FINAL")) assertEquals("MATCH", base.copy(rule = type).matchedRule())
        assertEquals("DOMAIN-SUFFIX,example.com", base.copy(rule = "DomainSuffix", rulePayload = "example.com").matchedRule())
        assertEquals("IP-CIDR,10.0.0.0/8", base.copy(rule = "IPCIDR", rulePayload = "10.0.0.0/8").matchedRule())
        assertEquals("inline", base.copy(rulePayload = "").matchedRule())
        assertEquals("—", base.copy(rule = "").matchedRule())
        assertEquals("FutureRule,org.example.browser", base.copy(rule = "FutureRule").matchedRule())
        assertEquals("Node", base.outbound)
        assertEquals("DIRECT", base.copy(chains = listOf("DIRECT", "Selector")).outbound)
    }
    @Test fun endpointsPreserveIpv6AndMissingPortWithoutInventingAUrlPath() {
        assertEquals("[2001:db8::1]:443", connectionEndpoint("2001:db8::1", "443"))
        assertEquals("[2001:db8::1]:443", connectionEndpoint("[2001:db8::1]", "443"))
        assertEquals("example.com", connectionEndpoint("example.com", ""))
        assertEquals("—", connectionEndpoint("", ""))
    }
}
