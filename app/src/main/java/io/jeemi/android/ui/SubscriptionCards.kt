package io.jeemi.android.ui

import android.icu.lang.UCharacter
import android.icu.lang.UProperty
import android.icu.text.BreakIterator
import android.graphics.Bitmap
import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.*
import io.jeemi.android.ui.theme.JeemiShapes
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.jeemi.android.R
import io.jeemi.android.data.SelectorIcons
import io.jeemi.android.domain.*
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import java.text.DateFormat
import java.util.Date
import java.util.Locale

@Composable
internal fun SelectorHeader(group: ProxyGroup, choice: String?, live: Boolean, expanded: Boolean,
    icons: SelectorIcons, toggle: () -> Unit) {
    val (_, title) = remember(group.name) { selectorTitle(group.name) }
    var showEgress by rememberSaveable(group.name) { mutableStateOf(false) }
    val egressLabel = stringResource(R.string.egress_node)
    Card(Modifier.fillMaxWidth(), shape = JeemiShapes.Component,
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)) {
        Row(Modifier.fillMaxWidth().heightIn(min = 48.dp).semantics { contentDescription = group.name }
            .combinedClickable(role = Role.Button, onClick = toggle, onLongClickLabel = egressLabel,
                onLongClick = { showEgress = true }).padding(horizontal = 10.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.width(24.dp), contentAlignment = Alignment.Center) {
                SelectorGroupIcon(group, icons)
            }
            Spacer(Modifier.width(8.dp))
            Column(Modifier.weight(1f)) {
                Text(title, maxLines = 1, softWrap = false, overflow = TextOverflow.Ellipsis,
                    style = MaterialTheme.typography.bodyMedium.copy(lineHeight = 18.sp))
                Text(group.type + " " + group.members.size, maxLines = 1, softWrap = false, overflow = TextOverflow.Ellipsis,
                    style = MaterialTheme.typography.labelSmall.copy(lineHeight = 14.sp), color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Text(if (choice.isNullOrEmpty()) "—" else if (live) choice else stringResource(R.string.selector_preselected, choice),
                Modifier.weight(1f).padding(start = 8.dp, end = 6.dp), maxLines = 1, softWrap = false,
                overflow = TextOverflow.Ellipsis, textAlign = TextAlign.End,
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.primary)
            Icon(if (expanded) Icons.Outlined.ExpandLess else Icons.Outlined.ExpandMore,
                stringResource(if (expanded) R.string.collapse else R.string.expand), Modifier.size(20.dp))
        }
    }
    if (showEgress) AlertDialog(onDismissRequest = { showEgress = false },
        title = { Text(egressLabel) },
        text = { Text(choice.orEmpty().ifBlank { "—" }, Modifier.verticalScroll(rememberScrollState())) },
        confirmButton = { TextButton(onClick = { showEgress = false }, shape = MaterialTheme.shapes.small) { Text(stringResource(R.string.close)) } })
}

@Composable
internal fun SelectorGroupIcon(group: ProxyGroup, icons: SelectorIcons) {
    val (emoji, _) = remember(group.name) { selectorTitle(group.name) }
    val image = rememberSelectorIcon(group.icon, icons)
    if (image != null) Image(image.asImageBitmap(), null, Modifier.size(20.dp).testTag("selector-icon-image"))
    else if (emoji.isEmpty()) Icon(Icons.Outlined.AccountTree, null, Modifier.size(20.dp), tint = MaterialTheme.colorScheme.primary)
    else Text(emoji, fontSize = 20.sp, maxLines = 1)
}

@Composable
internal fun rememberSelectorIcon(address: String, icons: SelectorIcons): Bitmap? {
    var bitmap by remember(address, icons) { mutableStateOf<Bitmap?>(null) }
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    LaunchedEffect(address, icons, lifecycle) {
        lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) { bitmap = icons.load(address) }
    }
    return bitmap?.takeUnless { it.isRecycled }
}

