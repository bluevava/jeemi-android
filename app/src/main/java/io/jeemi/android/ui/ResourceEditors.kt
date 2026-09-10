@file:OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
package io.jeemi.android.ui
import androidx.compose.foundation.clickable
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
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import io.jeemi.android.R
import io.jeemi.android.domain.*
import io.jeemi.android.ui.components.*
import org.json.JSONArray
import org.json.JSONObject

@Composable
internal fun RoutingPlanEditor(draft: LocalResource, state: AppState, model: JeemiViewModel) {
    val content = JSONObject(draft.content)
    val plan = content.getJSONObject("resourcePlan")
    val ids = plan.strings("strategyGroupIds")
    val disabled = plan.strings("disabledStrategyGroupIds")
    val groups = state.library.resources.filter { it.kind == ResourceKind.GROUPS && it.formatVersion >= 2 }
    val selectors = groups.filter { JSONObject(it.content).optString("kind") == "selector" }
    fun update(key: String, value: Any) {
        val next = JSONObject(plan.toString()).put(key, value)
        if (key == "strategyGroupIds") next.put("disabledStrategyGroupIds", JSONArray(disabled.filter { it in next.strings(key) }))
        model.editingResource.value = draft.copy(content = JSONObject(content.toString()).put("resourcePlan", next).toString())
    }
    CollapsibleCard(stringResource(R.string.local_routing), RoutingHelp) {
        SwitchRow(stringResource(R.string.local_rules_enabled), !plan.optBoolean("rulesDisabled"), !state.busy) { update("rulesDisabled", !it) }
        ChoiceField(stringResource(R.string.composition_strategy), plan.optString("ruleStrategy"),
            listOf("append_before_terminal" to stringResource(R.string.strategy_append_before_terminal),
                "prepend" to stringResource(R.string.strategy_prepend), "replace" to stringResource(R.string.strategy_rebuild)), !state.busy) { update("ruleStrategy", it) }
        SectionTitle(R.string.routing_groups, RoutingHelp)
        ids.forEachIndexed { index, id ->
            val group = groups.firstOrNull { it.id == id }
            Column {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(id !in disabled, { enabled -> update("disabledStrategyGroupIds", JSONArray(if (enabled) disabled - id else disabled + id)) }, enabled = !state.busy)
                    Text(group?.name ?: id, Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
                    IconButton(onClick = { update("strategyGroupIds", JSONArray(ids - id)) }, enabled = !state.busy) {
                        Icon(Icons.Outlined.Close, stringResource(R.string.remove_reference))
                    }
                }
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                    IconButton(onClick = { update("strategyGroupIds", JSONArray(ids.moved(index, -1))) }, enabled = !state.busy && index > 0) { Icon(Icons.Outlined.ArrowUpward, stringResource(R.string.move_up)) }
                    IconButton(onClick = { update("strategyGroupIds", JSONArray(ids.moved(index, 1))) }, enabled = !state.busy && index < ids.lastIndex) { Icon(Icons.Outlined.ArrowDownward, stringResource(R.string.move_down)) }
                }
            }
        }
        if (groups.any { it.id !in ids }) ChoiceField(stringResource(R.string.add_group), "", groups.filter { it.id !in ids }.map { it.id to it.name }, !state.busy) {
            update("strategyGroupIds", JSONArray(ids + it))
        }
        ChoiceField(stringResource(R.string.routing_proxy_target), plan.optString("defaultProxySelectorId"),
            listOf("" to stringResource(R.string.none_items)) + selectors.map { it.id to it.name }, !state.busy) { update("defaultProxySelectorId", it) }
        val match = plan.optJSONObject("match") ?: JSONObject().put("mode", "none").put("selectorId", "")
        ChoiceField(stringResource(R.string.local_match), match.optString("mode"), listOf("none" to stringResource(R.string.keep_original),
            "direct" to stringResource(R.string.mode_direct), "selector" to stringResource(R.string.target_selector)), !state.busy) {
            update("match", JSONObject(match.toString()).put("mode", it).apply { if (it != "selector") put("selectorId", "") })
        }
        if (match.optString("mode") == "selector") ChoiceField(stringResource(R.string.target_selector), match.optString("selectorId"),
            selectors.filter { it.id in ids && it.id !in disabled }.map { it.id to it.name }, !state.busy) {
            update("match", JSONObject(match.toString()).put("selectorId", it))
        }
    }
}

