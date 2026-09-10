package io.jeemi.android.ui
import io.jeemi.android.R
import io.jeemi.android.ui.components.HelpContent
internal val ExtraFieldHelp = mapOf(
    "/bind-address" to HelpContent(R.string.android_field_bind_address_title, R.string.android_field_bind_address_purpose, R.string.android_field_bind_address_scenarios, R.string.android_field_bind_address_cautions),
    "/find-process-mode" to HelpContent(R.string.android_field_find_process_title, R.string.android_field_find_process_purpose, R.string.android_field_find_process_scenarios, R.string.android_field_find_process_cautions),
    "/port" to HelpContent(R.string.android_field_http_port_title, R.string.android_field_http_port_purpose, R.string.android_field_http_port_scenarios, R.string.android_field_http_port_cautions),
    "/socks-port" to HelpContent(R.string.android_field_socks_port_title, R.string.android_field_socks_port_purpose, R.string.android_field_socks_port_scenarios, R.string.android_field_socks_port_cautions),
    "/proxies/*/udp" to HelpContent(R.string.android_field_proxy_udp_title, R.string.android_field_proxy_udp_purpose, R.string.android_field_proxy_udp_scenarios, R.string.android_field_proxy_udp_cautions),
    "/proxies/*/tfo" to HelpContent(R.string.android_field_proxy_tfo_title, R.string.android_field_proxy_tfo_purpose, R.string.android_field_proxy_tfo_scenarios, R.string.android_field_proxy_tfo_cautions),
    "/proxies/*/mptcp" to HelpContent(R.string.android_field_proxy_mptcp_title, R.string.android_field_proxy_mptcp_purpose, R.string.android_field_proxy_mptcp_scenarios, R.string.android_field_proxy_mptcp_cautions),
    "/proxies/*/client-fingerprint" to HelpContent(R.string.android_field_proxy_fingerprint_title, R.string.android_field_proxy_fingerprint_purpose, R.string.android_field_proxy_fingerprint_scenarios, R.string.android_field_proxy_fingerprint_cautions),
    "/proxies/*/dialer-proxy" to HelpContent(R.string.android_field_proxy_dialer_title, R.string.android_field_proxy_dialer_purpose, R.string.android_field_proxy_dialer_scenarios, R.string.android_field_proxy_dialer_cautions),
    "/proxies/*/ip-version" to HelpContent(R.string.android_field_proxy_ip_version_title, R.string.android_field_proxy_ip_version_purpose, R.string.android_field_proxy_ip_version_scenarios, R.string.android_field_proxy_ip_version_cautions),
    "/mode" to ModeHelp,
    "/dns/fake-ip-filter-mode" to RuntimeHelp,
    "/geodata-mode" to GeoHelp,
    "/geodata-loader" to GeoHelp,
    "/geo-auto-update" to GeoHelp,
    "/geo-update-interval" to GeoHelp,
    "/geox-url" to GeoHelp,
)

