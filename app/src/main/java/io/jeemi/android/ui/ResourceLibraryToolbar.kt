@file:OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
package io.jeemi.android.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.jeemi.android.R
import io.jeemi.android.data.*
import io.jeemi.android.domain.*
import io.jeemi.android.ui.components.*

internal val BuiltinRulesHelp = HelpContent(R.string.builtin_rules, R.string.help_builtin_rules_purpose,
    R.string.help_builtin_rules_scenarios, R.string.help_builtin_rules_cautions)
internal val ResourceFiltersHelp = HelpContent(R.string.resource_filters, R.string.help_resource_filters_purpose,
    R.string.help_resource_filters_scenarios, R.string.help_resource_filters_cautions)

@Composable
internal fun ResourceCategoryDropdown(value: String, select: (String) -> Unit) {
    var open by rememberSaveable { mutableStateOf(false) }
    val focus = LocalFocusManager.current
    val options = listOf(ResourceKind.SCRIPT, ResourceKind.CONFIG, ResourceKind.GROUPS, ResourceKind.RULES)
        .map { it.name to stringResource(it.label) } + ("CHAIN" to stringResource(R.string.chain_proxies))
    val label = stringResource(R.string.resource_category)
    BoxWithConstraints(Modifier.fillMaxWidth().padding(horizontal = 16.dp)) {
        val menuWidth = maxWidth
        OutlinedButton(onClick = { focus.clearFocus(); open = true }, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)
            .testTag("resource-category-menu").semantics { contentDescription = label },
            contentPadding = PaddingValues(horizontal = 12.dp, vertical = 2.dp), shape = MaterialTheme.shapes.small) {
            Icon(Icons.Outlined.FolderOpen, null, Modifier.size(19.dp))
            Spacer(Modifier.width(10.dp))
            Text(options.first { it.first == value }.second, Modifier.weight(1f),
                style = MaterialTheme.typography.titleSmall, maxLines = 2, overflow = TextOverflow.Ellipsis)
            Icon(if (open) Icons.Outlined.ExpandLess else Icons.Outlined.ExpandMore, null, Modifier.size(18.dp))
        }
        DropdownMenu(open, { open = false }, modifier = Modifier.width(menuWidth).heightIn(max = 360.dp)) {
            options.forEach { (key, title) ->
                DropdownMenuItem(text = { Text(title) }, modifier = Modifier.testTag("resource-category-$key").semantics { selected = key == value },
                    trailingIcon = { if (key == value) Icon(Icons.Outlined.Check, null, Modifier.size(18.dp)) },
                    onClick = { open = false; if (key != value) select(key) })
            }
        }
    }
}

