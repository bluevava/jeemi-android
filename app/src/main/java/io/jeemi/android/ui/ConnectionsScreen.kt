package io.jeemi.android.ui

import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalViewConfiguration
import androidx.compose.ui.platform.ViewConfiguration
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.jeemi.android.R
import io.jeemi.android.data.isApplicationPackage
import io.jeemi.android.data.safeApplicationLabel
import io.jeemi.android.domain.*
import io.jeemi.android.runtime.ConnectionRecord
import io.jeemi.android.runtime.connectionEndpoint
import io.jeemi.android.runtime.connectionTableRows
import io.jeemi.android.runtime.matchedRule
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.util.UUID

private data class ConnectionCloseRequest(val sessionId: String, val ids: List<String>)
private data class ConnectionTable(val sessionId: String = "", val ended: Boolean = false,
    val rows: List<ConnectionRecord> = emptyList(), val appNames: Map<String, String> = emptyMap())
private data class ApplicationIcon(val packageName: String, val bitmap: android.graphics.Bitmap?)

@Composable
internal fun ConnectionsScreen(state: AppState, model: JeemiViewModel, modifier: Modifier) {
    val feed by model.diagnostics.connections.collectAsStateWithLifecycle()
    val live by model.live.collectAsStateWithLifecycle()
    val runtime by model.runtime.collectAsStateWithLifecycle()
    var query by rememberSaveable { mutableStateOf("") }
    var search by rememberSaveable { mutableStateOf(false) }
    var ended by rememberSaveable { mutableStateOf(false) }
    var sort by rememberSaveable { mutableStateOf("start") }
    var descending by rememberSaveable { mutableStateOf(true) }
    var details by remember { mutableStateOf<ConnectionRecord?>(null) }
    var closing by remember { mutableStateOf<ConnectionCloseRequest?>(null) }
    LaunchedEffect(live?.sessionId) { details = null; closing = null }
    val active = runtime is RuntimeState.Running && live?.sessionId == feed.sessionId
    ObserveDiagnostic(true, live?.sessionId to runtime) { model.diagnostics.observeConnections() }
    val locale = LocalConfiguration.current.locales[0].toLanguageTag()
    val table by produceState(ConnectionTable(), feed.rows, feed.sessionId, locale, query, ended, sort, descending) {
        value = withContext(Dispatchers.Default) {
            val names = model.diagnostics.applicationNames(feed.rows.map { it.process }, locale)
            val rows = connectionTableRows(feed.rows, query, ended, sort, descending, names) { ensureActive() }
            ConnectionTable(feed.sessionId, ended, rows, names)
        }
    }
    fun application(row: ConnectionRecord) = table.appNames[row.process] ?: row.process.takeIf(::isApplicationPackage).orEmpty()
    val rows = if (table.sessionId == feed.sessionId && table.ended == ended) table.rows else emptyList()
    val activeShown = feed.rows.count { !it.ended }
    fun close(ids: List<String>) { closing = ConnectionCloseRequest(feed.sessionId, ids) }
    Column(modifier.padding(horizontal = 12.dp)) {
        Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), verticalAlignment = Alignment.CenterVertically) {
            FilterChip(!ended, { ended = false }, label = { Text(stringResource(R.string.connections_active, feed.activeCount)) })
            Spacer(Modifier.width(6.dp))
            FilterChip(ended, { ended = true }, label = { Text(stringResource(R.string.connections_ended, feed.rows.count { it.ended })) })
            Spacer(Modifier.weight(1f))
            IconButton(onClick = { search = !search; if (!search) query = "" }) { Icon(Icons.Outlined.Search, stringResource(R.string.search_connections)) }
            var sorting by remember { mutableStateOf(false) }
            Box {
                IconButton(onClick = { sorting = true }) { Icon(Icons.Outlined.Sort, stringResource(R.string.connection_sort)) }
                DropdownMenu(sorting, { sorting = false }) {
                    listOf("start" to R.string.connection_start, "target" to R.string.connection_target,
                        "process" to R.string.connection_application, "rule" to R.string.connection_rule,
                        "outbound" to R.string.connection_actual_outbound, "upload" to R.string.connection_upload_speed,
                        "download" to R.string.connection_download_speed).forEach { (key, label) ->
                        DropdownMenuItem(text = { Text(stringResource(label) + if (sort == key) (if (descending) " ↓" else " ↑") else "") },
                            onClick = { if (sort == key) descending = !descending else { sort = key; descending = key in listOf("start", "upload", "download") }; sorting = false })
                    }
                }
            }
            IconButton(onClick = { if (ended) model.diagnostics.clearEndedConnections() else close(rows.map { it.id }) }, enabled = !feed.busy && (ended || active && rows.isNotEmpty())) {
                Icon(if (ended) Icons.Outlined.DeleteSweep else Icons.Outlined.Close, stringResource(if (ended) R.string.clear_records else R.string.close_visible_connections))
            }
        }
        if (search) OutlinedTextField(query, { query = it }, Modifier.fillMaxWidth(), singleLine = true, label = { Text(stringResource(R.string.search_connections)) })
        val status = diagnosticStatus(if (!active) "offline" else feed.status)
        Text(if (feed.activeCount > activeShown) status + " · " + stringResource(R.string.connection_display_limit, activeShown, feed.activeCount) else status,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(vertical = 6.dp))
        if (feed.error) Text(stringResource(if (feed.limitExceeded) R.string.connection_snapshot_limit_error else R.string.connection_operation_error),
            color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
        val fontScale = LocalDensity.current.fontScale.coerceIn(1f, 1.5f)
        val speedWidth = (92 * fontScale).dp
        val actionWidth = (48 * fontScale).dp
        Row(Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.surfaceContainer).testTag("connection-table-header"), verticalAlignment = Alignment.CenterVertically) {
            ConnectionHeading(R.string.connection_app_target, Modifier.weight(1.25f)) { if (sort == "target") descending = !descending else { sort = "target"; descending = false } }
            ConnectionHeading(R.string.connection_match_outbound, Modifier.weight(1f)) { if (sort == "rule") descending = !descending else { sort = "rule"; descending = false } }
            ConnectionHeading(R.string.connection_speed, Modifier.width(speedWidth)) { if (sort == "download") descending = !descending else { sort = "download"; descending = true } }
            ConnectionHeading(R.string.connection_actions, Modifier.width(actionWidth))
        }
        val viewConfiguration = LocalViewConfiguration.current
        val tableViewConfiguration = remember(viewConfiguration) {
            object : ViewConfiguration by viewConfiguration {
                // These explicitly compact actions share a two-line cell. Do
                // not expand their hit regions across each other or nearby rows.
                override val minimumTouchTargetSize = DpSize.Zero
            }
        }
        CompositionLocalProvider(LocalViewConfiguration provides tableViewConfiguration) {
            LazyColumn(Modifier.weight(1f).testTag("connection-table")) {
                items(rows, key = { it.id }) { record ->
                    // One table row per connection. Each of its four cells contains
                    // two stacked values; only the row boundary has a divider.
                    Row(Modifier.fillMaxWidth().testTag("connection-row-" + record.id)
                        .clickable { details = record }.background(MaterialTheme.colorScheme.surface), verticalAlignment = Alignment.CenterVertically) {
                        ConnectionCell(connectionEndpoint(record.target, record.destinationPort), application(record).ifBlank { stringResource(R.string.connection_unknown_application) }, Modifier.weight(1.25f), secondLeading = {
                            ConnectionApplicationIcon(record, model)
                        })
                        ConnectionCell(record.matchedRule(), record.outbound.ifBlank { "—" }, Modifier.weight(1f))
                        ConnectionCell("↑ " + connectionSpeed(record.uploadRate), "↓ " + connectionSpeed(record.downloadRate), Modifier.width(speedWidth), speed = true)
                        Column(Modifier.width(actionWidth).padding(vertical = 4.dp), horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.spacedBy(2.dp)) {
                            ConnectionAction(Icons.Outlined.Close, stringResource(R.string.connection_close_target, record.target),
                                enabled = active && !record.ended && !feed.busy, modifier = Modifier.testTag("connection-close-" + record.id)) { close(listOf(record.id)) }
                            ConnectionAction(Icons.Outlined.Info, stringResource(R.string.connection_details_target, record.target),
                                modifier = Modifier.testTag("connection-details-" + record.id)) { details = record }
                        }
                    }
                    HorizontalDivider(thickness = .5.dp)
                }
            }
        }
        if (rows.isEmpty()) Text(stringResource(R.string.no_results), Modifier.padding(vertical = 16.dp), style = MaterialTheme.typography.bodySmall)
    }
    details?.let { original ->
        val found = feed.rows.firstOrNull { it.id == original.id }
        val current = found ?: original.copy(uploadRate = null, downloadRate = null)
        ConnectionDetails(current, application(current), if (!active || found == null || feed.status != "live") "stale" else if (current.ended) "ended" else "live", state, model,
            dismiss = { details = null }, close = { close(listOf(current.id)) })
    }
    closing?.let { request -> AlertDialog(onDismissRequest = { closing = null }, title = { Text(stringResource(R.string.close_connections_title)) },
        text = { Text(stringResource(R.string.close_connections_confirm, request.ids.size)) },
        confirmButton = { TextButton(onClick = { model.diagnostics.closeConnections(request.ids, request.sessionId); closing = null },
            enabled = active && request.sessionId == live?.sessionId, shape = MaterialTheme.shapes.small) { Text(stringResource(R.string.confirm)) } },
        dismissButton = { TextButton(onClick = { closing = null }, shape = MaterialTheme.shapes.small) { Text(stringResource(R.string.cancel)) } }) }
}

