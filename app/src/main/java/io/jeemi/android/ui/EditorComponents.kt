@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
package io.jeemi.android.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.clickable
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import io.jeemi.android.R
import io.jeemi.android.domain.*
import io.jeemi.android.ui.components.*

internal val ImportHelp = HelpContent(R.string.subscriptions, R.string.import_purpose, R.string.import_scenarios, R.string.import_cautions)
internal val ConfigHelp = HelpContent(R.string.configuration, R.string.config_purpose, R.string.config_scenarios, R.string.config_cautions)
internal val ModeHelp = HelpContent(R.string.proxy_mode, R.string.mode_purpose, R.string.mode_scenarios, R.string.mode_cautions)

internal val AppTheme.label: Int get() = when (this) {
    AppTheme.SYSTEM -> R.string.follow_system
    AppTheme.LIGHT -> R.string.light_theme
    AppTheme.DARK -> R.string.dark_theme
}

internal val ResourceKind.label: Int get() = when(this) {
    ResourceKind.CONFIG -> R.string.local_configuration; ResourceKind.SCRIPT -> R.string.scripts
    ResourceKind.GROUPS -> R.string.groups; ResourceKind.RULES -> R.string.rule_sets
}
internal val ProxyMode.label: Int get() = when(this) {
    ProxyMode.RULE -> R.string.mode_rule; ProxyMode.GLOBAL -> R.string.mode_global; ProxyMode.DIRECT -> R.string.mode_direct
}
internal fun categoryLabel(value: String): Int = when(value) {
    "general" -> R.string.category_general; "controller" -> R.string.category_controller
    "inbound" -> R.string.category_inbound; "dns" -> R.string.category_dns
    "sniffer" -> R.string.category_sniffer; "tun" -> R.string.category_tun
    "outbound" -> R.string.category_outbound; "routing" -> R.string.category_routing
    else -> R.string.category_advanced
}
internal fun strategyLabel(value: String): Int = when(value) {
    "replace" -> R.string.strategy_replace; "prepend" -> R.string.strategy_prepend
    "append" -> R.string.strategy_append; "merge_by_name" -> R.string.strategy_merge_by_name
    "merge" -> R.string.strategy_merge; "rebuild" -> R.string.strategy_rebuild; "override" -> R.string.strategy_override
    "append_before_terminal" -> R.string.strategy_append_before_terminal; else -> R.string.strategy_auto
}

@Composable
internal fun EditorDialog(title: String, busy: Boolean, dismiss: () -> Unit,
    action: (@Composable () -> Unit)? = null, help: HelpContent? = null,
    footer: (@Composable () -> Unit)? = null, content: @Composable ColumnScope.() -> Unit) {
    Dialog(onDismissRequest = { if (!busy) dismiss() }, properties = DialogProperties(usePlatformDefaultWidth = false, dismissOnClickOutside = false)) {
        Surface(Modifier.widthIn(max = 680.dp).fillMaxWidth().fillMaxHeight(0.96f), shape = MaterialTheme.shapes.large,
            color = MaterialTheme.colorScheme.background) {
            Column(Modifier.imePadding()) {
                TopAppBar(title = { Text(title, style = MaterialTheme.typography.titleMedium, maxLines = 2) },
                    navigationIcon = { IconButton(onClick = dismiss, enabled = !busy) { Icon(Icons.AutoMirrored.Outlined.ArrowBack, stringResource(R.string.back)) } },
                    actions = { help?.let { FeatureHelp(it) }; action?.invoke() }, colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.background))
                Column(Modifier.weight(1f).fillMaxWidth().padding(horizontal = 20.dp), content = content)
                footer?.let { HorizontalDivider(); Row(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 8.dp),
                    horizontalArrangement = Arrangement.End, verticalAlignment = Alignment.CenterVertically) {
                    TextButton(onClick = dismiss, enabled = !busy, shape = MaterialTheme.shapes.small) { Text(stringResource(R.string.cancel)) }
                    Spacer(Modifier.width(12.dp)); it()
                } }
            }
        }
    }
}

@Composable
internal fun CollapsibleCard(title: String, help: HelpContent, initiallyOpen: Boolean = false,
    content: @Composable ColumnScope.() -> Unit) {
    var expanded by rememberSaveable { mutableStateOf(initiallyOpen) }
    JeemiCard(Modifier.fillMaxWidth()) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Row(Modifier.weight(1f).heightIn(min = 48.dp).clickable { expanded = !expanded },
                verticalAlignment = Alignment.CenterVertically) {
                Text(title, Modifier.weight(1f), style = MaterialTheme.typography.titleSmall)
                Icon(if (expanded) Icons.Outlined.ExpandLess else Icons.Outlined.ExpandMore,
                    stringResource(if (expanded) R.string.collapse else R.string.expand))
            }
            FeatureHelp(help)
        }
        if (expanded) content()
    }
}

@Composable
internal fun ChoiceField(label: String, value: String, options: List<Pair<String, String>>, enabled: Boolean = true, select: (String) -> Unit) {
    var open by remember { mutableStateOf(false) }
    Column {
        OutlinedButton(onClick = { open = true }, enabled = enabled, modifier = Modifier.fillMaxWidth(), shape = MaterialTheme.shapes.medium) {
            Column(Modifier.weight(1f), horizontalAlignment = Alignment.Start) {
                Text(label, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Text(options.firstOrNull { it.first == value }?.second ?: value, style = MaterialTheme.typography.bodyLarge)
            }
            Icon(Icons.Outlined.ExpandMore, null)
        }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }, modifier = Modifier.heightIn(max = 400.dp)) {
            options.forEach { option -> DropdownMenuItem(text = { Text(option.second) }, onClick = { open = false; select(option.first) }) }
        }
    }
}

@Composable
internal fun SwitchRow(label: String, checked: Boolean, enabled: Boolean = true, change: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth().heightIn(min = 48.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(label, Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
        Switch(checked, change, enabled = enabled)
    }
}

@Composable
internal fun EmptyCard(title: String, help: HelpContent, action: (@Composable () -> Unit)? = null) {
    JeemiCard(Modifier.fillMaxWidth()) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(title, Modifier.weight(1f), style = MaterialTheme.typography.titleMedium)
            FeatureHelp(help)
        }
        action?.invoke()
    }
}
