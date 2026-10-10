package io.jeemi.android.ui

import io.jeemi.android.R
import io.jeemi.android.domain.ConfigField
import io.jeemi.android.ui.components.HelpContent

private val TunFieldHelp = HelpContent(R.string.field_tree_tun_title, R.string.field_tree_tun_purpose,
    R.string.field_tree_tun_scenarios, R.string.field_tree_tun_cautions)
private val ControllerFieldHelp = HelpContent(R.string.field_tree_controller_title, R.string.field_tree_controller_purpose,
    R.string.field_tree_controller_scenarios, R.string.field_tree_controller_cautions)
private val ExternalPanelFieldHelp = HelpContent(R.string.field_tree_ui_title, R.string.field_tree_ui_purpose,
    R.string.field_tree_ui_scenarios, R.string.field_tree_ui_cautions)
private val RoutingFieldHelp = HelpContent(R.string.field_tree_rules_title, R.string.field_tree_rules_purpose,
    R.string.field_tree_rules_scenarios, R.string.field_tree_rules_cautions)
private val ProxyFieldsHelp = HelpContent(R.string.field_tree_proxy_title, R.string.field_tree_proxy_purpose,
    R.string.field_tree_proxy_scenarios, R.string.field_tree_proxy_cautions)
private val SniffFieldsHelp = HelpContent(R.string.field_tree_sniff_title, R.string.field_tree_sniff_purpose,
    R.string.field_tree_sniff_scenarios, R.string.field_tree_sniff_cautions)

internal val FieldTreeBranchHelp = mapOf(
    "/dns" to HelpContent(R.string.field_tree_dns_title, R.string.field_tree_dns_purpose,
        R.string.field_tree_dns_scenarios, R.string.field_tree_dns_cautions),
    "/sniffer" to HelpContent(R.string.field_help_sniffer_enable_title, R.string.field_help_sniffer_enable_purpose,
        R.string.field_help_sniffer_enable_scenarios, R.string.field_tree_branch_cautions),
    "/sniffer/sniff" to SniffFieldsHelp,
    "/sniffer/sniff/HTTP" to SniffFieldsHelp, "/sniffer/sniff/TLS" to SniffFieldsHelp, "/sniffer/sniff/QUIC" to SniffFieldsHelp,
    "/tun" to TunFieldHelp, "/proxies" to ProxyFieldsHelp, "/proxies/*" to ProxyFieldsHelp,
)

internal val LockedFieldHelp = buildMap {
    listOf("enable", "stack", "auto-route", "auto-detect-interface", "device", "route-exclude-address").forEach { put("/tun/$it", TunFieldHelp) }
    listOf("external-controller", "external-controller-cors", "external-controller-unix", "external-controller-pipe",
        "external-controller-tls", "external-controller-routing-mark", "external-doh-server").forEach { put("/$it", ControllerFieldHelp) }
    put("/secret", HelpContent(R.string.field_tree_secret_title, R.string.field_tree_secret_purpose,
        R.string.field_tree_secret_scenarios, R.string.field_tree_secret_cautions))
    listOf("external-ui", "external-ui-name", "external-ui-url").forEach { put("/$it", ExternalPanelFieldHelp) }
    listOf("rules", "proxy-groups", "rule-providers").forEach { put("/$it", RoutingFieldHelp) }
    put("/proxies/*/interface-name", HelpContent(R.string.field_help_interface_name_title, R.string.field_help_interface_name_purpose,
        R.string.field_help_interface_name_scenarios, R.string.field_owner_android))
    put("/proxies/*/routing-mark", HelpContent(R.string.field_help_routing_mark_title, R.string.field_help_routing_mark_purpose,
        R.string.field_help_routing_mark_scenarios, R.string.field_owner_android))
}

internal fun localFieldHelp(path: String, field: ConfigField?): HelpContent {
    val base = fieldHelp(path)
    val note = when {
        path in setOf("/rules", "/proxy-groups", "/rule-providers") -> R.string.field_owner_routing
        field?.locked == true -> if (field.hidden) R.string.field_owner_android else R.string.field_owner_home
        field != null -> R.string.field_tree_edit_cautions
        else -> return base
    }
    return if (base.cautions == note) base else base.copy(extraCautions = note)
}
