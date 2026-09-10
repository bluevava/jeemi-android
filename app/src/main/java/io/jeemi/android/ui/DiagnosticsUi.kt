package io.jeemi.android.ui

import androidx.compose.runtime.*
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import io.jeemi.android.R
import io.jeemi.android.ui.components.HelpContent

internal val ConnectionsHelp = HelpContent(R.string.connections, R.string.connections_purpose, R.string.connections_scenarios, R.string.connections_cautions)
internal val LogsHelp = HelpContent(R.string.logs, R.string.logs_purpose, R.string.logs_scenarios, R.string.logs_cautions)
internal val DnsHelp = HelpContent(R.string.dns_diagnostic, R.string.dns_purpose, R.string.dns_scenarios, R.string.dns_cautions)
internal val ConnectionRuleHelp = HelpContent(R.string.add_connection_rule, R.string.connection_rule_purpose, R.string.connection_rule_scenarios, R.string.connection_rule_cautions)

@Composable
internal fun ObserveDiagnostic(enabled: Boolean, key: Any?, observe: suspend () -> Unit) {
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val current by rememberUpdatedState(observe)
    LaunchedEffect(lifecycle, enabled, key) {
        if (enabled) lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) { current() }
    }
}
@Composable
internal fun diagnosticStatus(status: String): String = stringResource(when (status) {
    "live" -> R.string.feed_live; "stale" -> R.string.feed_stale; "paused" -> R.string.feed_paused
    "connecting" -> R.string.runtime_starting; else -> R.string.feed_offline
})

internal fun connectionBytes(value: Long): String = when {
    value >= 1024L * 1024 * 1024 -> "%.1f GiB".format(value / (1024.0 * 1024 * 1024))
    value >= 1024 * 1024 -> "%.1f MiB".format(value / (1024.0 * 1024))
    value >= 1024 -> "%.1f KiB".format(value / 1024.0)
    else -> "$value B"
}

internal fun connectionSpeed(bytesPerSecond: Double?): String {
    if (bytesPerSecond == null || !bytesPerSecond.isFinite()) return "—"
    val units = listOf("B/s", "KiB/s", "MiB/s", "GiB/s", "TiB/s")
    var value = bytesPerSecond.coerceAtLeast(0.0)
    var unit = 0
    while (value >= 1024 && unit < units.lastIndex) { value /= 1024; unit++ }
    return (if (unit == 0 || value >= 100) "%.0f" else "%.1f").format(value) + " " + units[unit]
}
