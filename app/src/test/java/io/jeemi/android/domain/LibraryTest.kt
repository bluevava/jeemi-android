package io.jeemi.android.domain

import org.junit.Assert.*
import org.junit.Test

class LibraryTest {
    private fun subscription(id: String) = Subscription(id, id, "original", "normalized", "mihomo", "", 0, emptyList())

    @Test fun removingActiveSubscriptionClearsSelectionWithoutPickingAnother() {
        val library = Library(listOf(subscription("a"), subscription("b")), "a")
        val removed = library.removing("a")
        assertNull(removed.selected)
        assertEquals(listOf("b"), removed.subscriptions.map { it.id })
    }

    @Test(expected = IllegalArgumentException::class)
    fun cannotSelectUnknownSubscription() { Library().selecting("unknown") }

    @Test fun desiredModeNeverChangesOriginalSubscription() {
        val library = Library(listOf(subscription("a")), "a")
        val changed = library.copy(preferences = Preferences(mode = ProxyMode.GLOBAL, overrideMode = true))
        assertEquals(library.selected, changed.selected)
    }

    @Test fun navigationAlwaysHasFourDestinationsAndRuntimePagesBelongToMore() {
        assertEquals(listOf(Destination.HOME, Destination.SUBSCRIPTIONS, Destination.CONFIG, Destination.MORE), Destination.entries)
        assertTrue(ToolPage.entries.containsAll(listOf(ToolPage.CONNECTIONS, ToolPage.LOGS)))
    }

    @Test fun deletingResourceClearsAllAssociationsInOneLibraryUpdate() {
        val config = LocalResource("c", "Config", ResourceKind.CONFIG, "{}")
        val rules = LocalResource("r", "Rules", ResourceKind.RULES, "[]")
        val library = Library(listOf(subscription("a"), subscription("b")), resources = listOf(config, rules))
            .associating("a", "c", listOf("r")).associating("b", "c", emptyList())
        val result = library.removingResource("c")
        assertTrue(result.subscriptions.all { it.handlerId == null })
        assertEquals(listOf("r"), result.subscriptions.first().resourceIds)
    }

    @Test(expected = IllegalArgumentException::class)
    fun handlerCannotPointToARuleSet() {
        Library(listOf(subscription("a")), resources = listOf(LocalResource("r", "R", ResourceKind.RULES, "[]")))
            .associating("a", "r", emptyList())
    }

}
