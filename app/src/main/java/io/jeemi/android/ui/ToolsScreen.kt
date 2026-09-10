package io.jeemi.android.ui

import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.jeemi.android.R
import io.jeemi.android.ui.components.FeatureHelp
import kotlinx.coroutines.awaitCancellation
import org.json.JSONObject

@Composable
fun ToolsScreen(model: JeemiViewModel, modifier: Modifier) {
    val diagnostic = model.diagnostics
    val feed by diagnostic.dns.collectAsStateWithLifecycle()
    var domain by rememberSaveable { mutableStateOf("") }
    var proxy by rememberSaveable { mutableStateOf(diagnostic.dnsPreference("proxy", "8.8.8.8")) }
    var direct by rememberSaveable { mutableStateOf(diagnostic.dnsPreference("direct", "223.5.5.5")) }
    var custom by rememberSaveable { mutableStateOf(diagnostic.dnsPreference("custom", "1.1.1.1")) }
    var customProxy by rememberSaveable { mutableStateOf(diagnostic.dnsPreference("customProxy", "false") == "true") }
    val runtime by model.runtime.collectAsStateWithLifecycle()
    LaunchedEffect(runtime) { if (feed.busy) diagnostic.cancelDns() }
    ObserveDiagnostic(true, diagnostic) { try { awaitCancellation() } finally { diagnostic.cancelDns() } }
    DisposableEffect(diagnostic) { onDispose { diagnostic.cancelDns() } }
    Column(modifier.verticalScroll(rememberScrollState()).imePadding().padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(stringResource(R.string.dns_diagnostic), Modifier.weight(1f), style = MaterialTheme.typography.titleMedium)
            FeatureHelp(DnsHelp)
        }
        OutlinedTextField(domain, { domain = it.take(8192) }, Modifier.fillMaxWidth(), singleLine = true,
            label = { Text(stringResource(R.string.dns_domain)) }, enabled = !feed.busy,
            trailingIcon = { IconButton(onClick = { if (feed.busy) diagnostic.cancelDns() else diagnostic.queryDns(domain, proxy, direct, custom, customProxy) }, enabled = feed.busy || domain.isNotBlank()) {
                Icon(if (feed.busy) Icons.Outlined.Close else Icons.Outlined.Search, stringResource(if (feed.busy) R.string.cancel else R.string.dns_query_action))
            } })
        ResolverField(R.string.dns_proxy, proxy, !feed.busy) { proxy = it }
        ResolverField(R.string.dns_direct, direct, !feed.busy) { direct = it }
        ResolverField(R.string.dns_custom, custom, !feed.busy) { custom = it }
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(stringResource(R.string.dns_custom_proxy), Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
            Switch(customProxy, { customProxy = it }, enabled = !feed.busy)
        }
        if (feed.busy) LinearProgressIndicator(Modifier.fillMaxWidth())
        if (feed.error.isNotEmpty()) Text(dnsError(feed.error), color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
        feed.result?.let { raw ->
            val result = remember(raw) { JSONObject(raw) }
            Text(result.getString("domain"), style = MaterialTheme.typography.titleSmall)
            Text(runCatching { java.time.Instant.parse(result.getString("queriedAt")).atZone(java.time.ZoneId.systemDefault()).toLocalDateTime().toString() }.getOrDefault(""),
                style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            listOf("proxy" to R.string.dns_proxy, "direct" to R.string.dns_direct, "custom" to R.string.dns_custom).forEach { (key, label) ->
                DnsResult(label, result.getJSONObject(key))
            }
        }
    }
}

private val resolverPresets = listOf("8.8.8.8", "223.5.5.5", "223.6.6.6", "119.29.29.29", "114.114.114.114", "1.1.1.1", "9.9.9.9",
    "https://dns.google/dns-query", "https://dns.alidns.com/dns-query", "https://cloudflare-dns.com/dns-query", "https://doh.pub/dns-query")
@Composable
private fun ResolverField(label: Int, value: String, enabled: Boolean, change: (String) -> Unit) {
    var open by remember { mutableStateOf(false) }
    Box {
        OutlinedTextField(value, { change(it.take(2048)) }, Modifier.fillMaxWidth(), label = { Text(stringResource(label)) }, singleLine = true, enabled = enabled,
            trailingIcon = { IconButton(onClick = { open = true }, enabled = enabled) { Icon(Icons.Outlined.ArrowDropDown, stringResource(R.string.dns_presets)) } })
        DropdownMenu(open, { open = false }) { resolverPresets.forEach { server ->
            DropdownMenuItem(text = { Text(server, style = MaterialTheme.typography.bodySmall) }, onClick = { change(server); open = false })
        } }
    }
}

@Composable
private fun DnsResult(label: Int, result: JSONObject) {
    Card(Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(stringResource(label), Modifier.weight(1f), style = MaterialTheme.typography.titleSmall)
                Text(result.optLong("durationMS").toString() + " ms", style = MaterialTheme.typography.labelMedium)
            }
            Text(stringResource(if (result.optString("route") == "proxy") R.string.dns_route_proxy else R.string.dns_route_direct), style = MaterialTheme.typography.labelSmall)
            if (result.optString("server").isNotBlank()) SelectionContainer { Text(result.getString("server"), style = MaterialTheme.typography.bodySmall) }
            if (result.optString("node").isNotBlank()) Text(result.optString("selector") + " → " + result.getString("node"), style = MaterialTheme.typography.bodySmall)
            if (result.optString("error").isNotBlank()) Text(dnsError(result.getString("error")), color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
            val records = result.optJSONArray("records")
            if (records != null) repeat(records.length()) { i ->
                val record = records.getJSONObject(i)
                Text(record.getString("type"), style = MaterialTheme.typography.labelMedium)
                val addresses = record.getJSONArray("addresses")
                val error = record.optString("error")
                if (error.isNotEmpty()) Text(dnsError(error), color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
                else if (addresses.length() == 0) Text(stringResource(R.string.dns_no_records), style = MaterialTheme.typography.bodySmall)
                else SelectionContainer { Text((0 until addresses.length()).joinToString("\n") { addresses.getString(it) },
                    fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.bodySmall) }
            }
        }
    }
}

@Composable
private fun dnsError(code: String): String = stringResource(when (code) {
    "invalid_domain" -> R.string.dns_invalid_domain; "invalid_server" -> R.string.dns_invalid_server
    "proxy_invalid", "direct_unavailable" -> R.string.dns_route_unavailable
    "timeout" -> R.string.dns_timeout; "cancelled" -> R.string.dns_cancelled
    "nxdomain" -> R.string.dns_nxdomain; "servfail" -> R.string.dns_servfail; "refused" -> R.string.dns_refused
    "invalid_response" -> R.string.dns_invalid_response; else -> R.string.dns_failed
})
