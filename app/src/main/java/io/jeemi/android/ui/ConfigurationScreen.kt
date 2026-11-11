@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class, androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
package io.jeemi.android.ui
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.FactCheck
import androidx.compose.material.icons.automirrored.outlined.Rule
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.jeemi.android.R
import io.jeemi.android.data.*
import io.jeemi.android.domain.*
import io.jeemi.android.ui.components.*
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

@Composable
fun ConfigurationScreen(state: AppState, model: JeemiViewModel, modifier: Modifier) {
    val lifecycle = androidx.lifecycle.compose.LocalLifecycleOwner.current.lifecycle
    DisposableEffect(lifecycle, model) {
        val observer = androidx.lifecycle.LifecycleEventObserver { _, event ->
            if (event == androidx.lifecycle.Lifecycle.Event.ON_STOP) model.cancelResourceNetwork()
        }
        lifecycle.addObserver(observer)
        onDispose { lifecycle.removeObserver(observer); model.cancelResourceNetwork() }
    }
    var kind by rememberSaveable { mutableStateOf(ResourceKind.CONFIG) }
    var chainPage by rememberSaveable { mutableStateOf(false) }
    var query by rememberSaveable { mutableStateOf("") }
    var filtersOpen by rememberSaveable { mutableStateOf(false) }
    var configFilter by rememberSaveable { mutableStateOf(ALL_RESOURCES) }
    var categoryFilter by rememberSaveable { mutableStateOf(ALL_RESOURCES) }
    var groupFilters by rememberSaveable { mutableStateOf(emptyList<String>()) }
    var builtins by rememberSaveable { mutableStateOf(false) }
    var relationshipKey by rememberSaveable { mutableStateOf<String?>(null) }
    var deleteId by rememberSaveable { mutableStateOf<String?>(null) }
    var chooseGroupKind by rememberSaveable { mutableStateOf(false) }
    val editing by model.editingResource.collectAsState()
    val context = LocalContext.current
    // Do not expose results from an earlier library/filter while new work runs.
    val rows = key(state.library.resources, state.resourceGraph) {
        val result by produceState<List<ResourceLibraryRow>?>(null) {
            value = withContext(Dispatchers.Default) { indexResourceLibrary(state.library.resources, state.resourceGraph) }
        }
        result
    }
    val visible = key(rows, kind, query, configFilter, categoryFilter, groupFilters) {
        val result by produceState<List<ResourceLibraryRow>?>(null) {
            value = rows?.let { all -> withContext(Dispatchers.Default) {
                filterResourceLibrary(all, kind, query, configFilter, categoryFilter, groupFilters)
            } }
        }
        result
    }
    val filtering = configFilter.isNotEmpty() || categoryFilter.isNotEmpty() || groupFilters.isNotEmpty()
    Column(modifier) {
        ResourceCategoryDropdown(if (chainPage) "CHAIN" else kind.name) { selected ->
            chainPage = selected == "CHAIN"
            if (!chainPage) kind = ResourceKind.valueOf(selected)
            query = ""; configFilter = ALL_RESOURCES; categoryFilter = ALL_RESOURCES; groupFilters = emptyList()
        }
        if (chainPage) {
            ChainProxyScreen(state, model, Modifier.weight(1f))
        } else {
            Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp)) {
                ResourceLibraryToolbar(kind, query, { query = it }, filtering, filtersOpen,
                    { filtersOpen = !filtersOpen }, state.busy || state.resourceDownloading, create = {
                        if (kind == ResourceKind.GROUPS) chooseGroupKind = true else model.openResource(newResource(kind))
                    }, builtins = { builtins = true })
                if (filtersOpen && kind in listOf(ResourceKind.GROUPS, ResourceKind.RULES)) {
                    ResourceFilterControls(kind, rows.orEmpty(), rows != null && state.resourceGraph.available,
                        configFilter, { configFilter = it; groupFilters = emptyList() }, categoryFilter, { categoryFilter = it }, groupFilters, { groupFilters = it })
                }
                Box(Modifier.fillMaxWidth().heightIn(min = 24.dp), contentAlignment = Alignment.CenterStart) {
                    if (visible == null) LinearProgressIndicator(Modifier.fillMaxWidth())
                    else Text(stringResource(R.string.resource_results, visible.size, state.library.resources.count { it.kind == kind }),
                        Modifier.testTag("resource-results"), style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            key(kind, query, configFilter, categoryFilter, groupFilters) {
                LazyColumn(Modifier.weight(1f).testTag("resource-list"), contentPadding = PaddingValues(horizontal = 16.dp, vertical = 2.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    if (visible != null && visible.isEmpty()) item {
                        EmptyCard(stringResource(when {
                            !state.resourceGraph.available && (configFilter.isNotEmpty() || groupFilters.isNotEmpty()) -> R.string.resource_filters_unavailable
                            query.isBlank() && !filtering -> R.string.no_resources
                            else -> R.string.no_results
                        }), kind.help)
                    }
                    items(visible.orEmpty(), key = { it.resource.id }) { row ->
                        val item = row.resource
                        var menu by remember { mutableStateOf(false) }
                        Card(onClick = { model.openSavedResource(item.id) }, enabled = !state.busy && !state.resourceDownloading,
                            modifier = Modifier.fillMaxWidth().testTag("resource-card-${item.id}"), shape = MaterialTheme.shapes.large,
                            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)) {
                            Row(Modifier.padding(start = 12.dp, end = 4.dp, top = 6.dp, bottom = 6.dp).heightIn(min = 48.dp),
                                verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                                val (prefix, title) = remember(item.name, row.emoji) { selectorTitle(item.name).let { (prefix, name) ->
                                    (row.emoji.ifEmpty { prefix }) to if (row.emoji.isEmpty() || row.emoji == prefix) name else item.name
                                } }
                                if (prefix.isNotEmpty()) Text(prefix, fontSize = 22.sp, maxLines = 1)
                                else Icon(when (item.kind) {
                                    ResourceKind.GROUPS -> Icons.Outlined.AccountTree
                                    ResourceKind.RULES -> Icons.AutoMirrored.Outlined.Rule
                                    ResourceKind.CONFIG -> Icons.Outlined.Tune
                                    ResourceKind.SCRIPT -> Icons.Outlined.Code
                                }, null, Modifier.size(22.dp), tint = MaterialTheme.colorScheme.primary)
                                Column(Modifier.weight(1f)) {
                                    Text(title, style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                    ResourceFacts(row)
                                }
                                Box {
                                    IconButton(onClick = { menu = true }, enabled = !state.busy && !state.resourceDownloading,
                                        modifier = Modifier.testTag("resource-menu-${item.id}")) { Icon(Icons.Outlined.MoreVert, stringResource(R.string.more_actions), Modifier.size(19.dp)) }
                                    DropdownMenu(menu, { menu = false }) {
                                        DropdownMenuItem(text = { Text(stringResource(R.string.edit)) },
                                            leadingIcon = { Icon(Icons.Outlined.Edit, null, Modifier.size(18.dp)) },
                                            onClick = { menu = false; model.openSavedResource(item.id) })
                                        DropdownMenuItem(text = { Text(stringResource(R.string.resource_relationships)) },
                                            leadingIcon = { Icon(Icons.Outlined.AccountTree, null, Modifier.size(18.dp)) }, enabled = state.resourceGraph.available,
                                            onClick = { menu = false; relationshipKey = "resource:${item.id}" })
                                        DropdownMenuItem(text = { Text(stringResource(R.string.check_preview)) },
                                            leadingIcon = { Icon(Icons.AutoMirrored.Outlined.FactCheck, null, Modifier.size(18.dp)) },
                                            onClick = { menu = false; model.previewSavedResource(item.id) })
                                        if (item.kind == ResourceKind.SCRIPT && item.sourceUrl.isNotEmpty()) DropdownMenuItem(
                                            text = { Text(stringResource(R.string.redownload_script)) },
                                            leadingIcon = { Icon(Icons.Outlined.Refresh, null, Modifier.size(18.dp)) },
                                            onClick = { menu = false; model.refreshScript(item.id) })
                                        DropdownMenuItem(text = { Text(stringResource(R.string.delete)) }, onClick = { menu = false; deleteId = item.id })
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }
    if (builtins) BuiltinRulesDialog(state.busy, { builtins = false }) { kind ->
        model.createBuiltinRules(kind, builtinRuleLabels(context)); builtins = false
    }
    relationshipKey?.let { ResourceRelationshipsDialog(state.resourceGraph, it) { relationshipKey = null } }
    if (chooseGroupKind) AlertDialog(onDismissRequest = { chooseGroupKind = false }, title = { SectionTitle(R.string.group_category, GroupsHelp) },
        text = { Column {
            listOf("selector" to R.string.group_selector, "rule" to R.string.group_rule).forEach { (value, label) ->
                OutlinedButton(onClick = { chooseGroupKind = false; model.openResource(newResource(ResourceKind.GROUPS, value)) }, modifier = Modifier.fillMaxWidth(), shape = MaterialTheme.shapes.small) { Text(stringResource(label)) }
            }
        } }, confirmButton = { TextButton(onClick = { chooseGroupKind = false }, shape = MaterialTheme.shapes.small) { Text(stringResource(R.string.cancel)) } })
    editing?.let { ResourceEditor(it, state.copy(busy = state.busy || state.resourceDownloading), model) }
    deleteId?.let { id -> AlertDialog(onDismissRequest = { deleteId = null }, title = { Text(stringResource(R.string.delete_resource)) },
        text = { Column {
            Text(stringResource(R.string.delete_resource_note))
            RelationshipButton(state.resourceGraph, "resource:$id") { deleteId = null; relationshipKey = "resource:$id" }
        } }, dismissButton = { TextButton(onClick = { deleteId = null }, shape = MaterialTheme.shapes.small) { Text(stringResource(R.string.cancel)) } },
        confirmButton = { TextButton(onClick = { model.removeResource(id); deleteId = null }, shape = MaterialTheme.shapes.small) { Text(stringResource(R.string.delete)) } }) }
    state.packagePreview?.let { preview ->
        AlertDialog(onDismissRequest = { if (!state.busy) model.dismissPackage() }, title = { SectionTitle(R.string.import_package, PackageHelp) },
            text = { Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                listOf(R.string.package_added to preview.added, R.string.package_overwritten to preview.overwritten,
                    R.string.package_affected to preview.affected).forEach { (title, names) ->
                    Text(stringResource(title), style = MaterialTheme.typography.titleSmall)
                    Text(names.joinToString("\n").ifBlank { stringResource(R.string.none_items) }, style = MaterialTheme.typography.bodySmall)
                }
            } }, dismissButton = { TextButton(onClick = model::dismissPackage, enabled = !state.busy, shape = MaterialTheme.shapes.small) { Text(stringResource(R.string.cancel)) } },
            confirmButton = { TextButton(onClick = model::confirmPackage, enabled = !state.busy, shape = MaterialTheme.shapes.small) { Text(stringResource(R.string.package_confirm)) } })
    }
}

internal fun newResource(kind: ResourceKind, groupKind: String = "selector"): LocalResource {
    val id = UUID.randomUUID().toString().replace("-", "")
    val content = when (kind) {
        ResourceKind.CONFIG -> JSONObject().put("fields", JSONArray()).put("disabledFields", JSONArray())
            .put("resourcePlan", JSONObject().put("version", 1).put("strategyGroupIds", JSONArray())
                .put("disabledStrategyGroupIds", JSONArray()).put("rulesDisabled", false)
                .put("ruleStrategy", "append_before_terminal").put("defaultProxySelectorId", "")
                .put("match", JSONObject().put("mode", "none").put("selectorId", ""))).toString()
        ResourceKind.SCRIPT -> "function main(config) {\n  return config;\n}\n"
        ResourceKind.GROUPS -> JSONObject().put("id", id).put("kind", groupKind).put("type", "select")
            .put("ruleOutput", "rule-set").put("ruleSetReferences", JSONArray()).put("policy", JSONObject().put("mode", "proxy"))
            .put("emoji", "").put("icon", "").put("url", "http://www.google.com/generate_204").put("interval", 300)
            .put("tolerance", 50).put("strategy", "consistent-hashing").put("lazy", true)
            .put("filter", JSONObject().put("proxyTypes", JSONArray()).put("namePatterns", JSONArray())).toString()
        ResourceKind.RULES -> JSONObject().put("id", id).put("sourceType", "inline").put("behavior", "classical")
            .put("format", "yaml").put("url", "").put("interval", 86400).put("noResolve", false)
            .put("payloadYaml", "payload:\n  - DOMAIN,example.invalid\n").put("payload", JSONArray()).toString()
    }
    return LocalResource(id, "", kind, content, formatVersion = 2)
}

@Composable
private fun ResourceEditor(draft: LocalResource, state: AppState, model: JeemiViewModel) {
    val scriptInput by model.scriptInput.collectAsState()
    var testProfile by rememberSaveable(draft.id) { mutableStateOf("") }
    var fieldPath by rememberSaveable(draft.id) { mutableStateOf<String?>(null) }
    val filePicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri -> if (uri != null) model.importResourceFile(uri) }
    fun update(next: LocalResource) { model.editingResource.value = next }
    fun editField(field: ConfigField) {
        val content = if (draft.formatVersion >= 2) JSONObject(draft.content) else JSONObject()
        val entry = (content.objects("fields") + content.objects("disabledFields")).firstOrNull { it.optString("path") == field.path }
        model.fieldDraft.value = entry?.optString("valueYaml")?.trimEnd() ?: field.example.trimEnd()
        fieldPath = field.path
    }
    EditorDialog(stringResource(draft.kind.label), state.busy, model::closeResource, help = if (draft.kind == ResourceKind.SCRIPT) ScriptSourceHelp else draft.kind.help, footer = {
        OutlinedButton(onClick = { model.previewResource(testProfile.takeIf { it.isNotBlank() }) },
            enabled = !state.busy && !state.resourceDownloading, modifier = Modifier.testTag("resource-check"),
            shape = MaterialTheme.shapes.small) { Text(stringResource(R.string.check_preview)) }
        Button(onClick = model::saveResource, modifier = Modifier.testTag("resource-save"), enabled = !state.busy && !state.resourceDownloading && draft.name.isNotBlank(), shape = MaterialTheme.shapes.small) { Text(stringResource(R.string.save)) }
    }) {
        Column(Modifier.verticalScroll(rememberScrollState()).padding(bottom = 24.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
            if (state.busy) LinearProgressIndicator(Modifier.fillMaxWidth())
            OutlinedTextField(draft.name, { update(draft.copy(name = it.take(80))) }, Modifier.fillMaxWidth(), enabled = !state.busy,
                label = { Text(stringResource(R.string.name)) }, singleLine = true)
            if (draft.formatVersion >= 2 && draft.kind in listOf(ResourceKind.GROUPS, ResourceKind.RULES)) {
                ResourceEmojiField(JSONObject(draft.content).optString("emoji"), !state.busy) {
                    update(draft.copy(content = JSONObject(draft.content).put("emoji", it).toString()))
                }
            }
            OutlinedTextField(draft.description, { update(draft.copy(description = it.take(500))) }, Modifier.fillMaxWidth(),
                enabled = !state.busy, label = { Text(stringResource(R.string.description)) })
            DraftRelationships(draft, state)
            if (draft.kind in listOf(ResourceKind.CONFIG, ResourceKind.SCRIPT)) {
                CollapsibleCard(stringResource(R.string.resource_package), PackageHelp) {
                    OutlinedButton(onClick = { filePicker.launch(arrayOf("*/*")) }, enabled = !state.busy, shape = MaterialTheme.shapes.small) { Text(stringResource(R.string.import_package)) }
                    OutlinedButton(onClick = model::exportResource, enabled = !state.busy && draft.name.isNotBlank() && (draft.formatVersion >= 2 || draft.kind == ResourceKind.SCRIPT), shape = MaterialTheme.shapes.small) {
                        Text(stringResource(R.string.export_package))
                    }
                }
            }
            if (draft.formatVersion < 2 || draft.kind == ResourceKind.SCRIPT) {
                if (draft.kind != ResourceKind.SCRIPT) FixedChoiceField(stringResource(R.string.composition_strategy), draft.strategy,
                    (if (draft.kind == ResourceKind.CONFIG) listOf("auto", "replace") else listOf("auto", "prepend", "append", "replace"))
                        .map { it to stringResource(strategyLabel(it)) }, !state.busy) { update(draft.copy(strategy = it)) }
                OutlinedTextField(if (draft.kind == ResourceKind.SCRIPT) scriptInput else draft.content,
                    { if (draft.kind == ResourceKind.SCRIPT) model.scriptInput.value = it else update(draft.copy(content = it)) },
                    Modifier.fillMaxWidth(), enabled = !state.busy && !state.resourceDownloading,
                    label = { Text(stringResource(if (draft.kind == ResourceKind.SCRIPT) R.string.script_source_input else R.string.resource_content)) },
                    minLines = if (draft.kind == ResourceKind.SCRIPT && isScriptUrl(scriptInput)) 2 else 12,
                    textStyle = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace))
                if (draft.kind == ResourceKind.SCRIPT && draft.sourceUrl.isNotEmpty() && scriptInput.trim() == draft.sourceUrl)
                    CollapsibleCard(stringResource(R.string.cached_script), ScriptSourceHelp) {
                        Text(draft.content.take(65536), style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace))
                    }
                if (state.resourceDownloading) Row(verticalAlignment = Alignment.CenterVertically) {
                    CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                    TextButton(onClick = model::cancelResourceNetwork, shape = MaterialTheme.shapes.small) { Text(stringResource(R.string.cancel)) }
                }
            } else when (draft.kind) {
                ResourceKind.CONFIG -> {
                    RoutingPlanEditor(draft, state, model)
                    CollapsibleCard(stringResource(R.string.custom_fields), FieldsHelp, initiallyOpen = true) {
                        ConfigFieldTreeEditor(draft, state.catalog, state.busy, model::toggleFields, ::editField)
                    }
                }
                ResourceKind.GROUPS -> StrategyGroupEditor(draft, state, model)
                ResourceKind.RULES -> RuleSetEditor(draft, state, model)
            }
            if (draft.kind in listOf(ResourceKind.CONFIG, ResourceKind.SCRIPT)) {
                CollapsibleCard(stringResource(R.string.test_candidate), ResourceCheckHelp) {
                    ChoiceField(stringResource(R.string.test_profile), testProfile,
                        listOf("" to stringResource(R.string.associated_only)) + state.library.subscriptions.map { it.id to it.name }, !state.busy) { testProfile = it }
                }
            }

        }
    }
    state.catalog.firstOrNull { it.path == fieldPath }?.let {
        FieldEditor(it, draft, state.busy, model) { fieldPath = null }
    }
}
internal fun JSONObject.objects(key: String): List<JSONObject> = optJSONArray(key)?.let { array ->
    List(array.length()) { array.getJSONObject(it) }
} ?: emptyList()
internal fun JSONObject.strings(key: String): List<String> = optJSONArray(key)?.let { array ->
    List(array.length()) { array.getString(it) }
} ?: emptyList()

@Composable
private fun ResourceFacts(row: ResourceLibraryRow) {
    val resource = row.resource
    val facts = when (resource.kind) {
        ResourceKind.SCRIPT -> stringResource(if (resource.sourceUrl.isBlank()) R.string.script_text_source else R.string.script_url_source)
        ResourceKind.CONFIG -> if (resource.formatVersion < 2) "YAML" else stringResource(R.string.config_resource_facts, row.fields, row.references)
        ResourceKind.GROUPS -> if (resource.formatVersion < 2) "YAML" else
            (if (row.groupKind == "selector") row.type else stringResource(when (row.policy) {
                "direct" -> R.string.direction_direct; "reject" -> R.string.direction_reject; else -> R.string.direction_proxy
            })) + " · " + stringResource(R.string.rule_reference_count, row.references)
        ResourceKind.RULES -> if (resource.formatVersion < 2) "YAML" else
            stringResource(if (row.source == "http") R.string.source_http else R.string.source_inline) + " · " + row.behavior +
                if (row.source == "inline") " · " + stringResource(R.string.resource_entry_count, row.entries) else ""
    }
    Text(facts, style = MaterialTheme.typography.labelSmall, maxLines = 1, overflow = TextOverflow.Ellipsis, color = MaterialTheme.colorScheme.onSurfaceVariant)
}
