package io.jeemi.android.runtime

internal const val ACTIVE_CONNECTION_LIMIT = 2000
internal const val ENDED_CONNECTION_LIMIT = 100

internal data class ConnectionSnapshot(
    val rows: List<ConnectionRecord>,
    val activeCount: Int,
    // Only IDs already held by the UI are tracked beyond the display limit.
    // Keeping every ID from the core would create another unbounded cache.
    val presentTrackedIds: Set<String>,
)

internal fun mergeConnections(
    previous: List<ConnectionRecord>,
    active: List<ConnectionRecord>,
    presentIds: Set<String> = active.mapTo(HashSet()) { it.id },
): List<ConnectionRecord> {
    val result = ArrayList<ConnectionRecord>(active.size + ENDED_CONNECTION_LIMIT)
    result.addAll(active)
    var remaining = ENDED_CONNECTION_LIMIT
    // Newly observed endings precede older history. App/target duplicates are
    // different connections and must not replace one another.
    for (wasEnded in listOf(false, true)) {
        for (row in previous) {
            if (remaining == 0) break
            if (row.ended == wasEnded && row.id !in presentIds) {
                result.add(if (row.ended) row else row.copy(ended = true, uploadRate = 0.0, downloadRate = 0.0))
                remaining--
            }
        }
    }
    return result
}

internal fun markClosedConnections(previous: List<ConnectionRecord>, ids: Set<String>): List<ConnectionRecord> =
    mergeConnections(previous, previous.filter { !it.ended && it.id !in ids })
