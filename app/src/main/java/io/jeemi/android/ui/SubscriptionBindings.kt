package io.jeemi.android.ui

import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.jeemi.android.R
import io.jeemi.android.data.ChainGroup
import io.jeemi.android.domain.*

internal enum class BindingKind(val label: Int) {
    SCRIPT(R.string.binding_script), CONFIG(R.string.binding_config), CHAIN(R.string.binding_chain)
}

@Composable
internal fun SubscriptionBindings(subscription: Subscription, handler: LocalResource?, chains: List<ChainGroup>,
    busy: Boolean, bind: (BindingKind) -> Unit, unbind: (BindingKind) -> Unit) {
    val missing = stringResource(R.string.resource_missing)
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        if (subscription.handlerId != null && handler == null) Text(missing, color = MaterialTheme.colorScheme.error,
            style = MaterialTheme.typography.bodySmall)
        BindingKind.entries.forEach { kind ->
            val bound = when (kind) {
                BindingKind.SCRIPT -> handler?.kind == ResourceKind.SCRIPT
                BindingKind.CONFIG -> handler?.kind == ResourceKind.CONFIG
                BindingKind.CHAIN -> subscription.chainGroupIds.isNotEmpty()
            }
            val name = if (kind == BindingKind.CHAIN) subscription.chainGroupIds.joinToString(" · ") { id ->
                chains.firstOrNull { it.id == id }?.name ?: missing
            } else handler?.name.orEmpty()
            val label = stringResource(kind.label)
            val actionLabel = stringResource(if (bound) R.string.unbind_resource else R.string.bind_resource, label, subscription.name)
            val changeLabel = stringResource(R.string.change_binding, label, subscription.name)
            Surface(shape = MaterialTheme.shapes.small, color = if (bound) MaterialTheme.colorScheme.primaryContainer.copy(alpha = .45f)
                else MaterialTheme.colorScheme.surfaceContainerHigh.copy(alpha = .5f)) {
                Row(Modifier.fillMaxWidth().heightIn(min = 48.dp).padding(start = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                    Icon(when (kind) { BindingKind.SCRIPT -> Icons.Outlined.Code; BindingKind.CONFIG -> Icons.Outlined.Tune
                        BindingKind.CHAIN -> Icons.Outlined.Link }, null, Modifier.size(18.dp))
                    Spacer(Modifier.width(6.dp))
                    Text(label, style = MaterialTheme.typography.labelMedium)
                    TextButton(onClick = { bind(kind) }, enabled = !busy, shape = MaterialTheme.shapes.small,
                        modifier = Modifier.weight(1f).heightIn(min = 48.dp).semantics { contentDescription = changeLabel }
                            .testTag("binding-name-${subscription.id}-${kind.name}")) {
                        Text(if (bound) name else stringResource(R.string.unbound), maxLines = 2, overflow = TextOverflow.Ellipsis,
                            style = MaterialTheme.typography.bodySmall)
                    }
                    TextButton(onClick = { if (bound) unbind(kind) else bind(kind) }, enabled = !busy, shape = MaterialTheme.shapes.small,
                        modifier = Modifier.heightIn(min = 48.dp).semantics { contentDescription = actionLabel }
                            .testTag("binding-action-${subscription.id}-${kind.name}")) {
                        Text(stringResource(if (bound) R.string.unbind else R.string.bind), style = MaterialTheme.typography.labelMedium)
                    }
                }
            }
        }
    }
}
