package io.jeemi.android.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.Undo
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import io.jeemi.android.R
import io.jeemi.android.data.*
import io.jeemi.android.domain.LocalResource
import io.jeemi.android.ui.components.*
import kotlinx.coroutines.*

internal val RelationshipsHelp = HelpContent(R.string.resource_relationships, R.string.help_relationships_purpose,
    R.string.help_relationships_scenarios, R.string.help_relationships_cautions)
internal val ResourceCheckHelp = HelpContent(R.string.check_preview, R.string.help_resource_check_purpose,
    R.string.help_resource_check_scenarios, R.string.help_resource_check_cautions)

@Composable
internal fun RelationshipButton(graph: ResourceGraph, key: String, enabled: Boolean = true, open: () -> Unit) {
    TextButton(onClick = open, enabled = enabled && graph.available, shape = MaterialTheme.shapes.small,
        modifier = Modifier.heightIn(min = 48.dp).testTag("relationships-$key")) {
        Icon(Icons.Outlined.AccountTree, null, Modifier.size(18.dp))
        Spacer(Modifier.width(6.dp))
        Text(if (graph.available) stringResource(R.string.relationship_count, graph.affected(key).size)
            else stringResource(R.string.resource_relationships), style = MaterialTheme.typography.labelMedium)
    }
}

@Composable
internal fun DraftRelationships(draft: LocalResource, state: AppState) {
    var open by rememberSaveable(draft.id) { mutableStateOf(false) }
    var graph by remember(draft.id) { mutableStateOf(state.resourceGraph) }
    var pending by remember(draft.id) { mutableStateOf(true) }
    LaunchedEffect(draft, state.library) {
        pending = true
        delay(150)
        graph = withContext(Dispatchers.Default) {
            try {
                resourceGraph(state.library.copy(resources = state.library.resources.filterNot { it.id == draft.id } + draft))
            } catch (cancelled: CancellationException) { throw cancelled
            } catch (_: Exception) { ResourceGraph(available = false) }
        }
        pending = false
    }
    RelationshipButton(graph, "resource:${draft.id}", !state.busy && !pending) { open = true }
    if (open) ResourceRelationshipsDialog(graph, "resource:${draft.id}") { open = false }
}

@Composable
internal fun ResourceRelationshipsDialog(graph: ResourceGraph, initial: String, dismiss: () -> Unit) {
    var path by rememberSaveable(initial) { mutableStateOf(listOf(initial)) }
    val key = path.last()
    val node = graph.node(key)
    EditorDialog(stringResource(R.string.resource_relationships), false, dismiss, help = RelationshipsHelp) {
        LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp), contentPadding = PaddingValues(bottom = 24.dp)) {
            if (path.size > 1) item {
                TextButton(onClick = { path = path.dropLast(1) }, shape = MaterialTheme.shapes.small) {
                    Icon(Icons.AutoMirrored.Outlined.Undo, null); Text(stringResource(R.string.back))
                }
            }
            item {
                if (path.size > 1) Text(path.joinToString(" → ") { graph.node(it)?.name.orEmpty().ifBlank { "—" } },
                    style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Text(node?.name.orEmpty().ifBlank { stringResource(R.string.resource_missing) }, style = MaterialTheme.typography.titleMedium)
                if (node?.missing == true) Text(stringResource(R.string.resource_missing), color = MaterialTheme.colorScheme.error)
            }
            listOf(true, false).forEach { incoming ->
                val edges = graph.edges.filter { if (incoming) it.to == key else it.from == key }
                item { Text(stringResource(if (incoming) R.string.referenced_by else R.string.references_to), style = MaterialTheme.typography.titleSmall) }
                if (edges.isEmpty()) item { Text(stringResource(R.string.none_items), style = MaterialTheme.typography.bodySmall) }
                items(edges) { edge ->
                    val target = if (incoming) edge.from else edge.to
                    val related = graph.node(target)
                    ListItem(headlineContent = { Text(related?.name.orEmpty().ifBlank { stringResource(R.string.resource_missing) }) },
                        supportingContent = { Text(stringResource(relationRole(edge.role)) +
                            if (edge.enabled) "" else " · " + stringResource(R.string.field_disabled)) },
                        leadingContent = { Icon(if (related?.missing == true) Icons.Outlined.ErrorOutline else Icons.Outlined.Link, null) },
                        trailingContent = { Icon(Icons.Outlined.ChevronRight, null) },
                        modifier = Modifier.clip(MaterialTheme.shapes.small).clickable(role = Role.Button) { path = (path + target).takeLast(16) })
                }
            }
            item { Text(stringResource(R.string.affected_subscriptions), style = MaterialTheme.typography.titleSmall) }
            val affected = graph.affected(key).mapNotNull { graph.node("subscription:$it") }
            if (affected.isEmpty()) item { Text(stringResource(R.string.none_items), style = MaterialTheme.typography.bodySmall) }
            items(affected, key = { "affected-${it.key}" }) { subscription ->
                TextButton(onClick = { path = (path + subscription.key).takeLast(16) }, shape = MaterialTheme.shapes.small) {
                    Text(subscription.name)
                }
            }
        }
    }
}

private fun relationRole(role: String) = when (role) {
    "handler" -> R.string.local_handler
    "extra" -> R.string.extra_resources
    "chain" -> R.string.chain_proxies
    "group" -> R.string.routing_groups
    "proxy" -> R.string.routing_proxy_target
    "match" -> R.string.local_match
    else -> R.string.rule_set_references
}

@Composable
internal fun ResourceCheckDialog(check: ResourceCheck, busy: Boolean, model: JeemiViewModel) {
    var relations by rememberSaveable { mutableStateOf(false) }
    val passed = check.candidates.count { it.passed }
    EditorDialog(stringResource(R.string.check_preview), busy, model::dismissResourceCheck, help = ResourceCheckHelp) {
        if (busy) LinearProgressIndicator(Modifier.fillMaxWidth())
        LazyColumn(verticalArrangement = Arrangement.spacedBy(12.dp), contentPadding = PaddingValues(bottom = 24.dp)) {
            item {
                Text(check.graph.node(check.key)?.name.orEmpty(), style = MaterialTheme.typography.titleMedium)
                Text(stringResource(R.string.resource_structure_valid), color = MaterialTheme.colorScheme.primary)
                Text(stringResource(R.string.candidates_checked, passed, check.candidates.size),
                    color = if (passed == check.candidates.size) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error)
                if (check.candidates.isEmpty()) Text(stringResource(R.string.no_candidate_checked), style = MaterialTheme.typography.bodySmall)
                RelationshipButton(check.graph, check.key) { relations = true }
            }
            items(check.candidates, key = { it.id }) { row ->
                JeemiCard(Modifier.fillMaxWidth()) {
                    Text(row.name, style = MaterialTheme.typography.titleSmall)
                    Text(if (row.passed) stringResource(R.string.candidate_check_passed)
                        else row.issue?.let { businessIssueText(it) } ?: stringResource(R.string.candidate_check_failed),
                        style = MaterialTheme.typography.bodySmall,
                        color = if (row.passed) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error)
                    if (row.passed) OutlinedButton(onClick = { model.previewCheckedCandidate(row.id) }, enabled = !busy, shape = MaterialTheme.shapes.small) {
                        Text(stringResource(R.string.configuration_preview))
                    }
                }
            }
        }
    }
    if (relations) ResourceRelationshipsDialog(check.graph, check.key) { relations = false }
}
