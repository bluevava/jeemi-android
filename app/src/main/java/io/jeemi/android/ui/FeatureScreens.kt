@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
package io.jeemi.android.ui
import android.widget.Toast
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowForward
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.jeemi.android.R
import io.jeemi.android.domain.*
import io.jeemi.android.ui.components.*

@Composable
fun HomeScreen(state: AppState, model: JeemiViewModel, modifier: Modifier, authorizationPending: Boolean,
    startVpn: () -> Unit, openSubscriptions: (import: Boolean) -> Unit) {
    val dashboardLifecycle = androidx.lifecycle.compose.LocalLifecycleOwner.current.lifecycle
    DisposableEffect(dashboardLifecycle, model) {
        val observer = androidx.lifecycle.LifecycleEventObserver { _, event ->
            if (event == androidx.lifecycle.Lifecycle.Event.ON_STOP) model.cancelDashboardDownload()
        }
        dashboardLifecycle.addObserver(observer)
        onDispose { dashboardLifecycle.removeObserver(observer); model.cancelDashboardDownload() }
    }
    var editor by rememberSaveable { mutableStateOf<String?>(null) }
    val runtime by model.runtime.collectAsStateWithLifecycle()
    val live by model.live.collectAsStateWithLifecycle()
    val active = runtime is RuntimeState.Running || runtime == RuntimeState.Starting || runtime == RuntimeState.Restarting
    val selected = state.library.selected
    val subscriptionAction = stringResource(if (selected == null) R.string.add_subscription else R.string.manage_subscriptions)
    val switchAction = stringResource(if (active) R.string.vpn_stop else R.string.vpn_start)
    val modeLabel = stringResource(R.string.proxy_mode)
    val externalLabel = stringResource(R.string.external_ui)
    val uriHandler = LocalUriHandler.current
    val context = LocalContext.current
    val linkError = stringResource(R.string.about_link_error)
    val openAboutLink: (String) -> Unit = { uri ->
        try {
            uriHandler.openUri(uri)
        } catch (_: IllegalArgumentException) {
            Toast.makeText(context, linkError, Toast.LENGTH_SHORT).show()
        }
    }
    LazyColumn(modifier, contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
        item {
            JeemiCard(Modifier.fillMaxWidth()) {
                Row(Modifier.fillMaxWidth().heightIn(min = 48.dp).clip(MaterialTheme.shapes.small)
                    .clickable(enabled = !state.busy, role = Role.Button, onClickLabel = subscriptionAction) { openSubscriptions(selected == null) },
                    verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    if (selected != null) SubscriptionIcon(selected.icon, selected.iconImage)
                    else Icon(Icons.Outlined.Add, null, Modifier.size(26.dp))
                    Text(selected?.name ?: stringResource(R.string.add_subscription), Modifier.weight(1f),
                        style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Icon(Icons.AutoMirrored.Outlined.ArrowForward, null, Modifier.size(20.dp))
                }
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                    Text(stringResource(when (runtime) {
                        is RuntimeState.Failed -> R.string.runtime_failed
                        RuntimeState.Stopped -> R.string.runtime_stopped
                        RuntimeState.Starting -> R.string.runtime_starting
                        RuntimeState.Restarting -> R.string.runtime_restarting
                        RuntimeState.Stopping -> R.string.runtime_stopping
                        is RuntimeState.Running -> R.string.runtime_running
                    }), style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
                    Switch(checked = active, onCheckedChange = { if (it) startVpn() else model.stopVpn() },
                        colors = SwitchDefaults.colors(uncheckedThumbColor = MaterialTheme.colorScheme.onSurfaceVariant),
                        enabled = !authorizationPending && runtime != RuntimeState.Stopping &&
                            (active || (!state.busy && state.candidate != null && !state.projectionFailed)),
                        modifier = Modifier.sizeIn(minWidth = 48.dp, minHeight = 48.dp).semantics { contentDescription = switchAction })
                }
                if (runtime is RuntimeState.Running && live?.let { it.profileId != state.library.selectedId ||
                    it.revision != state.candidate?.revision || it.geoRevision != state.geoRevision } == true)
                    Text(stringResource(R.string.pending_restart), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary)
                SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth().semantics { contentDescription = modeLabel }) {
                    ProxyMode.entries.forEachIndexed { index, mode ->
                        SegmentedButton(selected = state.library.preferences.mode == mode,
                            onClick = { model.savePreferences(state.library.preferences.copy(mode = mode)) },
                            enabled = !state.busy, shape = SegmentedButtonDefaults.itemShape(index, 3)) { Text(stringResource(mode.label)) }
                    }
                }
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Text(stringResource(R.string.external_ui), Modifier.weight(1f))
                    FeatureHelp(ExternalUIHelp)
                    Switch(checked = state.library.preferences.externalUIEnabled || state.dashboardDownloading,
                        onCheckedChange = model::setExternalUI, enabled = !state.busy,
                        modifier = Modifier.semantics { contentDescription = externalLabel })
                }
                if (state.dashboardDownloading) Row(verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                    Text(stringResource(R.string.dashboard_preparing), style = MaterialTheme.typography.bodySmall)
                    TextButton(onClick = model::cancelDashboardDownload, shape = MaterialTheme.shapes.small) { Text(stringResource(R.string.cancel)) }
                }
                if (state.library.preferences.externalUIEnabled)
                    OutlinedButton(onClick = model::openDashboard, modifier = Modifier.fillMaxWidth(),
                        enabled = !state.busy && runtime is RuntimeState.Running && live?.let {
                            it.profileId == state.library.selectedId && it.revision == state.candidate?.revision && it.geoRevision == state.geoRevision
                        } == true, shape = MaterialTheme.shapes.small) {
                        Icon(Icons.Outlined.OpenInBrowser, null, Modifier.size(18.dp))
                        Spacer(Modifier.width(8.dp))
                        Text(stringResource(R.string.open_zashboard))
                    }
                if (state.projectionFailed) Text(stringResource(R.string.candidate_error), color = MaterialTheme.colorScheme.error)
            }
        }
        item {
            JeemiCard(Modifier.fillMaxWidth()) {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(stringResource(R.string.about_jeemi), Modifier.weight(1f), style = MaterialTheme.typography.titleMedium)
                    TextButton(onClick = { openAboutLink("https://github.com/bluevava/jeemi-android") },
                        modifier = Modifier.heightIn(min = 48.dp), contentPadding = PaddingValues(horizontal = 8.dp),
                        shape = MaterialTheme.shapes.small) {
                        Text(stringResource(R.string.open_source_repository), textDecoration = TextDecoration.Underline)
                    }
                }
                Text(stringResource(R.string.about_jeemi_description), style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
                Column(Modifier.fillMaxWidth()) {
                    AboutCommunityLink(stringResource(R.string.about_jeemi_group), stringResource(R.string.about_jeemi_group_description)) {
                        openAboutLink("https://t.me/Jeemi_group")
                    }
                    AboutCommunityLink(stringResource(R.string.about_jeemi_channel), stringResource(R.string.about_jeemi_channel_description)) {
                        openAboutLink("https://t.me/Jeemi_channel")
                    }
                }
            }
        }
        item {
            CollapsibleCard(stringResource(R.string.runtime_preferences), RuntimeHelp) {
                listOf("basic" to R.string.runtime_basics, "dns" to R.string.dns_settings,
                    "resources" to R.string.dns_resources, "lan" to R.string.lan_rules).forEach { (id, label) ->
                    OutlinedButton(onClick = { model.beginRuntimeEdit(); editor = id }, enabled = !state.busy, modifier = Modifier.fillMaxWidth(), shape = MaterialTheme.shapes.medium) {
                        Text(stringResource(label), Modifier.weight(1f))
                        Icon(Icons.AutoMirrored.Outlined.ArrowForward, null, Modifier.size(18.dp))
                    }
                }
            }
        }
    }
    editor?.let { RuntimeEditor(it, state, model) { editor = null } }
}

@Composable
private fun AboutCommunityLink(label: String, description: String, onClick: () -> Unit) {
    TextButton(onClick = onClick, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp),
        contentPadding = PaddingValues(vertical = 8.dp), shape = MaterialTheme.shapes.small) {
        Text(buildAnnotatedString {
            withStyle(SpanStyle(textDecoration = TextDecoration.Underline)) { append(label) }
            withStyle(SpanStyle(color = MaterialTheme.colorScheme.onSurfaceVariant)) { append("  "); append(description) }
        }, modifier = Modifier.fillMaxWidth(), style = MaterialTheme.typography.bodyMedium)
    }
}
