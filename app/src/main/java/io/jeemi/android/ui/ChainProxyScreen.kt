package io.jeemi.android.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import io.jeemi.android.R
import io.jeemi.android.data.*
import io.jeemi.android.domain.Subscription
import io.jeemi.android.ui.components.*
import io.jeemi.android.ui.theme.JeemiShapes
import org.json.JSONObject

internal val ChainHelp = HelpContent(R.string.chain_proxies, R.string.help_chain_purpose, R.string.help_chain_scenarios, R.string.help_chain_cautions)
internal val ChainFilterHelp = HelpContent(R.string.chain_filters, R.string.help_chain_filter_purpose, R.string.help_chain_filter_scenarios, R.string.help_chain_filter_cautions)
internal val ChainSourceHelp = HelpContent(R.string.chain_source, R.string.help_chain_source_purpose, R.string.help_chain_source_scenarios, R.string.help_chain_source_cautions)
internal val ChainNodesHelp = HelpContent(R.string.chain_nodes, R.string.help_chain_nodes_purpose, R.string.help_chain_nodes_scenarios, R.string.help_chain_nodes_cautions)
internal val ConversionHelp = HelpContent(R.string.conversion_details, R.string.help_conversion_purpose, R.string.help_conversion_scenarios, R.string.help_conversion_cautions)

