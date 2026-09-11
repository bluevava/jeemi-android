package io.jeemi.android.ui

import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import io.jeemi.android.R
import io.jeemi.android.domain.ResourceKind
import io.jeemi.android.ui.components.HelpContent

internal val ConnectionHelp = HelpContent(R.string.status_control, R.string.help_connection_purpose, R.string.help_connection_scenarios, R.string.help_connection_cautions)
internal val IconHelp = HelpContent(R.string.subscription_icon, R.string.icon_purpose, R.string.icon_scenarios, R.string.icon_cautions)
internal val ScanHelp = HelpContent(R.string.scan_subscription_qr, R.string.scan_purpose, R.string.scan_scenarios, R.string.scan_cautions)
internal val PermissionsHelp = HelpContent(R.string.permissions, R.string.permissions_purpose, R.string.permissions_scenarios, R.string.permissions_cautions)
internal val CoreHelp = HelpContent(R.string.core_management, R.string.help_core_purpose, R.string.help_core_scenarios, R.string.help_core_cautions)
internal val RuntimeHelp = HelpContent(R.string.runtime_preferences, R.string.help_runtime_purpose, R.string.help_runtime_scenarios, R.string.help_runtime_cautions)
internal val NodesHelp = HelpContent(R.string.node_list, R.string.help_nodes_purpose, R.string.help_nodes_scenarios, R.string.help_nodes_cautions)
internal val ExternalUIHelp = HelpContent(R.string.external_ui, R.string.help_external_ui_purpose, R.string.help_external_ui_scenarios, R.string.help_external_ui_cautions)
internal val ScriptSourceHelp = HelpContent(R.string.scripts, R.string.help_script_source_purpose, R.string.help_script_source_scenarios, R.string.help_script_source_cautions)
internal val NestedSelectorsHelp = HelpContent(R.string.nested_selectors, R.string.help_nested_purpose, R.string.help_nested_scenarios, R.string.help_nested_cautions)
internal val ProvidersHelp = HelpContent(R.string.rule_providers, R.string.help_providers_purpose, R.string.help_providers_scenarios, R.string.help_providers_cautions)
internal val AssociationHelp = HelpContent(R.string.associations, R.string.help_association_purpose, R.string.help_association_scenarios, R.string.help_association_cautions)
internal val PreviewHelp = HelpContent(R.string.configuration_preview, R.string.help_preview_purpose, R.string.help_preview_scenarios, R.string.help_preview_cautions)
internal val DocumentHelp = HelpContent(R.string.original_document, R.string.help_document_purpose, R.string.help_document_scenarios, R.string.help_document_cautions)
internal val LocalConfigHelp = HelpContent(R.string.local_configuration, R.string.help_localconfig_purpose, R.string.help_localconfig_scenarios, R.string.help_localconfig_cautions)
internal val ScriptHelp = HelpContent(R.string.scripts, R.string.help_script_purpose, R.string.help_script_scenarios, R.string.help_script_cautions)
internal val GroupsHelp = HelpContent(R.string.groups, R.string.help_groups_purpose, R.string.help_groups_scenarios, R.string.help_groups_cautions)
internal val RulesHelp = HelpContent(R.string.rule_sets, R.string.help_rules_purpose, R.string.help_rules_scenarios, R.string.help_rules_cautions)
internal val FieldsHelp = HelpContent(R.string.add_field, R.string.help_fields_purpose, R.string.help_fields_scenarios, R.string.help_fields_cautions)
internal val ToolsHelp = HelpContent(R.string.tools, R.string.help_tools_purpose, R.string.help_tools_scenarios, R.string.help_tools_cautions)
internal val GeoHelp = HelpContent(R.string.geo_management, R.string.help_geo_purpose, R.string.help_geo_scenarios, R.string.help_geo_cautions)
internal val FallbackHelp = HelpContent(R.string.fallback_override, R.string.help_fallback_purpose, R.string.help_fallback_scenarios, R.string.help_fallback_cautions)
internal val RoutingHelp = HelpContent(R.string.local_routing, R.string.help_routing_purpose, R.string.help_routing_scenarios, R.string.help_routing_cautions)
internal val DisplayHelp = HelpContent(R.string.subscription_settings, R.string.help_display_purpose, R.string.help_display_scenarios, R.string.help_display_cautions)
internal val AppearanceHelp = HelpContent(R.string.settings, R.string.help_appearance_purpose, R.string.help_appearance_scenarios, R.string.help_appearance_cautions)
internal val PackageHelp = HelpContent(R.string.resource_package, R.string.help_package_purpose, R.string.help_package_scenarios, R.string.help_package_cautions)

internal val ResourceKind.help: HelpContent get() = when (this) {
    ResourceKind.CONFIG -> LocalConfigHelp
    ResourceKind.SCRIPT -> ScriptHelp
    ResourceKind.GROUPS -> GroupsHelp
    ResourceKind.RULES -> RulesHelp
}

@Composable
internal fun enumLabel(value: String): String = when (value) {
    "silent" -> stringResource(R.string.log_silent)
    "error" -> stringResource(R.string.log_error)
    "warning" -> stringResource(R.string.log_warning)
    "info" -> stringResource(R.string.log_info)
    "debug" -> stringResource(R.string.log_debug)
    "fake-ip" -> stringResource(R.string.dns_fake_ip)
    "redir-host" -> stringResource(R.string.dns_redir_host)
    "lru" -> stringResource(R.string.cache_lru)
    "arc" -> stringResource(R.string.cache_arc)
    "strict" -> stringResource(R.string.find_strict)
    "always" -> stringResource(R.string.find_always)
    "off" -> stringResource(R.string.find_off)
    else -> value
}
