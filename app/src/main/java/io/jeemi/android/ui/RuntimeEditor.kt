@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
package io.jeemi.android.ui
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import io.jeemi.android.R
import io.jeemi.android.ui.components.*
import org.json.JSONObject
import mobile.Mobile

private data class RuntimeField(val key: String, val path: String, val label: Int, val type: String,
    val options: List<String> = emptyList(), val enable: String? = null, val merge: String? = null) {
    val fragment get() = if (key == "lanBypassRules") "rules.lan-bypass" else path.removePrefix("/").replace('/', '.')
    fun text(values: JSONObject): String = if (type == "list") values.optJSONArray(key)?.let { array ->
        (0 until array.length()).joinToString("\n") { "- " + JSONObject.quote(array.getString(it)) }
    }.orEmpty() else values.optString(key)
}
private val RuntimeFields = mapOf(
    "basic" to listOf(
        RuntimeField("logLevel", "/log-level", R.string.log_level, "enum", listOf("silent", "error", "warning", "info", "debug")),
        RuntimeField("findProcessMode", "/find-process-mode", R.string.find_process_mode, "enum", listOf("strict", "always", "off")),
        RuntimeField("listenerType", "/mixed-port", R.string.listener_type, "enum", listOf("mixed", "http", "socks")),
        RuntimeField("listenPort", "/mixed-port", R.string.listen_port, "number"),
        RuntimeField("allowLan", "/allow-lan", R.string.allow_lan, "boolean"),
        RuntimeField("ipv6", "/ipv6", R.string.ipv6, "boolean"),
        RuntimeField("tunRouteExcludeAddress", "/tun/route-exclude-address", R.string.runtime_route_exclude, "list",
            enable = "tunRouteExcludeAddressEnabled", merge = "tunRouteExcludeAddressMerge"),
    ),
    "dns" to listOf(
        RuntimeField("dnsEnabled", "/dns/enable", R.string.dns_enabled, "boolean"),
        RuntimeField("dnsListen", "/dns/listen", R.string.dns_listen, "string"),
        RuntimeField("dnsIpv6", "/dns/ipv6", R.string.dns_ipv6, "boolean"),
        RuntimeField("dnsEnhancedMode", "/dns/enhanced-mode", R.string.dns_mode, "enum", listOf("fake-ip", "redir-host")),
        RuntimeField("dnsFakeIpRange", "/dns/fake-ip-range", R.string.fake_ip_range, "string"),
        RuntimeField("dnsFakeIpRange6", "/dns/fake-ip-range6", R.string.fake_ip_range6, "string"),
    ),
    "resources" to listOf(
        RuntimeField("dnsNameservers", "/dns/nameserver", R.string.nameserver, "list", enable = "dnsNameserverEnabled", merge = "dnsNameserverMerge"),
        RuntimeField("dnsFakeIpFilter", "/dns/fake-ip-filter", R.string.fake_ip_filter, "list", enable = "dnsFakeIpFilterEnabled", merge = "dnsFakeIpFilterMerge"),
        RuntimeField("dnsProxyServerNameservers", "/dns/proxy-server-nameserver", R.string.proxy_dns, "list", enable = "dnsProxyServerNameserverEnabled", merge = "dnsProxyServerNameserverMerge"),
        RuntimeField("dnsNameserverPolicyYaml", "/dns/nameserver-policy", R.string.nameserver_policy, "yaml", enable = "dnsNameserverPolicyEnabled", merge = "dnsNameserverPolicyMerge"),
        RuntimeField("dnsProxyServerNameserverPolicyYaml", "/dns/proxy-server-nameserver-policy", R.string.proxy_nameserver_policy, "yaml", enable = "dnsProxyServerNameserverPolicyEnabled", merge = "dnsProxyServerNameserverPolicyMerge"),
        RuntimeField("hostsYaml", "/hosts", R.string.hosts, "yaml", enable = "dnsUseHosts", merge = "hostsMerge"),
    ),
    "lan" to listOf(RuntimeField("lanBypassRules", "/rules", R.string.lan_rules, "list")),
)