// Only presentation separates a leading emoji; exact group keys stay unchanged.
internal fun selectorTitle(name: String): Pair<String, String> {
    val text = name.trimStart()
    if (text.isEmpty()) return "" to name
    val iterator = BreakIterator.getCharacterInstance(Locale.ROOT).apply { setText(text) }
    val end = iterator.next()
    if (end == BreakIterator.DONE || end == text.length) return "" to name
    val first = text.substring(0, end)
    val emoji = first.codePoints().anyMatch { code ->
        UCharacter.hasBinaryProperty(code, UProperty.EMOJI) && code !in '0'.code..'9'.code && code != '#'.code && code != '*'.code
    }
    return if (emoji && text.substring(end).isNotBlank()) first to text.substring(end).trimStart() else "" to name
}

@Composable
internal fun SubscriptionSummary(subscription: Subscription?, busy: Boolean, largeFont: Boolean, now: Long,
    refresh: () -> Unit, providers: () -> Unit, preview: () -> Unit, expand: () -> Unit, beginImport: () -> Unit) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        SubscriptionIcon(subscription?.icon.orEmpty(), subscription?.iconImage.orEmpty())
        Spacer(Modifier.width(6.dp))
        Text(subscription?.name ?: stringResource(R.string.no_subscription), Modifier.weight(1f), maxLines = if (largeFont) 2 else 1,
            overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.titleSmall)
        if (!largeFont) SubscriptionActions(subscription, busy, refresh, providers, preview)
        IconButton(onClick = expand) { Icon(Icons.Outlined.ExpandMore, stringResource(R.string.subscription_shelf), Modifier.size(20.dp)) }
    }
    if (largeFont) Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
        SubscriptionActions(subscription, busy, refresh, providers, preview)
    }
    if (subscription != null) {
        Row(Modifier.fillMaxWidth().padding(bottom = 4.dp), verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            SummaryValue(expiryLabel(subscription, compact = true), Modifier.weight(.9f))
            SummaryValue(usageLabel(subscription), Modifier.weight(1.5f), TextAlign.Center)
            SummaryValue(relativeTime(subscription.updatedAt, now), Modifier.weight(.8f), TextAlign.End)
        }
    } else Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
        TextButton(onClick = beginImport, enabled = !busy, shape = MaterialTheme.shapes.small) { Icon(Icons.Outlined.Add, null); Text(stringResource(R.string.import_action)) }
    }
}

@Composable
private fun RowScope.SubscriptionActions(subscription: Subscription?, busy: Boolean, refresh: () -> Unit, providers: () -> Unit, preview: () -> Unit) {
    IconButton(onClick = refresh, enabled = !busy && !subscription?.sourceUrl.isNullOrBlank()) {
        Icon(Icons.Outlined.Refresh, stringResource(R.string.refresh), Modifier.size(20.dp))
    }
    IconButton(onClick = providers, enabled = subscription != null) {
        Icon(Icons.Outlined.RuleFolder, stringResource(R.string.rule_providers), Modifier.size(20.dp))
    }
    IconButton(onClick = preview, enabled = subscription != null && !busy) {
        Icon(Icons.Outlined.Code, stringResource(R.string.configuration_preview), Modifier.size(20.dp))
    }
}