@Composable
private fun ConnectionHeading(label: Int, modifier: Modifier, sort: (() -> Unit)? = null) {
    Box(modifier.heightIn(min = 48.dp).then(if (sort != null) Modifier.clickable(onClick = sort) else Modifier).padding(horizontal = 4.dp), contentAlignment = Alignment.CenterStart) {
        Text(stringResource(label), style = MaterialTheme.typography.labelSmall, maxLines = 2, overflow = TextOverflow.Ellipsis)
    }
}

@Composable
private fun ConnectionCell(first: String, second: String, modifier: Modifier, speed: Boolean = false, secondLeading: (@Composable () -> Unit)? = null) {
    Column(modifier.padding(horizontal = 4.dp, vertical = 4.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Text(first, maxLines = 1, overflow = TextOverflow.Ellipsis, fontSize = if (speed) 11.sp else 12.sp, lineHeight = 16.sp)
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(3.dp)) {
            secondLeading?.invoke()
            Text(second, Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis, fontSize = 11.sp, lineHeight = 16.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun ConnectionApplicationIcon(row: ConnectionRecord, model: JeemiViewModel) {
    val loaded by produceState<ApplicationIcon?>(null, row.process) {
        value = null
        value = ApplicationIcon(row.process, model.diagnostics.applicationIcons.load(row.process))
    }
    val bitmap = loaded?.takeIf { it.packageName == row.process }?.bitmap?.takeUnless { it.isRecycled }
    val image = remember(bitmap) { bitmap?.asImageBitmap() }
    val size = with(LocalDensity.current) { 14.sp.toDp() }
    if (image != null) Image(image, null, Modifier.size(size).testTag("connection-app-icon-" + row.id))
    else Icon(Icons.Outlined.Apps, null, Modifier.size(size).testTag("connection-app-fallback-" + row.id), tint = MaterialTheme.colorScheme.onSurfaceVariant)
}

@Composable
private fun ConnectionAction(icon: ImageVector, description: String, modifier: Modifier = Modifier, enabled: Boolean = true, onClick: () -> Unit) {
    val size = with(LocalDensity.current) { 16.sp.toDp() }
    val iconSize = with(LocalDensity.current) { 14.sp.toDp() }
    Box(modifier.size(size).clipToBounds().clickable(enabled = enabled, role = Role.Button, onClick = onClick)
        .semantics { contentDescription = description }, contentAlignment = Alignment.Center) {
        Icon(icon, null, Modifier.size(iconSize), tint = MaterialTheme.colorScheme.onSurface.copy(alpha = if (enabled) 1f else .38f))
    }
}

@Composable
private fun ConnectionDetails(row: ConnectionRecord, application: String, status: String, state: AppState, model: JeemiViewModel, dismiss: () -> Unit, close: () -> Unit) {
    var ruleType by remember { mutableStateOf<String?>(null) }
    EditorDialog(stringResource(R.string.connection_details), false, dismiss, help = ConnectionsHelp,
        action = { IconButton(close, enabled = status == "live") { Icon(Icons.Outlined.Close, stringResource(R.string.close_connection)) } }) {
        Column(Modifier.verticalScroll(rememberScrollState()).padding(bottom = 24.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text(if (status == "ended") stringResource(R.string.connection_closed) else diagnosticStatus(status), style = MaterialTheme.typography.labelMedium)
            DetailField(R.string.connection_target, connectionEndpoint(row.target, row.destinationPort), if (row.target.isBlank()) null else { {
                ruleType = if (row.host.isNotBlank() && !row.host.contains(':') && !row.host.matches(Regex("[0-9]+\\.[0-9]+\\.[0-9]+\\.[0-9]+"))) "domain" else "ip"
            } })
            DetailField(R.string.connection_application, application)
            DetailField(R.string.application_package, safeApplicationLabel(row.process, 255), if (!isApplicationPackage(row.process)) null else { { ruleType = "processName" } })
            DetailField(R.string.connection_uid, row.uid?.toString().orEmpty())
            DetailField(R.string.connection_source, connectionEndpoint(row.sourceIP, row.sourcePort))
            DetailField(R.string.connection_destination, connectionEndpoint(row.destinationIP, row.destinationPort), if (row.destinationIP.isBlank()) null else { { ruleType = "ip" } })
            DetailField(R.string.connection_network, row.network)
            DetailField(R.string.connection_inbound, row.inbound)
            DetailField(R.string.connection_rule, row.matchedRule())
            DetailField(R.string.connection_actual_outbound, row.outbound)
            DetailField(R.string.connection_outbound, row.chains.joinToString(" → "))
            DetailField(R.string.connection_upload_speed, connectionSpeed(row.uploadRate))
            DetailField(R.string.connection_download_speed, connectionSpeed(row.downloadRate))
            DetailField(R.string.connection_upload, connectionBytes(row.upload))
            DetailField(R.string.connection_download, connectionBytes(row.download))
            DetailField(R.string.connection_start, runCatching { java.time.Instant.parse(row.start).atZone(java.time.ZoneId.systemDefault()).toLocalDateTime().toString() }.getOrDefault(row.start))
            DetailField(R.string.connection_id, row.id)
        }
    }
    ruleType?.let { ConnectionRuleDialog(row, it, state, model) { ruleType = null } }
}

@Composable
private fun DetailField(label: Int, value: String, add: (() -> Unit)? = null) {
    Column {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(stringResource(label), Modifier.weight(1f), color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.labelMedium)
            if (add != null) {
                val description = stringResource(R.string.add_connection_rule_for, stringResource(label))
                TextButton(add, modifier = Modifier.semantics { contentDescription = description }, enabled = value.isNotBlank(), shape = MaterialTheme.shapes.small) {
                    Text(stringResource(R.string.add_connection_rule))
                }
            }
        }
        SelectionContainer { Text(value.ifBlank { "—" }, style = MaterialTheme.typography.bodyMedium) }
        HorizontalDivider(Modifier.padding(top = 10.dp))
    }
}

@Composable
private fun ConnectionRuleDialog(row: ConnectionRecord, initial: String, state: AppState, model: JeemiViewModel, dismiss: () -> Unit) {
    var targetId by rememberSaveable { mutableStateOf("") }
    val newId = rememberSaveable { UUID.randomUUID().toString().replace("-", "") }
    val unnamed = stringResource(R.string.unnamed_ruleset)
    var name by rememberSaveable { mutableStateOf(generateSequence(1) { it + 1 }.map { unnamed + it }.first { n -> state.library.resources.none { it.name == n } }) }
    var type by rememberSaveable { mutableStateOf(initial) }
    fun seed(kind: String) = when (kind) { "domain" -> row.host; "ip" -> row.destinationIP.ifBlank { row.host }; else -> row.process }
    var value by rememberSaveable { mutableStateOf(seed(initial)) }
    var preview by remember { mutableStateOf<Pair<String, String>?>(null) }
    var invalid by remember { mutableStateOf(false) }
    val input = JSONObject().put("ruleSetId", targetId).put("name", name).put("matchType", type).put("value", value).toString()
    val resources = state.library.resources
    LaunchedEffect(input, resources) {
        preview = null; invalid = false; delay(180)
        try { preview = input to model.prepareConnectionRule(input, newId).second }
        catch (cancelled: CancellationException) { throw cancelled }
        catch (_: Exception) { invalid = true }
    }
    val existing = resources.filter { it.kind == ResourceKind.RULES && it.formatVersion >= 2 }.filter {
        val content = JSONObject(it.content)
        content.optString("sourceType") == "inline" && (content.optString("behavior") == "classical" ||
            type == "domain" && content.optString("behavior") == "domain" || type == "ip" && content.optString("behavior") == "ipcidr")
    }
    EditorDialog(stringResource(R.string.add_connection_rule), state.busy, dismiss, help = ConnectionRuleHelp, footer = {
        Button(onClick = { model.saveConnectionRule(input, newId, resources, dismiss) }, enabled = !state.busy && preview?.first == input && !invalid, shape = MaterialTheme.shapes.small) { Text(stringResource(R.string.save)) }
    }) {
        Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            ChoiceField(stringResource(R.string.rule_match_type), type,
                listOf("domain" to stringResource(R.string.rule_domain), "ip" to "IP/CIDR", "processName" to stringResource(R.string.application_package)), !state.busy) {
                type = it; value = seed(it); targetId = ""
            }
            OutlinedTextField(value, { value = it.take(4096) }, Modifier.fillMaxWidth(), label = { Text(stringResource(R.string.rule_match_value)) }, enabled = !state.busy)
            ChoiceField(stringResource(R.string.rule_sets), targetId, listOf("" to stringResource(R.string.new_rule_set)) + existing.map { it.id to it.name }, !state.busy) { targetId = it }
            if (targetId.isEmpty()) OutlinedTextField(name, { name = it.take(80) }, Modifier.fillMaxWidth(), singleLine = true, label = { Text(stringResource(R.string.name)) }, enabled = !state.busy)
            if (preview?.first == input) SelectionContainer { Text(preview!!.second, Modifier.padding(vertical = 16.dp), fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.bodySmall) }
            if (invalid) Text(stringResource(R.string.rule_entry_invalid), color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
        }
    }
}
