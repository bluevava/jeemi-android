package io.jeemi.android.ui

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowForward
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import io.jeemi.android.R
import io.jeemi.android.data.SelectorIcons
import io.jeemi.android.domain.*
import io.jeemi.android.ui.components.FeatureHelp

@Composable
internal fun egressText(egress: SelectorEgress): String {
    return when (egress.end) {
        EgressEnd.NODE -> egress.nodeName.orEmpty()
        EgressEnd.BALANCED -> stringResource(R.string.egress_balanced)
        EgressEnd.RELAY -> stringResource(R.string.egress_relay)
        EgressEnd.UNAVAILABLE -> stringResource(R.string.egress_unavailable)
        EgressEnd.CYCLE -> stringResource(R.string.egress_cycle)
        EgressEnd.AUTOMATIC -> stringResource(R.string.egress_automatic)
    }
}

@Composable
internal fun SelectorBreadcrumb(path: List<ProxyGroup>, navigate: (List<String>) -> Unit) {
    Row(Modifier.fillMaxWidth().testTag("selector-breadcrumb"), verticalAlignment = Alignment.CenterVertically) {
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
internal fun SelectorGroupCard(group: ProxyGroup, delay: Int?, columns: Int, chosen: Boolean,
    selectable: Boolean, canOpen: Boolean, modifier: Modifier, icons: SelectorIcons,
    select: () -> Unit, open: () -> Unit) {
    ProxyNodeCard(group.name, group.type, delay, columns, chosen, selectable,
        testable = canOpen, testing = false, modifier = modifier, select = select, test = open,
        delayActionLabel = stringResource(R.string.open_nested_selector, group.name),
        nameIcon = rememberSelectorIcon(group.icon, icons)?.asImageBitmap())
}
