package io.jeemi.android.ui

import android.graphics.Bitmap
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.jeemi.android.R
import io.jeemi.android.data.*
import io.jeemi.android.domain.LocalResource
import io.jeemi.android.ui.components.HelpContent
import io.jeemi.android.ui.components.JeemiSwitch
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject

internal val AppRulesHelp = HelpContent(R.string.app_rule_list, R.string.app_rules_purpose, R.string.app_rules_scenarios, R.string.app_rules_cautions)

@Composable
internal fun AppRuleSetEditor(draft: LocalResource, busy: Boolean, model: JeemiViewModel) {
    val yaml = JSONObject(draft.content).optString("payloadYaml")
    var parsed by remember { mutableStateOf<Pair<String, AppRuleDraft>?>(null) }
    var invalid by remember(yaml) { mutableStateOf(false) }
    var picker by rememberSaveable(draft.id) { mutableStateOf(false) }
    LaunchedEffect(yaml) {
        parsed = null
        delay(120)
        try { parsed = yaml to editAppRuleDraft(yaml) }
        catch (cancelled: CancellationException) { throw cancelled }
        catch (_: Exception) { invalid = true }
    }
    val view = parsed?.takeIf { it.first == yaml }?.second
    CollapsibleCard(stringResource(R.string.app_rule_apps), AppRulesHelp, initiallyOpen = true) {
        if (view == null && !invalid) LinearProgressIndicator(Modifier.fillMaxWidth())
        if (invalid) Text(stringResource(R.string.app_rule_invalid), color = MaterialTheme.colorScheme.error)
        if (view != null) {
            Text(stringResource(R.string.app_rule_count, view.apps.size, view.otherRules), style = MaterialTheme.typography.labelMedium)
            if (view.rows.isNotEmpty()) LazyColumn(Modifier.fillMaxWidth().heightIn(max = 360.dp).testTag("rule-app-list")) {
                itemsIndexed(view.rows, key = { _, item -> "${item.index}:${item.rule}" },
                    contentType = { _, item -> item.packageName.isNotEmpty() }) { position, item ->
                    if (item.packageName.isEmpty()) {
                        Surface(Modifier.fillMaxWidth().padding(vertical = 4.dp).testTag("rule-other-${item.index}"),
                            shape = MaterialTheme.shapes.small, color = MaterialTheme.colorScheme.surfaceContainerHighest) {
                            SelectionContainer {
                                Text(item.rule, Modifier.padding(horizontal = 8.dp, vertical = 10.dp), maxLines = 1,
                                    overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace))
                            }
                        }
                    } else RuleAppRow(item.packageName, model = model) {
                        IconButton(onClick = { model.editRuleApps(draft, JSONObject().put("action", "move").put("index", item.index).put("step", -1)) },
                            enabled = !busy && position > 0, modifier = Modifier.testTag("rule-app-up-${item.index}")) {
                            Icon(Icons.Outlined.ArrowUpward, stringResource(R.string.move_up))
                        }
                        IconButton(onClick = { model.editRuleApps(draft, JSONObject().put("action", "move").put("index", item.index).put("step", 1)) },
                            enabled = !busy && position < view.rows.lastIndex, modifier = Modifier.testTag("rule-app-down-${item.index}")) {
                            Icon(Icons.Outlined.ArrowDownward, stringResource(R.string.move_down))
                        }
                        IconButton(onClick = { model.editRuleApps(draft, JSONObject().put("action", "delete").put("index", item.index)) },
                            enabled = !busy, modifier = Modifier.testTag("rule-app-delete-${item.index}")) {
                            Icon(Icons.Outlined.DeleteOutline, stringResource(R.string.delete))
                        }
                    }
                    HorizontalDivider()
                }
            }
        }
        OutlinedButton(onClick = { picker = true }, enabled = !busy && view != null,
            modifier = Modifier.fillMaxWidth().testTag("rule-app-add"), shape = MaterialTheme.shapes.small) {
            Icon(Icons.Outlined.Add, null)
            Text(stringResource(R.string.app_rule_add))
        }
    }
    // Both views use the same full document, without a second draft or merge.
    CollapsibleCard(stringResource(R.string.app_rule_yaml), AppRulesHelp) {
        OutlinedTextField(yaml, {
            model.editingResource.value = draft.copy(content = JSONObject(draft.content).put("payloadYaml", it).toString())
        }, Modifier.fillMaxWidth().testTag("rule-app-yaml"), minLines = 8, enabled = !busy,
            label = { Text(stringResource(R.string.rule_payload)) },
            keyboardOptions = KeyboardOptions(autoCorrectEnabled = false),
            textStyle = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace))
    }
    if (picker && view != null) InstalledAppsPicker(model, busy, view.apps.map { it.packageName }.toSet(),
        dismiss = { picker = false }, add = { packages ->
            model.editRuleApps(draft, JSONObject().put("action", "add").put("packages", JSONArray(packages))) { picker = false }
        })
}