@Composable
internal fun StrategyGroupEditor(draft: LocalResource, state: AppState, model: JeemiViewModel) {
    val content = JSONObject(draft.content)
    fun update(key: String, value: Any) { model.editingResource.value = draft.copy(content = JSONObject(content.toString()).put(key, value).toString()) }
    val selector = content.getString("kind") == "selector"
    SectionTitle(if (selector) R.string.group_selector else R.string.group_rule, GroupsHelp)
    if (selector) {
        ChoiceField(stringResource(R.string.selector_type), content.optString("type"),
            listOf("select", "url-test", "fallback", "load-balance").map { it to it }, !state.busy) { update("type", it) }
        ChoiceField(stringResource(R.string.selector_emoji), content.optString("emoji"),
            listOf("" to stringResource(R.string.none_items)) + listOf("🌐", "🚀", "⚡", "🛡️", "🔒", "🎮", "🎬", "🎵", "💬", "💻", "📱", "☁️", "🏠", "⭐", "🔥", "💎", "🌟", "🌈", "🍀", "🐱", "🦊", "🐼", "🐬").map { it to it }, !state.busy) { update("emoji", it) }
        JsonText(content, "icon", R.string.selector_icon_url, !state.busy, change = ::update)
        val filter = content.optJSONObject("filter") ?: JSONObject()
        fun filterUpdate(key: String, value: Any) = update("filter", JSONObject(filter.toString()).put(key, value))
        OutlinedTextField(filter.strings("namePatterns").joinToString("\n"), { filterUpdate("namePatterns", JSONArray(it.split('\n'))) },
            Modifier.fillMaxWidth(), label = { Text(stringResource(R.string.filter_names)) }, minLines = 3, enabled = !state.busy)
        CollapsibleCard(stringResource(R.string.filter_protocols), GroupsHelp) {
            val chosen = filter.strings("proxyTypes")
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                (listOf("ss", "ssr", "vmess", "vless", "trojan", "hysteria", "hysteria2", "tuic", "wireguard", "ssh", "socks5", "http", "anytls") + chosen).distinct().forEach { protocol ->
                    FilterChip(protocol in chosen, { filterUpdate("proxyTypes", JSONArray(if (protocol in chosen) chosen - protocol else chosen + protocol)) },
                        label = { Text(protocol) }, enabled = !state.busy)
                }
            }
        }
        if (content.optString("type") != "select") CollapsibleCard(stringResource(R.string.test_nodes), GroupsHelp, initiallyOpen = true) {
            JsonText(content, "url", R.string.test_url, !state.busy, change = ::update)
            JsonText(content, "interval", R.string.interval_seconds, !state.busy, number = true, change = ::update)
            if (content.optString("type") == "url-test") JsonText(content, "tolerance", R.string.tolerance_ms, !state.busy, number = true, change = ::update)
            if (content.optString("type") == "load-balance") ChoiceField(stringResource(R.string.balance_strategy), content.optString("strategy"),
                listOf("consistent-hashing", "round-robin", "sticky-sessions").map { it to it }, !state.busy) { update("strategy", it) }
            SwitchRow(stringResource(R.string.lazy_test), content.optBoolean("lazy", true), !state.busy) { update("lazy", it) }
        }
    } else {
        ChoiceField(stringResource(R.string.rule_direction), content.optJSONObject("policy")?.optString("mode") ?: "proxy",
            listOf("proxy" to stringResource(R.string.direction_proxy), "direct" to stringResource(R.string.direction_direct),
                "reject" to stringResource(R.string.direction_reject)), !state.busy) { update("policy", JSONObject().put("mode", it)) }
    }
    CollapsibleCard(stringResource(R.string.rule_set_references), RulesHelp, initiallyOpen = !selector) {
        ChoiceField(stringResource(R.string.rule_output), content.optString("ruleOutput"),
            listOf("rule-set" to stringResource(R.string.output_provider), "inline" to stringResource(R.string.output_inline)), !state.busy) { update("ruleOutput", it) }
        val refs = content.objects("ruleSetReferences").map { it.getString("ruleSetId") }
        fun setRefs(ids: List<String>) { update("ruleSetReferences", JSONArray().apply { ids.forEach { put(JSONObject().put("ruleSetId", it)) } }) }
        val rules = state.library.resources.filter { it.kind == ResourceKind.RULES && it.formatVersion >= 2 }
        val owned = state.library.resources.filter { it.kind == ResourceKind.GROUPS && it.formatVersion >= 2 && it.id != draft.id }
            .flatMap { JSONObject(it.content).objects("ruleSetReferences") }.map { it.getString("ruleSetId") }.toSet()
        refs.forEachIndexed { index, id ->
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(rules.firstOrNull { it.id == id }?.name ?: id, Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
                IconButton(onClick = { setRefs(refs.moved(index, -1)) }, enabled = !state.busy && index > 0) { Icon(Icons.Outlined.ArrowUpward, stringResource(R.string.move_up)) }
                IconButton(onClick = { setRefs(refs.moved(index, 1)) }, enabled = !state.busy && index < refs.lastIndex) { Icon(Icons.Outlined.ArrowDownward, stringResource(R.string.move_down)) }
                IconButton(onClick = { setRefs(refs - id) }, enabled = !state.busy) { Icon(Icons.Outlined.Close, stringResource(R.string.remove_reference)) }
            }
        }
        rules.filter { it.id !in refs }.forEach { rule ->
            val available = rule.id !in owned && (content.optString("ruleOutput") != "inline" || JSONObject(rule.content).optString("sourceType") == "inline")
            Row(verticalAlignment = Alignment.CenterVertically) {
                Checkbox(false, { setRefs(refs + rule.id) }, enabled = available && !state.busy)
                Column(Modifier.weight(1f)) {
                    Text(rule.name, style = MaterialTheme.typography.bodyMedium)
                    if (rule.id in owned) Text(stringResource(R.string.reference_owned), style = MaterialTheme.typography.labelSmall)
                }
            }
        }
        if (rules.isEmpty()) Text(stringResource(R.string.no_resources), style = MaterialTheme.typography.bodySmall)
    }
}