@Composable
internal fun ChainProxyScreen(state: AppState, model: JeemiViewModel, modifier: Modifier) {
    var expanded by rememberSaveable { mutableStateOf(listOf<String>()) }
    var chooseKind by rememberSaveable { mutableStateOf(false) }
    var deleting by rememberSaveable { mutableStateOf<List<String>?>(null) }
    val draft by model.chainDraft.collectAsState()
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    DisposableEffect(lifecycle) {
        val observer = LifecycleEventObserver { _, event -> if (event == Lifecycle.Event.ON_STOP) model.cancelChainNetwork() }
        lifecycle.addObserver(observer)
        onDispose { lifecycle.removeObserver(observer); model.cancelChainNetwork() }
    }
    val enabled = !state.busy && !state.chainBusy
    LazyColumn(modifier.fillMaxWidth(), contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        item {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(stringResource(R.string.chain_group_count, state.chains.groups.size), Modifier.weight(1f), style = MaterialTheme.typography.labelMedium)
                FeatureHelp(ChainHelp)
                IconButton(onClick = { if (state.chainBusy) model.cancelChainNetwork() else model.refreshChains() },
                    enabled = state.chainBusy || enabled && state.chains.groups.any { it.sources.isNotEmpty() }) {
                    Icon(if (state.chainBusy) Icons.Outlined.Close else Icons.Outlined.Refresh, stringResource(if (state.chainBusy) R.string.cancel else R.string.chain_refresh_all))
                }
                IconButton(onClick = { chooseKind = true }, enabled = enabled, modifier = Modifier.testTag("chain-add-group")) {
                    Icon(Icons.Outlined.Add, stringResource(R.string.chain_add_group))
                }
            }
            if (state.chainBusy) LinearProgressIndicator(Modifier.fillMaxWidth())
        }
        if (state.chains.groups.isEmpty()) item { EmptyCard(stringResource(R.string.chain_empty), ChainHelp) }
        state.chains.groups.forEach { group ->
            item(key = "group-${group.id}") {
                Card(Modifier.fillMaxWidth(), shape = JeemiShapes.Panel, colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)) {
                    Column(Modifier.padding(horizontal = 10.dp, vertical = 4.dp)) {
                        Row(Modifier.fillMaxWidth().heightIn(min = 48.dp).clickable(role = Role.Button) {
                            expanded = if (group.id in expanded) expanded - group.id else expanded + group.id
                        }, verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.Outlined.Link, null, Modifier.size(20.dp)); Spacer(Modifier.width(8.dp))
                            Column(Modifier.weight(1f)) {
                                Text(group.name, maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.titleSmall)
                                Text(stringResource(if (group.kind == "manual") R.string.chain_manual_count else R.string.chain_subscription_count, group.nodes.size),
                                    style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                            Icon(if (group.id in expanded) Icons.Outlined.ExpandLess else Icons.Outlined.ExpandMore,
                                stringResource(if (group.id in expanded) R.string.collapse else R.string.expand))
                        }
                        if (group.id in expanded) Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(stringResource(R.string.associated_count, state.library.subscriptions.count { group.id in it.chainGroupIds }),
                                Modifier.weight(1f), style = MaterialTheme.typography.labelSmall)
                            IconButton(onClick = { model.openChainGroup(group) }, enabled = enabled) { Icon(Icons.Outlined.Edit, stringResource(R.string.edit)) }
                            if (group.kind == "subscription") IconButton(onClick = { model.refreshChains(group.id) }, enabled = enabled && group.sources.isNotEmpty()) {
                                Icon(Icons.Outlined.Refresh, stringResource(R.string.chain_refresh_group))
                            }
                            IconButton(onClick = { model.openChainItem(group, source = group.kind == "subscription") }, enabled = enabled) {
                                Icon(Icons.Outlined.Add, stringResource(if (group.kind == "manual") R.string.chain_add_nodes else R.string.chain_add_source))
                            }
                            IconButton(onClick = { deleting = listOf(group.id, "group", "", state.chains.revision.toString()) }, enabled = enabled) {
                                Icon(Icons.Outlined.DeleteOutline, stringResource(R.string.delete))
                            }
                        }
                    }
                }
            }
            if (group.id in expanded) {
                itemsIndexed(group.sources, key = { _, source -> "source-${group.id}-${source.id}" }) { index, source ->
                    Card(Modifier.fillMaxWidth(), shape = JeemiShapes.Component) {
                        Column(Modifier.padding(horizontal = 10.dp, vertical = 4.dp)) {
                            Text(stringResource(R.string.chain_source_label, index + 1, source.label), maxLines = 1,
                                overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodyMedium)
                            Text(stringResource(R.string.chain_source_count, source.nodeCount), style = MaterialTheme.typography.labelSmall)
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text(chainDate(source.updatedAt), Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.labelSmall)
                                IconButton(onClick = { model.showConversionReport(source.report) }) { Icon(Icons.Outlined.Info, stringResource(R.string.conversion_details)) }
                                IconButton(onClick = { model.openChainItem(group, source.id, source = true) }, enabled = enabled) { Icon(Icons.Outlined.Edit, stringResource(R.string.edit)) }
                                IconButton(onClick = { model.refreshChains(group.id, source.id) }, enabled = enabled) { Icon(Icons.Outlined.Refresh, stringResource(R.string.refresh)) }
                                IconButton(onClick = { deleting = listOf(group.id, "source", source.id, state.chains.revision.toString()) }, enabled = enabled) {
                                    Icon(Icons.Outlined.DeleteOutline, stringResource(R.string.delete))
                                }
                            }
                            state.chainFailures.lastOrNull { it.groupId == group.id && it.sourceId == source.id }?.let {
                                Text(businessIssueText(it.issue), color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
                            }
                        }
                    }
                }
                items(group.nodes, key = { "node-${group.id}-${it.id}" }) { node ->
                    Card(Modifier.fillMaxWidth(), shape = JeemiShapes.Node) {
                        Row(Modifier.padding(horizontal = 10.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                            Column(Modifier.weight(1f)) {
                                Text(node.name, maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodyMedium)
                                Text(node.type, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                            if (group.kind == "manual") {
                                IconButton(onClick = { model.openChainItem(group, node.id) }, enabled = enabled) { Icon(Icons.Outlined.Edit, stringResource(R.string.edit)) }
                                IconButton(onClick = { deleting = listOf(group.id, "node", node.id, state.chains.revision.toString()) }, enabled = enabled) {
                                    Icon(Icons.Outlined.DeleteOutline, stringResource(R.string.delete))
                                }
                            }
                        }
                    }
                }
            }
        }
    }
    if (chooseKind) AlertDialog(onDismissRequest = { chooseKind = false }, title = { SectionTitle(R.string.chain_add_group, ChainHelp) },
        text = { Column {
            listOf("manual" to R.string.chain_manual, "subscription" to R.string.chain_subscription).forEach { (kind, label) ->
                OutlinedButton(onClick = { chooseKind = false; model.openChainGroup(kind = kind) }, Modifier.fillMaxWidth(), shape = MaterialTheme.shapes.small) { Text(stringResource(label)) }
            }
        } }, confirmButton = { TextButton(onClick = { chooseKind = false }, shape = MaterialTheme.shapes.small) { Text(stringResource(R.string.cancel)) } })
    deleting?.let { values -> AlertDialog(onDismissRequest = { deleting = null }, title = { Text(stringResource(R.string.delete)) },
        text = { Text(stringResource(R.string.chain_delete_note)) },
        dismissButton = { TextButton(onClick = { deleting = null }, enabled = !state.busy, shape = MaterialTheme.shapes.small) { Text(stringResource(R.string.cancel)) } },
        confirmButton = { TextButton(onClick = { model.deleteChainItem(values[0], values[1], values[2], values[3].toInt()) { deleting = null } }, enabled = enabled, shape = MaterialTheme.shapes.small) { Text(stringResource(R.string.delete)) } }) }
    draft?.let { ChainEditor(it, state, model) }
}

private fun chainDate(value: String): String = runCatching {
    java.text.DateFormat.getDateTimeInstance(java.text.DateFormat.SHORT, java.text.DateFormat.SHORT)
        .format(java.util.Date.from(java.time.Instant.parse(value)))
}.getOrDefault("—")

@Composable
private fun ChainEditor(draft: ChainDraft, state: AppState, model: JeemiViewModel) {
    val enabled = !state.busy && !state.chainBusy
    var reveal by rememberSaveable(draft.groupId, draft.id) { mutableStateOf(false) }
    fun update(next: ChainDraft) { model.chainDraft.value = next }
    val title = when (draft.mode) { "group" -> R.string.chain_group; "source" -> R.string.chain_source; else -> R.string.chain_nodes }
    val help = when (draft.mode) { "group" -> ChainHelp; "source" -> ChainSourceHelp; else -> ChainNodesHelp }
    EditorDialog(stringResource(title), state.busy, model::closeChainEditor, help = help, footer = {
        if (state.chainBusy) TextButton(onClick = model::cancelChainNetwork, shape = MaterialTheme.shapes.small) { Text(stringResource(R.string.chain_cancel_refresh)) }
        else Button(onClick = model::saveChainEditor, enabled = enabled && when (draft.mode) {
            "group" -> draft.name.isNotBlank(); "source" -> draft.url.isNotBlank(); else -> draft.contents.isNotBlank()
        }, modifier = Modifier.testTag("chain-save"), shape = MaterialTheme.shapes.small) { Text(stringResource(R.string.save)) }
    }) {
        Column(Modifier.verticalScroll(rememberScrollState()).padding(bottom = 24.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            if (state.chainBusy) LinearProgressIndicator(Modifier.fillMaxWidth())
            when (draft.mode) {
                "group" -> {
                    OutlinedTextField(draft.name, { update(draft.copy(name = it.take(160))) }, Modifier.fillMaxWidth().testTag("chain-name"),
                        enabled = enabled, label = { Text(stringResource(R.string.name)) }, singleLine = true)
                    CollapsibleCard(stringResource(R.string.chain_filters), ChainFilterHelp, initiallyOpen = true) {
                        OutlinedTextField(draft.selectorFilter, { update(draft.copy(selectorFilter = it.take(2048))) }, Modifier.fillMaxWidth(), enabled = enabled,
                            label = { Text(stringResource(R.string.chain_selector_filter)) })
                        OutlinedTextField(draft.nodeFilter, { update(draft.copy(nodeFilter = it.take(2048))) }, Modifier.fillMaxWidth(), enabled = enabled,
                            label = { Text(stringResource(R.string.chain_node_filter)) })
                    }
                }
                "source" -> {
                    OutlinedTextField(draft.url, { update(draft.copy(url = it.take(8192))) }, Modifier.fillMaxWidth().testTag("chain-url"), enabled = enabled,
                        label = { Text(stringResource(R.string.chain_url)) }, singleLine = true,
                        visualTransformation = if (reveal) VisualTransformation.None else PasswordVisualTransformation(),
                        trailingIcon = { IconButton(onClick = { reveal = !reveal }) { Icon(if (reveal) Icons.Outlined.VisibilityOff else Icons.Outlined.Visibility,
                            stringResource(if (reveal) R.string.chain_hide_url else R.string.chain_show_url)) } })
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(stringResource(R.string.chain_landing_filter), Modifier.weight(1f), style = MaterialTheme.typography.titleSmall)
                        FeatureHelp(ChainFilterHelp)
                    }
                    OutlinedTextField(draft.filter, { update(draft.copy(filter = it.take(2048))) }, Modifier.fillMaxWidth(), enabled = enabled,
                        label = { Text(stringResource(R.string.chain_landing_filter)) })
                }
                else -> OutlinedTextField(draft.contents, { update(draft.copy(contents = it)) }, Modifier.fillMaxWidth().testTag("chain-contents"),
                    enabled = enabled, label = { Text(stringResource(R.string.resource_content)) }, minLines = 12,
                    textStyle = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace))
            }
        }
    }
}

