@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
package io.jeemi.android.ui
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.horizontalScroll
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
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import io.jeemi.android.R
import io.jeemi.android.domain.*
import io.jeemi.android.ui.components.*
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

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
    var deleteId by rememberSaveable { mutableStateOf<String?>(null) }
    var chooseGroupKind by rememberSaveable { mutableStateOf(false) }
    val editing by model.editingResource.collectAsState()
    Column(modifier) {
        Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            listOf(ResourceKind.CONFIG, ResourceKind.SCRIPT, ResourceKind.GROUPS, ResourceKind.RULES).forEach { option ->
                FilterChip(!chainPage && kind == option, { chainPage = false; kind = option }, label = { Text(stringResource(option.label)) })
            }
            FilterChip(chainPage, { chainPage = true }, label = { Text(stringResource(R.string.chain_proxies)) })
        }
    if (chainPage) ChainProxyScreen(state, model, Modifier.weight(1f)) else
    LazyColumn(Modifier.weight(1f), contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        item {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(stringResource(R.string.saved_resources, state.library.resources.count { it.kind == kind }),
                    Modifier.weight(1f), style = MaterialTheme.typography.labelMedium)
                FilledTonalButton(onClick = {
                    if (kind == ResourceKind.GROUPS) chooseGroupKind = true else model.openResource(newResource(kind))
                }, enabled = !state.busy, shape = MaterialTheme.shapes.small) { Icon(Icons.Outlined.Add, null, Modifier.size(18.dp)); Text(stringResource(R.string.new_resource)) }
            }
            OutlinedTextField(query, { query = it }, Modifier.fillMaxWidth(), label = { Text(stringResource(R.string.name)) },
                leadingIcon = { Icon(Icons.Outlined.Search, null) }, singleLine = true)
        }
        val resources = state.library.resources.filter { it.kind == kind && it.name.contains(query, true) }
        if (resources.isEmpty()) item { EmptyCard(stringResource(if (query.isBlank()) R.string.no_resources else R.string.no_results), kind.help) }
        items(resources, key = { it.id }) { item ->
            JeemiCard(Modifier.fillMaxWidth()) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(item.name, style = MaterialTheme.typography.titleMedium)
                        val count = state.library.subscriptions.count { subscription ->
                            subscription.handlerId == item.id || item.id in subscription.resourceIds ||
                                state.library.resources.any { it.id == subscription.handlerId && it.content.contains(item.id) }
                        }
                        Text(stringResource(R.string.associated_count, count), style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    if (item.kind == ResourceKind.SCRIPT && item.sourceUrl.isNotEmpty())
                        IconButton(onClick = { model.refreshScript(item.id) }, enabled = !state.busy && !state.resourceDownloading) {
                            Icon(Icons.Outlined.Refresh, stringResource(R.string.redownload_script))
                        }
                    IconButton(onClick = { model.openResource(item) }, enabled = !state.busy && !state.resourceDownloading) { Icon(Icons.Outlined.Edit, stringResource(R.string.edit)) }
                    IconButton(onClick = { deleteId = item.id }, enabled = !state.busy) { Icon(Icons.Outlined.DeleteOutline, stringResource(R.string.delete)) }
                }
                if (item.description.isNotBlank()) Text(item.description, style = MaterialTheme.typography.bodySmall)
            }
        }
    }
    }
    if (chooseGroupKind) AlertDialog(onDismissRequest = { chooseGroupKind = false }, title = { SectionTitle(R.string.group_category, GroupsHelp) },
        text = { Column {
            listOf("selector" to R.string.group_selector, "rule" to R.string.group_rule).forEach { (value, label) ->
                OutlinedButton(onClick = { chooseGroupKind = false; model.openResource(newResource(ResourceKind.GROUPS, value)) }, modifier = Modifier.fillMaxWidth(), shape = MaterialTheme.shapes.small) { Text(stringResource(label)) }
            }
        } }, confirmButton = { TextButton(onClick = { chooseGroupKind = false }, shape = MaterialTheme.shapes.small) { Text(stringResource(R.string.cancel)) } })
    editing?.let { ResourceEditor(it, state.copy(busy = state.busy || state.resourceDownloading), model) }
    deleteId?.let { id -> AlertDialog(onDismissRequest = { deleteId = null }, title = { Text(stringResource(R.string.delete_resource)) },
        text = { Text(stringResource(R.string.delete_resource_note)) }, dismissButton = { TextButton(onClick = { deleteId = null }, shape = MaterialTheme.shapes.small) { Text(stringResource(R.string.cancel)) } },
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
    var testProfile by rememberSaveable(draft.id) { mutableStateOf(state.library.selectedId ?: "") }
    var fieldPicker by rememberSaveable(draft.id) { mutableStateOf(false) }
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
        Button(onClick = model::saveResource, enabled = !state.busy && !state.resourceDownloading && draft.name.isNotBlank(), shape = MaterialTheme.shapes.small) { Text(stringResource(R.string.save)) }
    }) {
        Column(Modifier.verticalScroll(rememberScrollState()).padding(bottom = 24.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
            OutlinedTextField(draft.name, { update(draft.copy(name = it.take(80))) }, Modifier.fillMaxWidth(), enabled = !state.busy,
                label = { Text(stringResource(R.string.name)) }, singleLine = true)
            OutlinedTextField(draft.description, { update(draft.copy(description = it.take(500))) }, Modifier.fillMaxWidth(),
                enabled = !state.busy, label = { Text(stringResource(R.string.description)) })
            if (draft.kind in listOf(ResourceKind.CONFIG, ResourceKind.SCRIPT)) {
                CollapsibleCard(stringResource(R.string.resource_package), PackageHelp) {
                    OutlinedButton(onClick = { filePicker.launch(arrayOf("*/*")) }, enabled = !state.busy, shape = MaterialTheme.shapes.small) { Text(stringResource(R.string.import_package)) }
                    OutlinedButton(onClick = model::exportResource, enabled = !state.busy && draft.name.isNotBlank() && (draft.formatVersion >= 2 || draft.kind == ResourceKind.SCRIPT), shape = MaterialTheme.shapes.small) {
                        Text(stringResource(R.string.export_package))
                    }
                }
            }
            if (draft.formatVersion < 2 || draft.kind == ResourceKind.SCRIPT) {
                if (draft.kind != ResourceKind.SCRIPT) ChoiceField(stringResource(R.string.composition_strategy), draft.strategy,
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
                        val content = JSONObject(draft.content)
                        val disabled = content.objects("disabledFields").map { it.getString("path") }.toSet()
                        (content.objects("fields") + content.objects("disabledFields")).sortedBy { it.getString("path") }.forEach { entry ->
                            val path = entry.getString("path")
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                TextButton(onClick = { state.catalog.firstOrNull { it.path == path }?.let(::editField) },
                                    modifier = Modifier.weight(1f), enabled = !state.busy, shape = MaterialTheme.shapes.small) {
                                    Column(Modifier.fillMaxWidth()) {
                                        Text(path.removePrefix("/").replace('/', '.'), style = MaterialTheme.typography.bodyMedium)
                                        Text(stringResource(if (path in disabled) R.string.field_disabled else R.string.field_active), style = MaterialTheme.typography.labelSmall)
                                    }
                                }
                                IconButton(onClick = { model.removeField(path) }, enabled = !state.busy) { Icon(Icons.Outlined.DeleteOutline, stringResource(R.string.delete)) }
                            }
                        }
                        OutlinedButton(onClick = { fieldPicker = true }, enabled = !state.busy, modifier = Modifier.fillMaxWidth(), shape = MaterialTheme.shapes.small) { Text(stringResource(R.string.add_field)) }
                    }
                }
                ResourceKind.GROUPS -> StrategyGroupEditor(draft, state, model)
                ResourceKind.RULES -> RuleSetEditor(draft, state, model)
            }
            if (draft.kind in listOf(ResourceKind.CONFIG, ResourceKind.SCRIPT)) {
                CollapsibleCard(stringResource(R.string.test_candidate), if (draft.kind == ResourceKind.SCRIPT) ScriptHelp else PreviewHelp) {
                    ChoiceField(stringResource(R.string.test_profile), testProfile, state.library.subscriptions.map { it.id to it.name }, !state.busy) { testProfile = it }
                    OutlinedButton(onClick = { model.previewResource(testProfile) }, enabled = !state.busy && testProfile.isNotBlank(), shape = MaterialTheme.shapes.small) { Text(stringResource(R.string.test_candidate)) }
                }
            }
        }
    }
    if (fieldPicker) FieldPicker(state.catalog, { fieldPicker = false }) { fieldPicker = false; editField(it) }
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