@Composable
internal fun RuleSetEditor(draft: LocalResource, state: AppState, model: JeemiViewModel) {
    val content = JSONObject(draft.content)
    fun update(key: String, value: Any) { model.editingResource.value = draft.copy(content = JSONObject(content.toString()).put(key, value).toString()) }
    SectionTitle(R.string.rule_sets, RulesHelp)
    ChoiceField(stringResource(R.string.source_type), content.optString("sourceType"),
        listOf("inline" to stringResource(R.string.source_inline), "http" to stringResource(R.string.source_http)), !state.busy) { update("sourceType", it) }
    ChoiceField(stringResource(R.string.rule_behavior), content.optString("behavior"),
        listOf("classical", "domain", "ipcidr").map { it to it }, !state.busy) { update("behavior", it) }
    if (content.optString("sourceType") == "http") {
        JsonText(content, "url", R.string.resource_url, !state.busy, change = ::update)
        ChoiceField(stringResource(R.string.rule_format), content.optString("format"),
            (if (content.optString("behavior") == "classical") listOf("yaml", "text") else listOf("yaml", "text", "mrs")).map { it to it }, !state.busy) { update("format", it) }
        JsonText(content, "interval", R.string.interval_seconds, !state.busy, number = true, change = ::update)
    } else OutlinedTextField(content.optString("payloadYaml"), { update("payloadYaml", it) }, Modifier.fillMaxWidth(),
        label = { Text(stringResource(R.string.rule_payload)) }, minLines = 12, enabled = !state.busy,
        textStyle = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace))
    SwitchRow(stringResource(R.string.no_resolve), content.optBoolean("noResolve"), !state.busy) { update("noResolve", it) }
}

@Composable
private fun JsonText(content: JSONObject, key: String, label: Int, enabled: Boolean, number: Boolean = false, change: (String, Any) -> Unit) {
    OutlinedTextField(content.optString(key), { change(key, if (number) it.toIntOrNull() ?: it else it) }, Modifier.fillMaxWidth(),
        label = { Text(stringResource(label)) }, singleLine = true, enabled = enabled)
}
private fun <T> List<T>.moved(index: Int, step: Int) = toMutableList().apply {
    if (index in indices && index + step in indices) add(index + step, removeAt(index))
}

