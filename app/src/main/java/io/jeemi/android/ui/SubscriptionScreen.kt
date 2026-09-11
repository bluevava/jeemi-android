@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
package io.jeemi.android.ui
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.clickable
import io.jeemi.android.ui.theme.JeemiShapes
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import io.jeemi.android.R
import io.jeemi.android.domain.*
import io.jeemi.android.ui.components.*
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import kotlinx.coroutines.delay

@Composable
fun SubscriptionScreen(state: AppState, model: JeemiViewModel, modifier: Modifier,
    importRequested: Boolean = false, onImportHandled: () -> Unit = {}) {
    val selected = state.library.selected
    var panel by rememberSaveable { mutableStateOf<String?>(null) }
    var shelf by rememberSaveable { mutableStateOf(false) }
    var searchOpen by rememberSaveable { mutableStateOf(false) }
    var expandedGroups by rememberSaveable { mutableStateOf(listOf<String>()) }
    var deleteId by rememberSaveable { mutableStateOf<String?>(null) }
    var query by rememberSaveable { mutableStateOf("") }
    var editingId by rememberSaveable { mutableStateOf<String?>(null) }
    var associationId by rememberSaveable { mutableStateOf<String?>(null) }
    var handledImport by rememberSaveable { mutableIntStateOf(state.importRevision) }
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    val preferences = state.library.preferences
    val live by model.live.collectAsState()
    val runtime by model.runtime.collectAsState()
    val matching = runtime is RuntimeState.Running && live?.let { it.profileId == selected?.id &&
        it.revision == state.candidate?.revision && it.geoRevision == state.geoRevision } == true
    val groups = remember(state.structure.groups, matching, live?.proxies) {
        state.structure.groups.map { group ->
            if (matching) group.copy(members = live?.proxies?.get(group.name)?.members ?: group.members) else group
        }
    }
    val groupsByName = remember(groups) { groups.associateBy { it.name } }
    val choices = remember(matching, live?.proxies, selected?.selections) {
        if (matching) live?.proxies.orEmpty().mapValues { it.value.now } else selected?.selections.orEmpty()
    }
    fun memberDelay(name: String): Int? {
        if (!matching) return null
        val child = groupsByName[name]
        val leaf = if (child == null) name else selectorEgress(child, groupsByName, choices, true).nodeName
        return leaf?.let { live?.proxies?.get(it)?.delay }
    }
    val paths by model.selectorPaths.collectAsState()
    LaunchedEffect(groupsByName, selected?.id, preferences.mode, paths) {
        paths.filterKeys { it.take(2) == listOf(selected?.id.orEmpty(), preferences.mode.name) }.forEach { (key, requested) ->
            val root = groupsByName[key.last()]
            val valid = root?.let { selectorPath(it, requested, groupsByName).drop(1).map { group -> group.name } }.orEmpty()
            if (valid != requested) model.setSelectorPath(key, valid)
        }
    }
    val nodeTypes = remember(matching, live?.proxies, state.candidate?.nodes, selected?.nodes, state.structure.groups) {
        val groupNames = state.structure.groups.map { it.name }.toSet()
        if (matching) live?.proxies.orEmpty().filter { (name, proxy) ->
            proxy.members.isEmpty() && name !in groupNames && name !in builtinProxyNames
        }.mapValues { it.value.type }
        else (state.candidate?.nodes ?: selected?.nodes).orEmpty()
            .filter { it.name !in groupNames && it.name !in builtinProxyNames }.associate { it.name to it.type }
    }
    val search = remember(query) { NodeNameSearch(query) }
    val visible = remember(groups, preferences.showHiddenGroups, search, nodeTypes) {
        search.filter(groups.filter { !it.hidden || preferences.showHiddenGroups }, nodeTypes.keys)
    }
    val testTargets = remember(visible, nodeTypes, search, paths, groupsByName, selected?.id, preferences.mode) {
        val displayed = if (search.active) visible else visible.map { root ->
            selectorPath(groupsByName.getValue(root.name),
                paths[listOf(selected?.id.orEmpty(), preferences.mode.name, root.name)].orEmpty(), groupsByName).last()
        }
        nodeTestTargets(displayed, nodeTypes.keys)
    }
    val fontScale = LocalDensity.current.fontScale
    val columns = when (preferences.nodeDensity) { NodeDensity.LARGE -> 1; NodeDensity.MEDIUM -> 2; NodeDensity.SMALL -> 3 }
        .let { if (fontScale >= 1.7f) 1 else if (fontScale >= 1.25f) minOf(it, 2) else it }
    val focus = remember { FocusRequester() }
    fun beginImport() {
        editingId = null
        model.importDraft.value = ""; panel = "import"
    }
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    LaunchedEffect(lifecycle) {
        lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) { while (true) { now = System.currentTimeMillis(); delay(1000) } }
    }
    LaunchedEffect(importRequested) {
        if (importRequested) { beginImport(); onImportHandled() }
    }
    LaunchedEffect(state.importRevision) {
        if (state.importRevision > handledImport) {
            handledImport = state.importRevision
            panel = null; model.importDraft.value = ""
        }
    }
    LaunchedEffect(searchOpen) { if (searchOpen) focus.requestFocus() }
    BackHandler(searchOpen && panel == null) { searchOpen = false; query = "" }
    Column(modifier.imePadding()) {
        LazyColumn(Modifier.weight(1f), contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            item(key = "subscription-management") {
                Card(Modifier.fillMaxWidth(), shape = JeemiShapes.Panel,
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)) {
                    Column(Modifier.padding(horizontal = 10.dp, vertical = 6.dp)) {
                        if (shelf) {
                            Row(Modifier.fillMaxWidth().heightIn(min = 48.dp)
                                .clickable(role = Role.Button) { shelf = false }, verticalAlignment = Alignment.CenterVertically) {
                                Text(stringResource(R.string.collapse_subscription_panel), Modifier.weight(1f),
                                    style = MaterialTheme.typography.bodyMedium)
                                Icon(Icons.Outlined.ExpandLess, stringResource(R.string.subscription_shelf), Modifier.size(20.dp))
                            }
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text(stringResource(R.string.subscription_count, state.library.subscriptions.size), Modifier.weight(1f), style = MaterialTheme.typography.labelSmall)
                                TextButton(onClick = ::beginImport, enabled = !state.busy, shape = MaterialTheme.shapes.small) { Icon(Icons.Outlined.Add, null); Text(stringResource(R.string.import_action)) }
                            }
                            state.library.subscriptions.forEach { subscription ->
                                key(subscription.id) {
                                    var menu by remember { mutableStateOf(false) }
                                    SubscriptionEntry(subscription, state.library.resources.firstOrNull { it.id == subscription.handlerId },
                                        subscription.id == state.library.selectedId, state.busy, now,
                                        select = { model.select(subscription.id) }, refresh = { model.refresh(subscription.id) },
                                        associate = { associationId = subscription.id; panel = "associate" }) {
                                        Box {
                                            IconButton(onClick = { menu = true }, enabled = !state.busy) { Icon(Icons.Outlined.MoreVert, stringResource(R.string.more_actions)) }
                                            DropdownMenu(menu, { menu = false }) {
                                                DropdownMenuItem(text = { Text(stringResource(R.string.edit)) }, onClick = {
                                                    menu = false; editingId = subscription.id
                                                    model.importDraft.value = subscription.original; panel = "import"
                                                })
                                                DropdownMenuItem(text = { Text(stringResource(R.string.associations)) }, onClick = { menu = false; associationId = subscription.id; panel = "associate" })
                                                DropdownMenuItem(text = { Text(stringResource(R.string.chain_associate)) }, onClick = { menu = false; associationId = subscription.id; panel = "chains" })
                                                DropdownMenuItem(text = { Text(stringResource(R.string.chain_generation_details)) }, enabled = subscription.chainGroupIds.isNotEmpty(),
                                                    onClick = { menu = false; model.previewChains(subscription.id) })
                                                DropdownMenuItem(text = { Text(stringResource(R.string.conversion_details)) },
                                                    onClick = { menu = false; model.showConversionReport(subscription.normalizationReport) })
                                                DropdownMenuItem(text = { Text(stringResource(R.string.original_document)) }, onClick = { menu = false; model.viewDocument(subscription.original, R.string.original_document) })
                                                DropdownMenuItem(text = { Text(stringResource(R.string.normalized_document)) }, onClick = { menu = false; model.viewDocument(subscription.normalized, R.string.normalized_document) })
                                                DropdownMenuItem(text = { Text(stringResource(R.string.delete)) }, onClick = { menu = false; deleteId = subscription.id })
                                            }
                                        }
                                    }
                                    Spacer(Modifier.height(6.dp))
                                }
                            }
                        } else SubscriptionSummary(selected, state.busy, fontScale >= 1.4f, now,
                            refresh = { selected?.let { model.refresh(it.id) } }, providers = { panel = "providers" },
                            preview = model::preview, expand = { shelf = true }, beginImport = ::beginImport)
                    }
                }
            }
            if (state.projectionFailed) item { Text(stringResource(R.string.candidate_error), color = MaterialTheme.colorScheme.error) }
            if (selected != null && visible.isEmpty()) item {
                EmptyCard(stringResource(if (search.active) R.string.no_results else R.string.no_groups), NodesHelp)
            }
            visible.forEach { group ->
                val root = groupsByName.getValue(group.name)
                val pathKey = listOf(selected?.id.orEmpty(), preferences.mode.name, group.name)
                val path = selectorPath(root, paths[pathKey].orEmpty(), groupsByName)
                val currentGroup = if (search.active) group else path.last()
                val expanded = group.name in expandedGroups || search.active
                item(key = "group-" + group.name) {
                    SelectorHeader(root, egressText(selectorEgress(root, groupsByName, choices, matching)), matching, expanded, model.selectorIcons) {
                        expandedGroups = if (group.name in expandedGroups) expandedGroups - group.name else expandedGroups + group.name
                    }
                }
                if (expanded) {
                    if (!search.active && path.size > 1) item(key = "path-" + group.name) {
                        SelectorBreadcrumb(path) { model.setSelectorPath(pathKey, it) }
                    }
                    val members = currentGroup.members.let { names ->
                        when (preferences.nodeSort) {
                            NodeSort.NAME -> names.sortedBy { it.lowercase() }
                            NodeSort.TYPE -> names.sortedWith(compareBy({ node -> nodeTypes[node].orEmpty() }, { it }))
                            NodeSort.DELAY -> if (matching) names.sortedBy { memberDelay(it)?.takeIf { delay -> delay > 0 } ?: Int.MAX_VALUE } else names
                            else -> names
                        }
                    }
                    items(members.chunked(columns).withIndex().toList(),
                        key = { "members-${group.name.length}:${group.name}-${currentGroup.name.length}:${currentGroup.name}-${it.index}" }) { (_, row) ->
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            row.forEach { node ->
                                val chosen = selectorChoice(currentGroup, choices, matching) == node
                                val current = if (matching) live?.proxies?.get(node) else null
                                val child = groupsByName[node]
                                if (child != null && !search.active) {
                                    SelectorGroupCard(child, memberDelay(node), columns, chosen,
                                        selectable = !state.busy && !runtime.transitioning && currentGroup.type == "select",
                                        canOpen = path.none { it.name == node }, modifier = Modifier.weight(1f), icons = model.selectorIcons,
                                        select = { model.selectNode(currentGroup.name, node) },
                                        open = { model.setSelectorPath(pathKey, path.drop(1).map { it.name } + node) })
                                } else {
                                val type = nodeTypes[node] ?: current?.type ?: groups.firstOrNull { it.name == node }?.type
                                    ?: node.takeIf { it in builtinProxyNames }.orEmpty()
                                ProxyNodeCard(node, type, current?.delay, columns, chosen,
                                    selectable = !state.busy && !runtime.transitioning && currentGroup.type == "select",
                                    testable = matching && !state.testing && current != null && current.members.isEmpty() &&
                                        current.type.lowercase() !in listOf("direct", "reject", "pass"),
                                    testing = node in state.testingNodes, modifier = Modifier.weight(1f),
                                    select = { model.selectNode(currentGroup.name, node) }, test = { model.testNode(node) })
                                }
                            }
                            repeat(columns - row.size) { Spacer(Modifier.weight(1f)) }
                        }
                    }
                    if (currentGroup.providers.isNotEmpty()) item {
                        Text(currentGroup.providers.joinToString(" · "), style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(horizontal = 12.dp))
                    }
                }
            }
        }
        Surface(color = MaterialTheme.colorScheme.surface.copy(alpha = .96f), shadowElevation = 4.dp) {
            Column {
                if (searchOpen) OutlinedTextField(query, { query = it }, Modifier.fillMaxWidth().padding(horizontal = 16.dp).focusRequester(focus),
                    singleLine = true, label = { Text(stringResource(R.string.search_nodes)) },
                    trailingIcon = { IconButton(onClick = { query = ""; searchOpen = false }) { Icon(Icons.Outlined.Close, stringResource(R.string.hide_search)) } })
                Row(Modifier.fillMaxWidth().heightIn(min = 48.dp), horizontalArrangement = Arrangement.SpaceEvenly, verticalAlignment = Alignment.CenterVertically) {
                    IconButton(onClick = {
                        model.testNodes(testTargets)
                    }, enabled = state.testing || (matching && state.testingNodes.isEmpty() && testTargets.isNotEmpty())) {
                        Icon(if (state.testing) Icons.Outlined.StopCircle else Icons.Outlined.Speed, stringResource(if (state.testing) R.string.cancel else R.string.test_nodes), Modifier.size(18.dp))
                    }
                    IconButton(onClick = { searchOpen = !searchOpen; if (!searchOpen) query = "" }) { Icon(Icons.Outlined.Search, stringResource(R.string.search_nodes), Modifier.size(18.dp)) }
                    IconToggleButton(checked = preferences.showHiddenGroups, onCheckedChange = { model.savePreferences(preferences.copy(showHiddenGroups = it)) }, enabled = !state.busy) {
                        Icon(if (preferences.showHiddenGroups) Icons.Outlined.Visibility else Icons.Outlined.VisibilityOff, stringResource(R.string.show_hidden_groups), Modifier.size(18.dp))
                    }
                    IconButton(onClick = { panel = "fallback" }, enabled = selected != null && state.candidate != null) { Icon(Icons.Outlined.FilterAlt, stringResource(R.string.fallback_override), Modifier.size(18.dp)) }
                    IconToggleButton(checked = shelf, onCheckedChange = { shelf = it }) { Icon(Icons.Outlined.Layers, stringResource(R.string.subscription_shelf), Modifier.size(18.dp)) }
                }
            }
        }
    }
    if (panel == "providers") EditorDialog(stringResource(R.string.rule_providers), state.busy, { panel = null }, help = ProvidersHelp) {
        LazyColumn {
            items(state.candidate?.providers ?: emptyList()) { provider ->
                ListItem(headlineContent = { Text(provider.name) }, supportingContent = { Text(provider.type + " · " + provider.behavior) },
                    leadingContent = { Checkbox(provider.name !in (selected?.disabledProviders ?: emptyList()),
                        { model.provider(provider.name, it) }, enabled = !state.busy) },
                    trailingContent = { IconButton(onClick = { model.refreshProvider(provider.name) }, enabled = matching && !state.busy && provider.type == "http" && provider.name !in selected?.disabledProviders.orEmpty()) {
                        Icon(Icons.Outlined.Refresh, stringResource(R.string.refresh))
                    } })
            }
            if (state.candidate?.providers.isNullOrEmpty()) item { Text(stringResource(R.string.no_results), Modifier.padding(vertical = 24.dp)) }
        }
    }
    if (panel == "fallback" && selected != null) FallbackDialog(state, model) { panel = null }
    if (panel == "associate") state.library.subscriptions.firstOrNull { it.id == associationId }?.let {
        AssociationDialog(it, state, model) { panel = null }
    }
    if (panel == "chains") state.library.subscriptions.firstOrNull { it.id == associationId }?.let {
        ChainAssociation(it, state, model) { panel = null }
    }
    if (panel == "import") SubscriptionImportDialog(state.library.subscriptions.firstOrNull { it.id == editingId }, state, model) { panel = null }
    deleteId?.let { id -> AlertDialog(onDismissRequest = { deleteId = null }, title = { Text(stringResource(R.string.delete_subscription)) },
        text = { Text(stringResource(R.string.delete_description)) }, dismissButton = { TextButton(onClick = { deleteId = null }, shape = MaterialTheme.shapes.small) { Text(stringResource(R.string.cancel)) } },
        confirmButton = { TextButton(onClick = { model.remove(id); deleteId = null }, shape = MaterialTheme.shapes.small) { Text(stringResource(R.string.delete)) } }) }
}

