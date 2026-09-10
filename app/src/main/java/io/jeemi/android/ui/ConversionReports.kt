package io.jeemi.android.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import io.jeemi.android.R
import io.jeemi.android.data.BusinessIssue
import org.json.JSONObject

@Composable
internal fun ConversionReportDialog(raw: String, dismiss: () -> Unit) {
    val report = remember(raw) { runCatching { JSONObject(raw) }.getOrDefault(JSONObject()) }
    val diagnostics = remember(raw) { report.objects("diagnostics") }
    EditorDialog(stringResource(R.string.conversion_details), false, dismiss, help = ConversionHelp) {
        LazyColumn(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(10.dp), contentPadding = PaddingValues(bottom = 24.dp)) {
            item { Text(stringResource(R.string.conversion_summary, report.optString("format", "—"), report.optInt("proxyCount"), report.optInt("skippedNodes"))) }
            if (report.optString("requiredCore").isNotBlank()) item { Text(stringResource(R.string.conversion_required_core, report.getString("requiredCore"))) }
            if (diagnostics.isEmpty()) item { Text(stringResource(R.string.conversion_no_issues), style = MaterialTheme.typography.bodySmall) }
            items(diagnostics.take(100)) { entry ->
                Text(businessIssueText(BusinessIssue("conversion_" + entry.optString("code"), entry.optInt("line"))), style = MaterialTheme.typography.bodyMedium)
                HorizontalDivider()
            }
            if (diagnostics.size > 100) item { Text(stringResource(R.string.conversion_more, diagnostics.size - 100)) }
        }
    }
}

@Composable
internal fun ChainCompositionDialog(raw: String, state: AppState, dismiss: () -> Unit) {
    val report = remember(raw) { JSONObject(raw) }
    val diagnostics = remember(raw) { report.objects("diagnostics") }
    EditorDialog(stringResource(R.string.chain_generation_details), false, dismiss, help = ChainHelp) {
        LazyColumn(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(10.dp), contentPadding = PaddingValues(bottom = 24.dp)) {
            item { Text(stringResource(R.string.chain_generated_count, report.optInt("generated")), style = MaterialTheme.typography.titleSmall) }
            items(diagnostics.take(100)) { entry ->
                Text(state.chains.groups.firstOrNull { it.id == entry.optString("groupId") }?.name.orEmpty(), style = MaterialTheme.typography.labelMedium)
                Text(stringResource(businessIssueResource("chain_diag_" + entry.optString("code")), entry.optString("selector"), entry.optInt("count")))
                HorizontalDivider()
            }
            if (diagnostics.size > 100) item { Text(stringResource(R.string.conversion_more, diagnostics.size - 100)) }
        }
    }
}
