package io.jeemi.android.runtime

// Ported from desktop connections/connectionPresentation.ts. The API returns
// RuleType.String() and its chain starts with the actual outbound leaf.
private val connectionRuleTypes = listOf(
    "DOMAIN", "DOMAIN-SUFFIX", "DOMAIN-KEYWORD", "DOMAIN-REGEX", "DOMAIN-WILDCARD", "GEOSITE", "GEOIP", "SRC-GEOIP",
    "IP-ASN", "SRC-IP-ASN", "IP-CIDR", "SRC-IP-CIDR", "IP-SUFFIX", "SRC-IP-SUFFIX", "SRC-PORT", "DST-PORT", "IN-PORT",
    "DSCP", "IN-USER", "IN-NAME", "IN-TYPE", "PROCESS-NAME", "PROCESS-PATH", "PROCESS-NAME-REGEX", "PROCESS-PATH-REGEX",
    "PROCESS-NAME-WILDCARD", "PROCESS-PATH-WILDCARD", "REMATCH-NAME", "NETWORK", "UID", "AND", "OR", "NOT",
).associateBy { it.replace("-", "") } + ("SUBRULES" to "SUB-RULE")

internal fun ConnectionRecord.matchedRule(): String {
    val kind = rule.trim()
    val key = kind.replace("-", "").uppercase(java.util.Locale.ROOT)
    return when {
        key == "MATCH" || key == "FINAL" -> "MATCH"
        key == "RULESET" -> rulePayload.ifBlank { "RULE-SET" }
        kind.isBlank() -> "—"
        rulePayload.isBlank() -> "inline"
        else -> (connectionRuleTypes[key] ?: kind) + "," + rulePayload
    }
}

internal fun connectionEndpoint(host: String, port: String): String {
    val address = if (host.contains(':') && !host.startsWith('[')) "[$host]" else host.ifBlank { "—" }
    return if (port.isBlank()) address else "$address:$port"
}

// A rate requires two successful snapshots from the same observation/session.
// Counters, a new connection or a resumed page must not be presented as speed.
internal fun connectionRates(previous: List<ConnectionRecord>?, current: List<ConnectionRecord>, elapsedNanos: Long?): List<ConnectionRecord> {
    val before = previous?.associateBy { it.id }.orEmpty()
    val seconds = elapsedNanos?.takeIf { it > 0 }?.div(1_000_000_000.0)
    return current.map { row ->
        val old = before[row.id]?.takeIf { !it.ended && it.start == row.start }
        fun rate(now: Long, last: Long?): Double? = if (seconds == null || last == null || now < last || last < 0) null else (now - last) / seconds
        row.copy(uploadRate = rate(row.upload, old?.upload), downloadRate = rate(row.download, old?.download))
    }
}

internal fun connectionTableRows(all: List<ConnectionRecord>, query: String, ended: Boolean, sort: String,
    descending: Boolean, appNames: Map<String, String>, checkActive: () -> Unit = {}): List<ConnectionRecord> {
    val text = query.trim()
    val rows = all.filter {
        checkActive()
        it.ended == ended && (it.matches(text) || text.isNotEmpty() && appNames[it.process]?.contains(text, true) == true)
    }
    if (sort == "upload" || sort == "download") {
        val comparator = compareBy<ConnectionRecord> { if (sort == "upload") it.uploadRate ?: -1.0 else it.downloadRate ?: -1.0 }
        return rows.sortedWith(if (descending) comparator.reversed() else comparator)
    }
    // Compute text keys once per record, not on every sort comparison.
    val keyed = rows.map { row ->
        checkActive()
        val key = when (sort) {
            "target" -> row.target.lowercase(java.util.Locale.ROOT)
            "process" -> (appNames[row.process] ?: row.process).lowercase(java.util.Locale.ROOT)
            "rule" -> row.matchedRule()
            "outbound" -> row.outbound
            else -> row.start
        }
        key to row
    }
    val comparator = compareBy<Pair<String, ConnectionRecord>> { it.first }
    return keyed.sortedWith(if (descending) comparator.reversed() else comparator).map { it.second }
}