@Composable
private fun InstalledAppsPicker(model: JeemiViewModel, busy: Boolean, existing: Set<String>, dismiss: () -> Unit, add: (List<String>) -> Unit) {
    var query by rememberSaveable { mutableStateOf("") }
    var showSystem by rememberSaveable { mutableStateOf(false) }
    var selected by remember { mutableStateOf<List<String>>(emptyList()) }
    val locale = LocalConfiguration.current.locales.toLanguageTags()
    var apps by remember(locale) { mutableStateOf<List<InstalledApp>?>(null) }
    var failed by remember { mutableStateOf(false) }
    var retry by remember { mutableIntStateOf(0) }
    ObserveDiagnostic(true, listOf(locale, retry)) {
        if (apps == null) {
            failed = false
            try { apps = model.installedApps.load() }
            catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { failed = true }
        }
    }
    val filtered by produceState<List<InstalledApp>>(emptyList(), apps, query, showSystem) {
        value = withContext(Dispatchers.Default) { filterInstalledApps(apps.orEmpty(), query, showSystem) }
    }
    EditorDialog(stringResource(R.string.app_rule_add), busy, dismiss, help = AppRulesHelp, footer = {
        Button(onClick = { add(selected) }, enabled = !busy && selected.isNotEmpty(),
            modifier = Modifier.testTag("rule-app-confirm"), shape = MaterialTheme.shapes.small) {
            Text(stringResource(R.string.app_rule_add_selected, selected.size))
        }
    }) {
        OutlinedTextField(query, { query = it.take(255) }, Modifier.fillMaxWidth().testTag("rule-app-search"), singleLine = true,
            enabled = !busy, label = { Text(stringResource(R.string.app_rule_search)) }, keyboardOptions = KeyboardOptions(autoCorrectEnabled = false))
        Row(Modifier.fillMaxWidth().heightIn(min = 48.dp).testTag("rule-app-system")
            .toggleable(showSystem, enabled = !busy, role = Role.Switch) { showSystem = it }, verticalAlignment = Alignment.CenterVertically) {
            Text(stringResource(R.string.app_rule_system), Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
            JeemiSwitch(showSystem, onCheckedChange = null, enabled = !busy)
        }
        if (apps == null && !failed) LinearProgressIndicator(Modifier.fillMaxWidth())
        if (failed) {
            Text(stringResource(R.string.app_rule_load_failed), color = MaterialTheme.colorScheme.error)
            TextButton(onClick = { retry++ }, enabled = !busy, shape = MaterialTheme.shapes.small) { Text(stringResource(R.string.app_rule_retry)) }
        }
        if (apps != null && filtered.isEmpty()) Text(stringResource(R.string.no_results), style = MaterialTheme.typography.bodyMedium)
        LazyColumn(Modifier.weight(1f).testTag("rule-app-picker-list")) {
            items(filtered, key = { it.packageName }) { app ->
                val checked = app.packageName in selected || app.packageName in existing
                RuleAppRow(app.packageName, model, knownLabel = app.name,
                    modifier = Modifier.testTag("rule-app-pick-${app.packageName}").toggleable(checked,
                        enabled = !busy && app.packageName !in existing, role = Role.Checkbox) {
                        selected = if (it) selected + app.packageName else selected - app.packageName
                    }) {
                    Checkbox(checked, onCheckedChange = null, enabled = !busy && app.packageName !in existing)
                }
                HorizontalDivider()
            }
        }
    }
}

@Composable
private fun RuleAppRow(packageName: String, model: JeemiViewModel, modifier: Modifier = Modifier,
    knownLabel: String? = null, actions: @Composable RowScope.() -> Unit) {
    val locale = LocalConfiguration.current.locales.toLanguageTags()
    var details by remember(packageName, locale, knownLabel) {
        mutableStateOf(AppDetails(knownLabel ?: packageName, if (knownLabel == null) AppInstallation.UNKNOWN else AppInstallation.INSTALLED))
    }
    var bitmap by remember(packageName) { mutableStateOf<Bitmap?>(null) }
    ObserveDiagnostic(true, listOf(packageName, locale, knownLabel)) {
        if (knownLabel == null) details = model.ruleAppDetails.load(packageName)
        bitmap = if (details.installation == AppInstallation.NOT_INSTALLED) null else model.diagnostics.applicationIcons.load(packageName)
    }
    val image = remember(bitmap) { bitmap?.takeUnless { it.isRecycled }?.asImageBitmap() }
    val iconSize = with(LocalDensity.current) { 24.sp.toDp() }
    Row(modifier.fillMaxWidth().heightIn(min = 56.dp).padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        if (details.installation == AppInstallation.NOT_INSTALLED) Text(stringResource(R.string.app_rule_not_installed),
            Modifier.width(IntrinsicSize.Max).testTag("rule-app-missing-$packageName"), textAlign = TextAlign.Center,
            style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        else if (image != null) Image(image, null, Modifier.size(iconSize))
        else Icon(Icons.Outlined.Apps, null, Modifier.size(iconSize), tint = MaterialTheme.colorScheme.onSurfaceVariant)
        Column(Modifier.weight(1f).padding(horizontal = 6.dp)) {
            if (details.installation == AppInstallation.INSTALLED) Text(details.label, maxLines = 1, overflow = TextOverflow.Ellipsis,
                style = MaterialTheme.typography.bodyMedium)
            Text(packageName, maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        actions()
    }
}
