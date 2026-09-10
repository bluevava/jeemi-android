package io.jeemi.android.ui

import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.jeemi.android.R
import io.jeemi.android.domain.RuntimeState
import io.jeemi.android.runtime.CoreLog

@Suppress("DEPRECATION")
@Composable
internal fun LogsScreen(model: JeemiViewModel, configuredLevel: String, modifier: Modifier) {
    val feed by model.diagnostics.logs.collectAsStateWithLifecycle()
    val live by model.live.collectAsStateWithLifecycle()
    val runtime by model.runtime.collectAsStateWithLifecycle()
    var paused by rememberSaveable { mutableStateOf(false) }
    var follow by rememberSaveable { mutableStateOf(true) }
    var query by rememberSaveable { mutableStateOf("") }
    var filter by rememberSaveable { mutableStateOf("all") }
    var search by rememberSaveable { mutableStateOf(false) }
    var details by remember { mutableStateOf<CoreLog?>(null) }
    val list = rememberLazyListState()
    val clipboard = LocalClipboardManager.current
    ObserveDiagnostic(!paused, Triple(live?.sessionId, runtime, configuredLevel)) { model.diagnostics.observeLogs(configuredLevel) }
    val rows = feed.rows.filter { (filter == "all" || it.level == filter) && it.message.contains(query.trim(), ignoreCase = true) }
    LaunchedEffect(rows.lastOrNull()?.id, follow, query, filter) { if (follow && rows.isNotEmpty()) list.scrollToItem(rows.lastIndex) }
    Column(modifier.padding(horizontal = 12.dp)) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text(diagnosticStatus(if (runtime !is RuntimeState.Running) "offline" else if (paused) "paused" else feed.status),
                Modifier.weight(1f), style = MaterialTheme.typography.labelMedium)
            IconButton(onClick = { search = !search; if (!search) query = "" }) { Icon(Icons.Outlined.Search, stringResource(R.string.search_logs)) }
            IconButton(onClick = { paused = !paused }) { Icon(if (paused) Icons.Outlined.PlayArrow else Icons.Outlined.Pause, stringResource(if (paused) R.string.resume_feed else R.string.pause_feed)) }
            IconButton(onClick = model.diagnostics::clearLogs, enabled = feed.rows.isNotEmpty()) { Icon(Icons.Outlined.DeleteSweep, stringResource(R.string.clear_records)) }
            IconButton(onClick = { clipboard.setText(AnnotatedString(rows.joinToString("\n") { "${it.time} [${it.level}] ${it.message}" })) }, enabled = rows.isNotEmpty()) {
                Icon(Icons.Outlined.ContentCopy, stringResource(R.string.copy_visible_logs))
            }
        }
        if (search) OutlinedTextField(query, { query = it }, Modifier.fillMaxWidth(), singleLine = true, label = { Text(stringResource(R.string.search_logs)) })
        Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            listOf("all", "error", "warning", "info", "debug").forEach { level ->
                FilterChip(filter == level, { filter = level }, label = { Text(if (level == "all") stringResource(R.string.filter_all) else enumLabel(level)) })
            }
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            Checkbox(follow, { follow = it }); Text(stringResource(R.string.follow_logs), Modifier.weight(1f), style = MaterialTheme.typography.labelMedium)
            Text(rows.size.toString() + " / 500", style = MaterialTheme.typography.labelSmall)
        }
        LazyColumn(Modifier.weight(1f), state = list, contentPadding = PaddingValues(bottom = 12.dp)) {
            items(rows, key = { it.id }) { row ->
                Column(Modifier.fillMaxWidth().heightIn(min = 48.dp).clickable { details = row }.padding(vertical = 7.dp)) {
                    val tone = when (row.level) { "error" -> MaterialTheme.colorScheme.error; "warning" -> MaterialTheme.colorScheme.tertiary; else -> MaterialTheme.colorScheme.primary }
                    Text(row.time + " · " + enumLabel(row.level), style = MaterialTheme.typography.labelSmall, color = tone)
                    Text(row.message, maxLines = 3, overflow = TextOverflow.Ellipsis, fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.bodySmall)
                }
                HorizontalDivider(thickness = .5.dp)
            }
            if (rows.isEmpty()) item { Text(stringResource(R.string.no_results), Modifier.padding(vertical = 24.dp), style = MaterialTheme.typography.bodySmall) }
        }
    }
    details?.let { row -> EditorDialog(stringResource(R.string.log_details), false, { details = null }, help = LogsHelp,
        action = { IconButton(onClick = { clipboard.setText(AnnotatedString("${row.time} [${row.level}] ${row.message}")) }) { Icon(Icons.Outlined.ContentCopy, stringResource(R.string.copy_record)) } }) {
        SelectionContainer { Text(row.time + " · " + enumLabel(row.level) + "\n\n" + row.message,
            Modifier.verticalScroll(rememberScrollState()), style = MaterialTheme.typography.bodySmall, fontFamily = FontFamily.Monospace) }
    } }
}