private val ResolverPresets = listOf("114.114.114.114", "223.5.5.5", "https://dns.alidns.com/dns-query",
    "https://doh.pub/dns-query", "https://1.1.1.1/dns-query", "https://dns.google/dns-query")

private fun RuntimeField.help(): HelpContent {
    val policy = when {
        enable == null && type != "list" -> R.string.runtime_forced_help
        key == "lanBypassRules" -> R.string.runtime_lan_help
        type == "yaml" -> R.string.runtime_mapping_help
        key == "dnsNameservers" -> R.string.runtime_nameserver_help
        else -> R.string.runtime_list_help
    }
    val base = if (key == "tunRouteExcludeAddress") HelpContent(R.string.runtime_route_exclude,
        R.string.runtime_route_purpose, R.string.runtime_route_scenarios, R.string.runtime_route_cautions)
        else if (key == "lanBypassRules") RuntimeHelp.copy(title = label) else fieldHelp(path)
    return base.copy(extraCautions = policy)
}

@Composable
internal fun RuntimeEditor(section: String, state: AppState, model: JeemiViewModel, dismiss: () -> Unit) {
    val draft by model.runtimeDraft.collectAsState()
    val texts by model.runtimeTextDraft.collectAsState()
    val issue by model.runtimeIssue.collectAsState()
    val current = JSONObject(draft ?: state.library.preferences.runtimeJson)
    val defaults = remember { JSONObject(Mobile.runtimeDefaults()) }
    fun close() { model.closeRuntimeEdit(); dismiss() }
    fun restore(field: RuntimeField) {
        model.updateRuntimeValue(field.key, defaults.get(field.key))
        model.updateRuntimeText(field.fragment, field.text(defaults))
    }
    EditorDialog(stringResource(when(section) {
        "basic" -> R.string.runtime_basics; "dns" -> R.string.dns_settings; "lan" -> R.string.lan_rules; else -> R.string.dns_resources
    }), state.busy, ::close, help = RuntimeHelp, footer = {
        Button(onClick = { model.saveRuntimePreferences { close() } }, enabled = !state.busy,
            modifier = Modifier.testTag("runtime-save"), shape = MaterialTheme.shapes.small) { Text(stringResource(R.string.save)) }
    }) {
        LazyColumn(Modifier.testTag("runtime-fields"), verticalArrangement = Arrangement.spacedBy(12.dp), contentPadding = PaddingValues(bottom = 24.dp)) {
            items(RuntimeFields.getValue(section), key = { it.key }) { field ->
                val label = stringResource(field.label)
                val injectionLabel = stringResource(R.string.runtime_enable_resource, label)
                val enabled = field.enable?.let { current.optBoolean(it) } ?: true
                JeemiCard(Modifier.fillMaxWidth().testTag("runtime-" + field.key)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(label, Modifier.weight(1f), style = MaterialTheme.typography.titleSmall)
                        FeatureHelp(field.help())
                        if (field.enable != null || field.type == "boolean") {
                            val key = field.enable ?: field.key
                            Switch(checked = current.optBoolean(key), onCheckedChange = { model.updateRuntimeValue(key, it) },
                                enabled = !state.busy, colors = SwitchDefaults.colors(uncheckedThumbColor = MaterialTheme.colorScheme.onSurfaceVariant),
                                modifier = Modifier.sizeIn(minWidth = 48.dp, minHeight = 48.dp).testTag("runtime-switch-" + key)
                                    .semantics { contentDescription = if (field.enable != null) injectionLabel else label })
                        }
                    }
                    if (enabled && field.type != "boolean") {
                        field.merge?.let { key ->
                            val strategyLabel = stringResource(R.string.composition_strategy)
                            SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth().semantics { contentDescription = strategyLabel }) {
                                listOf("append" to R.string.runtime_append_unique, "override" to R.string.strategy_override).forEachIndexed { index, choice ->
                                    SegmentedButton(selected = current.optString(key) == choice.first,
                                        onClick = { model.updateRuntimeValue(key, choice.first) }, enabled = !state.busy,
                                        modifier = Modifier.testTag("runtime-" + key + "-" + choice.first),
                                        shape = SegmentedButtonDefaults.itemShape(index, 2)) { Text(stringResource(choice.second)) }
                                }
                            }
                        }
                        val yaml = field.type in listOf("list", "yaml")
                        val value = if (yaml) texts[field.fragment] ?: field.text(current) else current.optString(field.key)
                        if (field.type == "enum") {
                            ChoiceField(stringResource(R.string.field_value), value, field.options.map { it to enumLabel(it) }, !state.busy) {
                                model.updateRuntimeValue(field.key, it)
                            }
                        } else {
                            OutlinedTextField(value, { text ->
                                if (yaml) model.updateRuntimeText(field.fragment, text)
                                else model.updateRuntimeValue(field.key, if (field.type == "number") text.toIntOrNull() ?: text else text)
                            }, Modifier.fillMaxWidth().testTag("runtime-value-" + field.key), enabled = !state.busy,
                                label = { Text(if (yaml) field.fragment else stringResource(R.string.field_value)) },
                                minLines = if (yaml) 4 else 1, maxLines = if (yaml) 10 else 1, singleLine = !yaml,
                                textStyle = MaterialTheme.typography.bodyMedium.copy(fontFamily = if (yaml) FontFamily.Monospace else FontFamily.Default),
                                keyboardOptions = KeyboardOptions(keyboardType = if (field.type == "number") KeyboardType.Number else KeyboardType.Text))
                        }
                        if (yaml) {
                            val presets = when (field.key) {
                                "dnsNameservers", "dnsProxyServerNameservers" -> ResolverPresets
                                "dnsFakeIpFilter" -> defaults.getJSONArray(field.key).let { array -> (0 until array.length()).map { array.getString(it) } }
                                else -> emptyList()
                            }
                            Column {
                                if (presets.isNotEmpty()) {
                                    var open by remember { mutableStateOf(false) }
                                    TextButton(onClick = { open = true }, enabled = !state.busy, shape = MaterialTheme.shapes.small) { Text(stringResource(R.string.runtime_add_preset)) }
                                    DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
                                        presets.forEach { preset -> DropdownMenuItem(text = { Text(preset) }, onClick = {
                                            open = false; model.addRuntimePreset(field.fragment, value, preset)
                                        }) }
                                    }
                                }
                                TextButton(onClick = { restore(field) }, enabled = !state.busy, shape = MaterialTheme.shapes.small) { Text(stringResource(R.string.restore_defaults)) }
                            }
                        }
                    }
                }
            }
        }
    }
    issue?.let { invalid ->
        val field = RuntimeFields.values.flatten().firstOrNull { it.fragment == invalid.field }
        AlertDialog(onDismissRequest = { model.runtimeIssue.value = null },
            title = { Text(stringResource(R.string.runtime_yaml_error)) },
            text = { Text(stringResource(if (invalid.line > 0) R.string.runtime_yaml_error_line else R.string.runtime_yaml_error_field,
                field?.let { stringResource(it.label) } ?: invalid.field, invalid.line)) },
            confirmButton = { TextButton(onClick = { model.runtimeIssue.value = null }, shape = MaterialTheme.shapes.small) { Text(stringResource(R.string.runtime_continue_editing)) } },
            dismissButton = { if (field != null) TextButton(onClick = {
                model.updateRuntimeText(field.fragment, field.text(JSONObject(state.library.preferences.runtimeJson)))
                model.runtimeIssue.value = null
            }, shape = MaterialTheme.shapes.small) { Text(stringResource(R.string.runtime_revert_field)) } })
    }
}