@Composable
private fun AssociationDialog(subscription: Subscription, state: AppState, model: JeemiViewModel, dismiss: () -> Unit) {
    var handler by rememberSaveable { mutableStateOf(subscription.handlerId) }
    var extras by rememberSaveable { mutableStateOf(subscription.resourceIds) }
    val revision = rememberSaveable { state.saveRevision }
    LaunchedEffect(state.saveRevision) { if (state.saveRevision > revision) dismiss() }
    EditorDialog(stringResource(R.string.associations), state.busy, dismiss, action = {
        TextButton(onClick = { model.associate(subscription.id, handler, extras) }, enabled = !state.busy, shape = MaterialTheme.shapes.small) { Text(stringResource(R.string.save)) }
    }, help = AssociationHelp) {
        LazyColumn(contentPadding = PaddingValues(bottom = 24.dp)) {
            item { ListItem(headlineContent = { Text(stringResource(R.string.no_handler)) }, leadingContent = { RadioButton(handler == null, null) }, modifier = Modifier.clickable(enabled = !state.busy) { handler = null }) }
            items(state.library.resources.filter { it.kind in listOf(ResourceKind.CONFIG, ResourceKind.SCRIPT) }) { item ->
                ListItem(headlineContent = { Text(item.name) }, supportingContent = { Text(stringResource(item.kind.label)) }, leadingContent = { RadioButton(handler == item.id, null) },
                    modifier = Modifier.clickable(enabled = !state.busy) { handler = item.id })
            }
            item { Text(stringResource(R.string.extra_resources), Modifier.padding(vertical = 20.dp), style = MaterialTheme.typography.titleMedium) }
            items(state.library.resources.filter { it.formatVersion < 2 && it.kind in listOf(ResourceKind.GROUPS, ResourceKind.RULES) }) { item ->
                ListItem(headlineContent = { Text(item.name) }, supportingContent = { Text(stringResource(item.kind.label)) }, leadingContent = { Checkbox(item.id in extras, null) },
                    modifier = Modifier.clickable(enabled = !state.busy) { extras = if(item.id in extras) extras - item.id else extras + item.id })
            }
            if (state.library.resources.isEmpty()) item { Text(stringResource(R.string.no_resources), color = MaterialTheme.colorScheme.onSurfaceVariant) }
        }
    }
}

