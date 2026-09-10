package io.jeemi.android.ui

import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import io.jeemi.android.R
import io.jeemi.android.data.BusinessIssue

internal fun businessIssueResource(code: String): Int = when (code) {
    "chain_busy" -> R.string.chain_busy
    "chain_cancelled" -> R.string.chain_cancelled
    "chain_candidate_invalid" -> R.string.chain_candidate_invalid
    "chain_core_version_required" -> R.string.chain_core_version_required
    "chain_diag_generated" -> R.string.chain_diag_generated
    "chain_diag_match_not_selector" -> R.string.chain_diag_match_not_selector
    "chain_diag_no_nodes_matched" -> R.string.chain_diag_no_nodes_matched
    "chain_diag_no_selectors_matched" -> R.string.chain_diag_no_selectors_matched
    "chain_diag_nodes_skipped" -> R.string.chain_diag_nodes_skipped
    "chain_diag_provider_skipped" -> R.string.chain_diag_provider_skipped
    "chain_dns_conflict" -> R.string.chain_dns_conflict
    "chain_duplicate_group" -> R.string.chain_duplicate_group
    "chain_duplicate_node_name" -> R.string.chain_duplicate_node_name
    "chain_fetch_failed" -> R.string.chain_fetch_failed
    "chain_generation_limit" -> R.string.chain_generation_limit
    "chain_group_filter_invalid" -> R.string.chain_group_filter_invalid
    "chain_group_in_use" -> R.string.chain_group_in_use
    "chain_group_missing" -> R.string.chain_group_missing
    "chain_identity_failed" -> R.string.chain_identity_failed
    "chain_input_limit" -> R.string.chain_input_limit
    "chain_invalid_configuration" -> R.string.chain_invalid_configuration
    "chain_invalid_group" -> R.string.chain_invalid_group
    "chain_invalid_library" -> R.string.chain_invalid_library
    "chain_invalid_nodes" -> R.string.chain_invalid_nodes
    "chain_invalid_selector" -> R.string.chain_invalid_selector
    "chain_invalid_source" -> R.string.chain_invalid_source
    "chain_name_conflict" -> R.string.chain_name_conflict
    "chain_no_nodes" -> R.string.chain_no_nodes
    "chain_node_missing" -> R.string.chain_node_missing
    "chain_read_failed" -> R.string.chain_read_failed
    "chain_revision_conflict" -> R.string.chain_revision_conflict
    "chain_single_node_required" -> R.string.chain_single_node_required
    "chain_source_missing" -> R.string.chain_source_missing
    "chain_unknown" -> R.string.chain_unknown
    "chain_write_failed" -> R.string.chain_write_failed
    "conversion_certificate_name_mismatch" -> R.string.conversion_certificate_name_mismatch
    "conversion_certificate_pin_mismatch" -> R.string.conversion_certificate_pin_mismatch
    "conversion_chain_dialer_replaced" -> R.string.conversion_chain_dialer_replaced
    "conversion_conflicting_alias" -> R.string.conversion_conflicting_alias
    "conversion_core_version_required" -> R.string.conversion_core_version_required
    "conversion_invalid_certificate" -> R.string.conversion_invalid_certificate
    "conversion_invalid_dns" -> R.string.conversion_invalid_dns
    "conversion_invalid_encoding" -> R.string.conversion_invalid_encoding
    "conversion_invalid_field" -> R.string.conversion_invalid_field
    "conversion_invalid_native" -> R.string.conversion_invalid_native
    "conversion_invalid_output" -> R.string.conversion_invalid_output
    "conversion_invalid_surge" -> R.string.conversion_invalid_surge
    "conversion_invalid_uri" -> R.string.conversion_invalid_uri
    "conversion_no_supported_nodes" -> R.string.conversion_no_supported_nodes
    "conversion_node_renamed" -> R.string.conversion_node_renamed
    "conversion_output_limit" -> R.string.conversion_output_limit
    "conversion_size_limit" -> R.string.conversion_size_limit
    "conversion_snell_userkey_unsupported" -> R.string.conversion_snell_userkey_unsupported
    "conversion_surge_dns_scope_preserved" -> R.string.conversion_surge_dns_scope_preserved
    "conversion_unknown" -> R.string.conversion_unknown
    "conversion_unsupported_certificate" -> R.string.conversion_unsupported_certificate
    "conversion_unsupported_dns_options" -> R.string.conversion_unsupported_dns_options
    "conversion_unsupported_format" -> R.string.conversion_unsupported_format
    "conversion_unsupported_host_mapping" -> R.string.conversion_unsupported_host_mapping
    "conversion_unsupported_node_fields" -> R.string.conversion_unsupported_node_fields
    "conversion_unsupported_protocol" -> R.string.conversion_unsupported_protocol
    "conversion_unsupported_protocol_version" -> R.string.conversion_unsupported_protocol_version
    "conversion_unsupported_tls_combination" -> R.string.conversion_unsupported_tls_combination
    "conversion_unsupported_transport" -> R.string.conversion_unsupported_transport
    "conversion_unsupported_udp_setting" -> R.string.conversion_unsupported_udp_setting
    "conversion_unused_certificate" -> R.string.conversion_unused_certificate
    else -> R.string.chain_unknown
}

@Composable
internal fun businessIssueText(issue: BusinessIssue): String {
    val message = stringResource(businessIssueResource(issue.code))
    return if (issue.line > 0) stringResource(R.string.conversion_position, issue.line, message) else message
}