@Composable
internal fun SubscriptionEntry(subscription: Subscription, handler: LocalResource?, chosen: Boolean, busy: Boolean, now: Long,
    select: () -> Unit, refresh: () -> Unit, associate: () -> Unit, menu: @Composable () -> Unit) {
    Card(Modifier.fillMaxWidth().semantics { selected = chosen }.clickable(enabled = !busy, role = Role.Button, onClick = select),
        shape = JeemiShapes.Component, colors = CardDefaults.cardColors(containerColor =
            if (chosen) MaterialTheme.colorScheme.secondaryContainer else MaterialTheme.colorScheme.surfaceContainerLow)) {
        Column(Modifier.padding(horizontal = 10.dp, vertical = 6.dp)) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                SubscriptionIcon(subscription.icon, subscription.iconImage)
                Spacer(Modifier.width(8.dp))
                Column(Modifier.weight(1f)) {
                    Text(subscription.name, style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text(relativeTime(subscription.updatedAt, now), style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
                IconButton(onClick = refresh, enabled = !busy && subscription.sourceUrl.isNotBlank()) {
                    Icon(Icons.Outlined.Refresh, stringResource(R.string.refresh_subscription, subscription.name), Modifier.size(20.dp))
                }
                menu()
            }
            Spacer(Modifier.height(4.dp))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                SummaryValue(expiryLabel(subscription), Modifier.weight(1f))
                SummaryValue(usageLabel(subscription), Modifier.weight(1f), TextAlign.End)
            }
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(stringResource(R.string.rule_provider_count, subscription.ruleProviderCount), Modifier.weight(1f),
                    style = MaterialTheme.typography.labelSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                TextButton(onClick = associate, modifier = Modifier.weight(1.25f), enabled = !busy,
                    contentPadding = PaddingValues(horizontal = 0.dp), shape = JeemiShapes.Component) {
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End, verticalAlignment = Alignment.CenterVertically) {
                        if (handler != null) {
                            Icon(if (handler.kind == ResourceKind.SCRIPT) Icons.Outlined.Code else Icons.Outlined.Tune,
                                stringResource(handler.kind.label), Modifier.size(14.dp))
                            Spacer(Modifier.width(4.dp))
                        }
                        Text(handler?.name ?: stringResource(R.string.associate_subscription_handler), maxLines = 1,
                            overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.labelSmall)
                    }
                }
            }
            if (subscription.description.isNotBlank()) Text(subscription.description, style = MaterialTheme.typography.bodySmall)
            if (subscription.chainGroupIds.isNotEmpty()) Text(stringResource(R.string.chain_linked_count, subscription.chainGroupIds.size),
                style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary)
            if (subscription.skippedNodes > 0) Text(pluralStringResource(R.plurals.skipped_nodes, subscription.skippedNodes, subscription.skippedNodes),
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
        }
    }
}

@Composable
private fun SummaryValue(value: String, modifier: Modifier, align: TextAlign = TextAlign.Start) {
    Text(value, modifier, maxLines = 1, softWrap = false, overflow = TextOverflow.Ellipsis, textAlign = align,
        style = MaterialTheme.typography.labelSmall.copy(fontSize = 10.sp, lineHeight = 14.sp), color = MaterialTheme.colorScheme.onSurfaceVariant)
}

@Composable
private fun expiryLabel(subscription: Subscription, compact: Boolean = false): String {
    val seconds = subscription.expiresAt?.takeIf { it in 1..Long.MAX_VALUE / 1000 } ?: return "—"
    val locale = LocalConfiguration.current.locales[0]
    val formatter = if (compact) DateFormat.getDateInstance(DateFormat.SHORT, locale)
        else DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT, locale)
    return formatter.format(Date(seconds * 1000))
}

@Composable
private fun usageLabel(subscription: Subscription): String {
    if (subscription.uploadedBytes == null && subscription.downloadedBytes == null && subscription.totalBytes == null) return "—"
    fun bytes(value: Long): String = "%.2f GiB".format(value / 1073741824.0)
    return stringResource(R.string.subscription_usage, bytes((subscription.uploadedBytes ?: 0) + (subscription.downloadedBytes ?: 0)),
        subscription.totalBytes?.let(::bytes) ?: "—")
}

@Composable
private fun relativeTime(updatedAt: Long, now: Long): String {
    if (updatedAt <= 0) return "—"
    val seconds = ((now - updatedAt).coerceAtLeast(0) / 1000).coerceAtMost(Int.MAX_VALUE.toLong()).toInt()
    val (unit, count) = when {
        seconds < 60 -> R.plurals.seconds_ago to seconds
        seconds < 3600 -> R.plurals.minutes_ago to seconds / 60
        seconds < 86400 -> R.plurals.hours_ago to seconds / 3600
        else -> R.plurals.days_ago to seconds / 86400
    }
    return pluralStringResource(unit, count, count)
}
