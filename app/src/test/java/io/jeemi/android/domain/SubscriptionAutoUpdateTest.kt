package io.jeemi.android.domain

import org.junit.Assert.*
import org.junit.Test

class SubscriptionAutoUpdateTest {
    private val item = Subscription("a", "Name", "original", "normalized", "clash", "", 0, emptyList(),
        sourceUrl = "https://example.invalid/sub", updatedAt = 1000)

    @Test fun fixedIntervalsAndLegacyDefaultsDoNotAcceptArbitraryInput() {
        assertEquals(60, item.updateIntervalMinutes)
        assertEquals(0, item.copy(sourceUrl = "").updateIntervalMinutes)
        assertEquals(listOf(10, 30, 60, 180, 720), subscriptionUpdateIntervals)
        for (value in subscriptionUpdateIntervals) assertEquals(value, subscriptionUpdateInterval(item.sourceUrl, value))
        for (value in listOf(-1, 0, 5, 15, 10080)) assertThrows(IllegalArgumentException::class.java) {
            subscriptionUpdateInterval(item.sourceUrl, value)
        }
    }

    @Test fun dueBoundaryAndClockChangesNeverCreateCatchUpBatches() {
        for (minutes in subscriptionUpdateIntervals) {
            val selected = item.copy(autoUpdateIntervalMinutes = minutes)
            val due = selected.updatedAt + minutes * 60_000L
            assertFalse(subscriptionUpdateDue(selected, null, due - 1))
            assertTrue(subscriptionUpdateDue(selected, null, due))
            assertTrue(subscriptionUpdateDue(selected, null, due + 10 * minutes * 60_000L))
            assertFalse(subscriptionUpdateDue(selected.copy(updatedAt = due), null, due))
            assertFalse(subscriptionUpdateDue(selected, null, 500))
        }
        assertFalse(subscriptionUpdateDue(item.copy(updatedAt = Long.MAX_VALUE), null, Long.MAX_VALUE))
        assertFalse(subscriptionUpdateDue(item.copy(updatedAt = 0), null, Long.MAX_VALUE))
        assertFalse(subscriptionUpdateDue(item.copy(sourceUrl = ""), null, Long.MAX_VALUE))
    }

    @Test fun failureWaitsAFullIntervalAndManualSuccessOrSourceChangeInvalidatesIt() {
        val attempt = SubscriptionUpdateAttempt(item.sourceUrl, 60, item.updatedAt, 4_000_000, SubscriptionUpdateError.DOWNLOAD)
        assertFalse(subscriptionUpdateDue(item, attempt, 7_599_999))
        assertTrue(subscriptionUpdateDue(item, attempt, 7_600_000))
        assertFalse(attempt.matches(item.copy(updatedAt = 4_000_001)))
        assertFalse(attempt.matches(item.copy(sourceUrl = "https://example.invalid/new")))
        assertFalse(attempt.matches(item.copy(autoUpdateIntervalMinutes = 10)))
        assertTrue(subscriptionUpdateDue(item.copy(autoUpdateIntervalMinutes = 10), attempt, 4_000_001))
    }

    @Test fun fetchedContentPreservesAllLocalIdentityAndChoices() {
        val previous = item.copy(description = "notes", icon = "🌐", iconImage = "cached", handlerId = "config",
            resourceIds = listOf("group", "rule"), chainGroupIds = listOf("chain"), chainRevision = 2,
            selections = mapOf("group" to "node"), fallbackMode = "selector", fallbackSelector = "group",
            disabledProviders = listOf("provider"), autoUpdateIntervalMinutes = 180, totalBytes = 100)
        val parsed = item.copy(id = "new", name = "Header must not rename", original = "new source", normalized = "new yaml",
            normalizationVersion = 3, normalizationReport = "report", nodes = listOf(ProxyNode("new", "ss")))
        val result = previous.withFetchedContent(parsed, mapOf("upload" to 1, "download" to 2), 5000)
        assertEquals(previous.copy(original = parsed.original, normalized = parsed.normalized, nodes = parsed.nodes,
            normalizationVersion = 3, normalizationReport = "report", updatedAt = 5000,
            uploadedBytes = 1, downloadedBytes = 2), result)
    }
}