@Composable
internal fun FieldPicker(fields: List<ConfigField>, dismiss: () -> Unit, select: (ConfigField) -> Unit) {
    var query by rememberSaveable { mutableStateOf("") }
    var category by rememberSaveable { mutableStateOf("general") }
    EditorDialog(stringResource(R.string.add_field), false, dismiss, help = FieldsHelp) {
        OutlinedTextField(query, { query = it }, Modifier.fillMaxWidth(), singleLine = true, label = { Text(stringResource(R.string.search_fields)) })
        Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            fields.filterNot { it.hidden }.map { it.category }.distinct().forEach {
                FilterChip(category == it, { category = it }, label = { Text(stringResource(categoryLabel(it))) })
            }
        }
        LazyColumn(contentPadding = PaddingValues(vertical = 14.dp)) {
            items(fields.filter { !it.hidden && if (query.isNotBlank()) it.path.contains(query, true) else it.category == category }.sortedBy { it.path }, key = { it.path }) { field ->
                Row(Modifier.fillMaxWidth().heightIn(min = 56.dp).clickable(enabled = !field.locked) { select(field) },
                    verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f).padding(start = ((field.path.count { it == '/' } - 1).coerceAtLeast(0) * 8).dp)) {
                        Text(field.path.removePrefix("/").replace('/', '.'), style = MaterialTheme.typography.bodyMedium)
                        if (field.locked) Text(stringResource(R.string.field_locked), style = MaterialTheme.typography.labelSmall)
                    }
                    FeatureHelp(fieldHelp(field.path))
                }
            }
        }
    }
}

@Composable
internal fun FieldEditor(field: ConfigField, draft: LocalResource, busy: Boolean, model: JeemiViewModel, dismiss: () -> Unit) {
    val config = if (draft.formatVersion >= 2) JSONObject(draft.content) else JSONObject()
    val entry = (config.objects("fields") + config.objects("disabledFields")).firstOrNull { it.optString("path") == field.path }
    var enabled by rememberSaveable(field.path) { mutableStateOf(config.objects("disabledFields").none { it.optString("path") == field.path }) }
    var strategy by rememberSaveable(field.path) { mutableStateOf(entry?.optString("strategy") ?: field.defaultStrategy) }
    var conflict by rememberSaveable(field.path) { mutableStateOf(entry?.optString("conflictPolicy", "error") ?: field.defaultConflict) }
    val value by model.fieldDraft.collectAsState()
    val uriHandler = LocalUriHandler.current
    AlertDialog(onDismissRequest = { if (!busy) dismiss() },
        title = { Text(field.path.removePrefix("/").replace('/', '.')) },
        text = { Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            SectionTitle(R.string.field_value, fieldHelp(field.path))
            SwitchRow(stringResource(R.string.enabled), enabled, !busy) { enabled = it }
            when (field.editor) {
                "boolean" -> SwitchRow(stringResource(R.string.field_value), value == "true", !busy) { model.fieldDraft.value = it.toString() }
                "enum" -> ChoiceField(stringResource(R.string.field_value), value, field.options.map { it to enumLabel(it) }, !busy) { model.fieldDraft.value = it }
                else -> OutlinedTextField(value, { model.fieldDraft.value = it }, Modifier.fillMaxWidth(), minLines = if (field.editor == "yaml") 5 else 1,
                    enabled = !busy, textStyle = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace))
            }
            ChoiceField(stringResource(R.string.composition_strategy), strategy, field.strategies.map { it to stringResource(strategyLabel(it)) }, !busy) { strategy = it }
            if (strategy == "merge_by_name") ChoiceField(stringResource(R.string.field_conflict), conflict,
                listOf("error" to stringResource(R.string.conflict_error), "use_local" to stringResource(R.string.conflict_local)), !busy) { conflict = it }
            if (field.documentation.startsWith("https://")) TextButton(onClick = { uriHandler.openUri(field.documentation) }, shape = MaterialTheme.shapes.small) { Text(stringResource(R.string.field_reference)) }
        } }, dismissButton = { TextButton(onClick = dismiss, enabled = !busy, shape = MaterialTheme.shapes.small) { Text(stringResource(R.string.cancel)) } },
        confirmButton = { TextButton(onClick = { model.addField(field, value, enabled, strategy, conflict, dismiss) }, enabled = !busy, shape = MaterialTheme.shapes.small) { Text(stringResource(R.string.apply_to_draft)) } })
}
