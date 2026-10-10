package io.jeemi.android.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.KeyboardArrowRight
import androidx.compose.material.icons.outlined.KeyboardArrowDown
import androidx.compose.material.icons.outlined.Lock
import androidx.compose.material.icons.outlined.Remove
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.semantics.text
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.style.LineBreak
import androidx.compose.ui.unit.dp
import io.jeemi.android.R
import io.jeemi.android.data.configFields
import io.jeemi.android.domain.*
import io.jeemi.android.ui.components.FeatureHelp
import io.jeemi.android.ui.components.JeemiSwitch
import org.json.JSONObject

@Composable
internal fun ConfigFieldTreeEditor(draft: LocalResource, catalog: List<ConfigField>, busy: Boolean,
    toggle: (List<ConfigField>, Boolean) -> Unit, edit: (ConfigField) -> Unit) {
    var query by rememberSaveable(draft.id) { mutableStateOf("") }
    var onlyEnabled by rememberSaveable(draft.id) { mutableStateOf(false) }
    var showLocked by rememberSaveable(draft.id) { mutableStateOf(false) }
    var expanded by rememberSaveable(draft.id) { mutableStateOf(emptyList<String>()) }
    val content = remember(draft.content) { JSONObject(draft.content) }
    val enabledPaths = content.configFields("fields").map { it.getString("path") }.toSet()
    val configured = enabledPaths + content.configFields("disabledFields").map { it.getString("path") }
    val tree = remember(catalog) { configFieldTree(catalog) }
    val resources = LocalResources.current
    val locale = LocalConfiguration.current.locales.toLanguageTags()
    val descriptions = remember(catalog, locale) {
        (catalog.map { it.path } + FieldTreeBranchHelp.keys).associateWith { path ->
            val help = fieldHelp(path)
            listOf(help.title, help.purpose, help.scenarios).joinToString(" ") { resources.getString(it) }
        }
    }
    val filtered = filterConfigFieldTree(tree, enabledPaths, onlyEnabled, showLocked, query, descriptions)
    LaunchedEffect(query, onlyEnabled, showLocked) {
        if (query.isNotBlank() || onlyEnabled) {
            fun branches(nodes: List<ConfigFieldNode>): List<String> = nodes.flatMap {
                if (it.children.isEmpty()) emptyList() else listOf(it.path) + branches(it.children)
            }
            expanded = (expanded + branches(filtered)).distinct()
        }
    }
    OutlinedTextField(query, { query = it }, Modifier.fillMaxWidth().testTag("field-tree-search"),
        singleLine = true, label = { Text(stringResource(R.string.search_fields)) },
        keyboardOptions = KeyboardOptions(autoCorrectEnabled = false))
    Box(Modifier.testTag("field-filter-enabled")) {
        SwitchRow(stringResource(R.string.fields_only_enabled), onlyEnabled) { onlyEnabled = it }
    }
    Box(Modifier.testTag("field-filter-locked")) {
        SwitchRow(stringResource(R.string.fields_show_locked), showLocked) { showLocked = it }
    }
    if (filtered.isEmpty()) Text(stringResource(R.string.no_results), style = MaterialTheme.typography.bodyMedium)
    fun expand(path: String) { expanded = if (path in expanded) expanded - path else expanded + path }
    filtered.forEach { node -> key(node.path) {
        FieldTreeRow(node, catalog, enabledPaths, configured, expanded,
            0, busy, ::expand, toggle, edit)
    } }
}

@Composable
private fun FieldTreeRow(node: ConfigFieldNode, catalog: List<ConfigField>, enabledPaths: Set<String>,
    configured: Set<String>, expanded: List<String>, depth: Int, busy: Boolean,
    expand: (String) -> Unit, toggle: (List<ConfigField>, Boolean) -> Unit, edit: (ConfigField) -> Unit) {
    val branch = node.children.isNotEmpty()
    val descendants = catalog.filter { it.path == node.path || it.path.startsWith("${node.path}/") }
    val editable = descendants.filterNot { it.locked || it.hidden }
    val targets = if (branch) editable.filter { it.path in configured } else editable
    val active = editable.count { it.path in enabledPaths }
    val partial = active > 0 && active < targets.size
    val locked = editable.isEmpty()
    val open = node.path in expanded
    val label = if (node.label == "*") stringResource(R.string.fields_all_proxies) else node.label
    val fullPath = node.path.removePrefix("/").replace('/', '.')
    val toggleLabel = stringResource(R.string.field_injection_for, fullPath)
    val stateLabel = stringResource(when {
        locked -> R.string.field_locked
        partial -> R.string.fields_partly_enabled
        active > 0 -> R.string.field_active
        else -> R.string.field_disabled
    })
    Row(Modifier.fillMaxWidth().padding(start = (depth.coerceAtMost(3) * 8).dp)
        .background(if (active > 0) MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.35f)
            else MaterialTheme.colorScheme.surface, MaterialTheme.shapes.small)
        .heightIn(min = 48.dp).testTag("field-row-${node.path}"), verticalAlignment = Alignment.CenterVertically) {
        JeemiSwitch(checked = active > 0, onCheckedChange = { toggle(targets, it) },
            enabled = !busy && !locked && targets.isNotEmpty(),
            modifier = Modifier.sizeIn(minWidth = 52.dp, minHeight = 48.dp).semantics {
                contentDescription = toggleLabel; stateDescription = stateLabel
            }.testTag("field-toggle-${node.path}"),
            thumbContent = if (locked || partial) {{ Icon(if (locked) Icons.Outlined.Lock else Icons.Outlined.Remove,
                null, Modifier.size(16.dp)) }} else null)
        Spacer(Modifier.width(8.dp))
        Row(Modifier.weight(1f).heightIn(min = 48.dp)
            .clickable(enabled = branch || (!busy && !locked)) { if (branch) expand(node.path) else node.field?.let(edit) }
            .testTag("field-name-${node.path}"), verticalAlignment = Alignment.CenterVertically) {
            if (branch) Icon(if (open) Icons.Outlined.KeyboardArrowDown else Icons.AutoMirrored.Outlined.KeyboardArrowRight,
                stringResource(if (open) R.string.collapse else R.string.expand), Modifier.size(18.dp))
            else Spacer(Modifier.width(18.dp))
            // Prefer identifier separators when wrapping, while accessibility keeps the exact field name.
            Text(label.replace("-", "-\u200B"), Modifier.weight(1f).clearAndSetSemantics { text = AnnotatedString(label) },
                style = MaterialTheme.typography.bodyMedium.copy(lineBreak = LineBreak.Heading),
                color = if (locked) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.onSurface)
        }
        FeatureHelp(localFieldHelp(node.path, node.field), title = fullPath)
    }
    if (branch && open) node.children.forEach { child -> key(child.path) {
        FieldTreeRow(child, catalog, enabledPaths, configured, expanded, depth + 1, busy, expand, toggle, edit)
    } }
}