@Composable
internal fun ResourceLibraryToolbar(kind: ResourceKind, query: String, search: (String) -> Unit,
    filtered: Boolean, filtersOpen: Boolean, toggleFilters: () -> Unit, busy: Boolean,
    create: () -> Unit, builtins: () -> Unit) {
    val label = stringResource(R.string.search_resources)
    val focus = LocalFocusManager.current
    val supportsFilters = kind == ResourceKind.GROUPS || kind == ResourceKind.RULES
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        BasicTextField(query, search, singleLine = true, textStyle = MaterialTheme.typography.bodyMedium.copy(color = MaterialTheme.colorScheme.onSurface),
            keyboardOptions = KeyboardOptions(autoCorrectEnabled = false, imeAction = ImeAction.Search),
            keyboardActions = KeyboardActions(onSearch = { focus.clearFocus() }), cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
            modifier = Modifier.weight(1f).heightIn(min = 48.dp).testTag("resource-search").semantics { contentDescription = label },
            decorationBox = { input ->
                Row(Modifier.background(MaterialTheme.colorScheme.surfaceContainer, MaterialTheme.shapes.small)
                    .padding(start = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Outlined.Search, null, Modifier.size(18.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
                    Spacer(Modifier.width(6.dp))
                    Box(Modifier.weight(1f).padding(vertical = 8.dp)) {
                        if (query.isEmpty()) Text(label, maxLines = 1, overflow = TextOverflow.Ellipsis,
                            style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        input()
                    }
                    if (query.isNotEmpty()) IconButton(onClick = { search("") }) {
                        Icon(Icons.Outlined.Close, stringResource(R.string.clear_resource_search), Modifier.size(18.dp))
                    } else Spacer(Modifier.width(10.dp))
                }
            })
        if (supportsFilters) IconToggleButton(checked = filtersOpen, onCheckedChange = { focus.clearFocus(); toggleFilters() },
            modifier = Modifier.testTag("resource-filters-toggle")) {
            BadgedBox(badge = { if (filtered) Badge() }) {
                Icon(Icons.Outlined.FilterList, stringResource(R.string.resource_filters), Modifier.size(19.dp))
            }
        }
        if (kind == ResourceKind.CONFIG) IconButton(onClick = builtins, enabled = !busy, modifier = Modifier.testTag("builtin-rules")) {
            Icon(Icons.Outlined.AutoAwesome, stringResource(R.string.builtin_rules), Modifier.size(19.dp))
        }
        FeatureHelp(if (supportsFilters && filtersOpen) ResourceFiltersHelp else kind.help)
        IconButton(onClick = create, enabled = !busy, modifier = Modifier.testTag("resource-create")) {
            Icon(Icons.Outlined.Add, stringResource(R.string.new_resource), Modifier.size(20.dp), tint = MaterialTheme.colorScheme.primary)
        }
    }
}

@Composable
internal fun ResourceFilterControls(kind: ResourceKind, rows: List<ResourceLibraryRow>, enabled: Boolean,
    config: String, setConfig: (String) -> Unit, category: String, setCategory: (String) -> Unit,
    groups: List<String>, setGroups: (List<String>) -> Unit) {
    val configs = rows.filter { it.resource.kind == ResourceKind.CONFIG }
    val eligibleGroups = rows.filter { it.resource.kind == ResourceKind.GROUPS &&
        (config.isEmpty() || if (config == UNLINKED_RESOURCES) it.configurations.isEmpty() else config in it.configurations) }
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        FlowRow(Modifier.weight(1f), horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(0.dp)) {
            CompactFilterMenu(label = when (config) {
                ALL_RESOURCES -> stringResource(R.string.all_resource_configs)
                UNLINKED_RESOURCES -> stringResource(R.string.unlinked_resource_config)
                else -> configs.firstOrNull { it.resource.id == config }?.resource?.name ?: stringResource(R.string.resource_missing)
            }, tag = "resource-config-filter", enabled = enabled,
                options = listOf(ALL_RESOURCES to stringResource(R.string.all_resource_configs), UNLINKED_RESOURCES to stringResource(R.string.unlinked_resource_config)) +
                    configs.map { it.resource.id to it.resource.name }, selectedIds = listOf(config), select = setConfig)
            if (kind == ResourceKind.RULES) CompactFilterMenu(label = if (groups.isEmpty()) stringResource(R.string.all_resource_groups)
                else if (groups.size == 1) if (groups.single() == UNLINKED_RESOURCES) stringResource(R.string.unlinked_resource_group)
                    else eligibleGroups.firstOrNull { it.resource.id == groups.single() }?.resource?.name ?: stringResource(R.string.resource_missing)
                else stringResource(R.string.filtered_group_count, groups.size),
                tag = "resource-group-filter", enabled = enabled,
                options = listOf(ALL_RESOURCES to stringResource(R.string.all_resource_groups), UNLINKED_RESOURCES to stringResource(R.string.unlinked_resource_group)) +
                    eligibleGroups.map { it.resource.id to listOf(it.emoji, it.resource.name).filter(String::isNotBlank).joinToString(" ") },
                selectedIds = groups.ifEmpty { listOf(ALL_RESOURCES) }, multiple = true) { id ->
                    setGroups(if (id.isEmpty()) emptyList() else if (id in groups) groups - id else groups + id)
                }
        }
        if (config.isNotEmpty() || category.isNotEmpty() || groups.isNotEmpty()) IconButton(onClick = {
            setConfig(ALL_RESOURCES); setCategory(ALL_RESOURCES); setGroups(emptyList())
        }) { Icon(Icons.Outlined.FilterAltOff, stringResource(R.string.reset_resource_filters), Modifier.size(18.dp)) }
    }
    if (kind == ResourceKind.GROUPS) SegmentedChoice(stringResource(R.string.group_category), category,
        listOf(ALL_RESOURCES to stringResource(R.string.all_resource_categories), "selector" to stringResource(R.string.group_selector), "rule" to stringResource(R.string.group_rule)),
        enabled = enabled, showLabel = false, optionTagPrefix = "resource-category-", select = setCategory)
}

@Composable
private fun CompactFilterMenu(label: String, tag: String, enabled: Boolean, options: List<Pair<String, String>>,
    selectedIds: List<String>, multiple: Boolean = false, select: (String) -> Unit) {
    var open by rememberSaveable { mutableStateOf(false) }
    Box {
        TextButton(onClick = { open = true }, enabled = enabled, shape = MaterialTheme.shapes.small,
            contentPadding = PaddingValues(horizontal = 8.dp, vertical = 2.dp),
            modifier = Modifier.widthIn(max = 220.dp).heightIn(min = 48.dp).testTag(tag)) {
            Text(label, maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.labelMedium,
                modifier = Modifier.weight(1f, fill = false))
            Icon(Icons.Outlined.ExpandMore, null, Modifier.size(16.dp))
        }
        DropdownMenu(open, { open = false }, modifier = Modifier.heightIn(max = 360.dp).widthIn(max = 300.dp)) {
            options.forEach { (id, name) ->
                DropdownMenuItem(text = { Text(name, maxLines = 2, overflow = TextOverflow.Ellipsis) },
                    modifier = Modifier.semantics { selected = id in selectedIds },
                    leadingIcon = { if (id in selectedIds) Icon(Icons.Outlined.Check, null, Modifier.size(18.dp)) else Spacer(Modifier.size(18.dp)) },
                    onClick = { select(id); if (!multiple) open = false })
            }
        }
    }
}

@Composable
internal fun BuiltinRulesDialog(busy: Boolean, dismiss: () -> Unit, create: (String) -> Unit) {
    AlertDialog(onDismissRequest = dismiss, title = { SectionTitle(R.string.builtin_rules, BuiltinRulesHelp) },
        text = { Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            listOf("general" to R.string.builtin_general_name, "apps" to R.string.builtin_apps_name).forEach { (key, label) ->
                OutlinedButton(onClick = { create(key) }, enabled = !busy, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp).testTag("builtin-$key"),
                    shape = MaterialTheme.shapes.small) {
                    Text(stringResource(label), Modifier.weight(1f))
                    Icon(Icons.Outlined.Add, null, Modifier.size(18.dp))
                }
            }
        } }, confirmButton = { TextButton(onClick = dismiss, shape = MaterialTheme.shapes.small) { Text(stringResource(R.string.close)) } })
}
