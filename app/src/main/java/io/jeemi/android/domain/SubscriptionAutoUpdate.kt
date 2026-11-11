package io.jeemi.android.domain

val subscriptionUpdateIntervals = listOf(10, 30, 60, 180, 720)

fun subscriptionUpdateInterval(sourceUrl: String, minutes: Int? = null): Int {
    if (sourceUrl.isBlank()) return 0
    return (minutes ?: 60).also { require(it in subscriptionUpdateIntervals) }
}

enum class SubscriptionUpdateError { DOWNLOAD, VALIDATION }

val Subscription.updateIntervalMinutes: Int get() = subscriptionUpdateInterval(sourceUrl, autoUpdateIntervalMinutes)

data class SubscriptionUpdateAttempt(val sourceUrl: String, val intervalMinutes: Int,
    val successfulAt: Long, val attemptedAt: Long, val error: SubscriptionUpdateError) {
    fun matches(item: Subscription) = sourceUrl == item.sourceUrl &&
        intervalMinutes == item.updateIntervalMinutes && successfulAt == item.updatedAt
}

fun subscriptionUpdateDue(item: Subscription, attempt: SubscriptionUpdateAttempt?, now: Long): Boolean {
    if (item.sourceUrl.isBlank() || item.updatedAt <= 0) return false
    val last = maxOf(item.updatedAt, attempt?.takeIf { it.matches(item) }?.attemptedAt ?: 0)
    return now >= last && now - last >= item.updateIntervalMinutes * 60_000L
}

/** Keep identity, local choices, icon and associations; only replace fetched data. */
fun Subscription.withFetchedContent(parsed: Subscription, usage: Map<String, Long>, now: Long): Subscription = copy(
    original = parsed.original, normalized = parsed.normalized, format = parsed.format,
    requiredCore = parsed.requiredCore, skippedNodes = parsed.skippedNodes, nodes = parsed.nodes,
    normalizationVersion = parsed.normalizationVersion, normalizationReport = parsed.normalizationReport,
    updatedAt = now, uploadedBytes = usage["upload"] ?: uploadedBytes,
    downloadedBytes = usage["download"] ?: downloadedBytes, totalBytes = usage["total"] ?: totalBytes,
    expiresAt = usage["expire"] ?: expiresAt,
)