@Composable
internal fun ChainAssociation(subscription: Subscription, state: AppState, model: JeemiViewModel, dismiss: () -> Unit) {
    var selected by rememberSaveable(subscription.id) { mutableStateOf(subscription.chainGroupIds) }
    val revision = rememberSaveable(subscription.id) { subscription.chainRevision }
    val libraryRevision = rememberSaveable(subscription.id) { state.chains.revision }
    EditorDialog(stringResource(R.string.chain_associate), state.busy, dismiss, help = ChainHelp, footer = {
        Button(onClick = { model.associateChains(subscription.id, selected, revision, libraryRevision, dismiss) }, enabled = !state.busy,
            modifier = Modifier.testTag("chain-associate-save"), shape = MaterialTheme.shapes.small) { Text(stringResource(R.string.save)) }
    }) {
        LazyColumn(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            item {
                Text(subscription.name, style = MaterialTheme.typography.titleSmall)
                TextButton(onClick = { selected = emptyList() }, enabled = !state.busy, shape = MaterialTheme.shapes.small) { Text(stringResource(R.string.chain_unlink_all)) }
            }
            if (state.chains.groups.isEmpty()) item { EmptyCard(stringResource(R.string.chain_empty), ChainHelp) }
            items(state.chains.groups, key = { it.id }) { group ->
                Row(Modifier.fillMaxWidth().clickable(enabled = !state.busy, role = Role.Checkbox) {
                    selected = if (group.id in selected) selected - group.id else selected + group.id
                }.padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(group.id in selected, onCheckedChange = null)
                    Column(Modifier.weight(1f)) {
                        Text(group.name, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text(stringResource(R.string.chain_source_count, group.nodes.size), style = MaterialTheme.typography.labelSmall)
                    }
                }
            }
        }
    }
}
