package io.jeemi.android.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowForward
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.jeemi.android.R
import io.jeemi.android.data.SelectorIcons
import io.jeemi.android.domain.*
import io.jeemi.android.ui.components.FeatureHelp
import io.jeemi.android.ui.theme.JeemiShapes

@Composable
internal fun egressText(egress: SelectorEgress): String {
    val suffix = when (egress.end) {
        EgressEnd.NODE -> ""
        EgressEnd.BALANCED -> stringResource(R.string.egress_balanced)
        EgressEnd.RELAY -> stringResource(R.string.egress_relay)
        EgressEnd.UNAVAILABLE -> stringResource(R.string.egress_unavailable)
        EgressEnd.CYCLE -> stringResource(R.string.egress_cycle)
        EgressEnd.AUTOMATIC -> stringResource(R.string.egress_automatic)
    }
    return (egress.names + listOf(suffix).filter { it.isNotEmpty() }).joinToString(" · ")
}

@Composable
internal fun SelectorBreadcrumb(path: List<ProxyGroup>, navigate: (List<String>) -> Unit) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Row(Modifier.weight(1f).horizontalScroll(rememberScrollState()), verticalAlignment = Alignment.CenterVertically) {
            path.forEachIndexed { index, group ->
                if (index > 0) Icon(Icons.AutoMirrored.Outlined.ArrowForward, null, Modifier.size(12.dp))
                TextButton(onClick = { navigate(path.take(index + 1).drop(1).map { it.name }) },
                    shape = MaterialTheme.shapes.small, enabled = index != path.lastIndex,
                    colors = ButtonDefaults.textButtonColors(disabledContentColor = MaterialTheme.colorScheme.onSurfaceVariant)) {
                    Text(group.name, maxLines = 1)
                }
            }
        }
        FeatureHelp(NestedSelectorsHelp)
    }
}

@Composable
internal fun SelectorGroupCard(group: ProxyGroup, egress: String, live: Boolean, chosen: Boolean,
    selectable: Boolean, canOpen: Boolean, icons: SelectorIcons, modifier: Modifier,
    select: () -> Unit, open: () -> Unit) {
    Card(modifier.semantics { selected = chosen }, shape = JeemiShapes.Node,
        colors = CardDefaults.cardColors(containerColor = if (chosen) MaterialTheme.colorScheme.secondaryContainer else MaterialTheme.colorScheme.surface)) {
        Row(Modifier.fillMaxWidth().heightIn(min = 64.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f).heightIn(min = 64.dp)
                .clickable(enabled = selectable, role = Role.Button, onClick = select).padding(start = 8.dp, top = 5.dp, bottom = 5.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    SelectorGroupIcon(group, icons)
                    Text(selectorTitle(group.name).second, maxLines = 1, overflow = TextOverflow.Ellipsis,
                        style = MaterialTheme.typography.bodySmall)
                }
                Text(group.type, maxLines = 1, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Text(if (live) egress else stringResource(R.string.selector_preselected, egress), maxLines = 1,
                    overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary)
            }
            IconButton(onClick = open, enabled = canOpen) {
                Icon(Icons.AutoMirrored.Outlined.ArrowForward, stringResource(R.string.open_nested_selector, group.name), Modifier.size(18.dp))
            }
        }
    }
}