@Composable
private fun FallbackDialog(state: AppState, model: JeemiViewModel, dismiss: () -> Unit) {
    val subscription = state.library.selected ?: return
    var mode by rememberSaveable { mutableStateOf(subscription.fallbackMode) }
    var selector by rememberSaveable { mutableStateOf(subscription.fallbackSelector) }
    AlertDialog(onDismissRequest = { if (!state.busy) dismiss() },
        title = { SectionTitle(R.string.fallback_override, FallbackHelp) },
        text = { Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(stringResource(R.string.original_target, state.candidate?.fallbackOriginal.orEmpty()), style = MaterialTheme.typography.bodySmall)
            ChoiceField(stringResource(R.string.fallback_override), mode, listOf("none" to stringResource(R.string.keep_original),
                "direct" to stringResource(R.string.mode_direct), "selector" to stringResource(R.string.target_selector)), !state.busy) { mode = it }
            if (mode == "selector") ChoiceField(stringResource(R.string.target_selector), selector,
                state.candidate?.fallbackOptions.orEmpty().map { it to it }, !state.busy) { selector = it }
        } },
        dismissButton = { TextButton(onClick = dismiss, enabled = !state.busy, shape = MaterialTheme.shapes.small) { Text(stringResource(R.string.cancel)) } },
        confirmButton = { TextButton(onClick = { model.fallback(mode, if (mode == "selector") selector else "", dismiss) },
            enabled = !state.busy && (mode != "selector" || selector.isNotBlank()), shape = MaterialTheme.shapes.small) { Text(stringResource(R.string.save)) } })
}
