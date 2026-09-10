package io.jeemi.android.ui.components

import androidx.annotation.StringRes
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.HelpOutline
import androidx.compose.material.icons.outlined.Explore
import androidx.compose.material.icons.outlined.Lightbulb
import androidx.compose.material.icons.outlined.WarningAmber
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import io.jeemi.android.R

data class HelpContent(@param:StringRes val title: Int, @param:StringRes val purpose: Int,
    @param:StringRes val scenarios: Int, @param:StringRes val cautions: Int, @param:StringRes val extraCautions: Int? = null)

@Composable
fun FeatureHelp(content: HelpContent) {
    var open by rememberSaveable { mutableStateOf(false) }
    IconButton(onClick = { open = true }, modifier = Modifier.size(48.dp)) {
        Icon(Icons.AutoMirrored.Outlined.HelpOutline, stringResource(R.string.help_for, stringResource(content.title)),
            Modifier.size(19.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
    }
    if (open) {
        AlertDialog(
            onDismissRequest = { open = false },
            title = { Text(stringResource(content.title)) },
            text = {
                Column(Modifier.heightIn(max = 420.dp).verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(20.dp)) {
                    HelpSection(Icons.Outlined.Lightbulb, R.string.help_purpose, content.purpose)
                    HelpSection(Icons.Outlined.Explore, R.string.help_scenarios, content.scenarios)
                    HelpSection(Icons.Outlined.WarningAmber, R.string.help_cautions, content.cautions, content.extraCautions)
                }
            },
            confirmButton = { TextButton(onClick = { open = false }, shape = MaterialTheme.shapes.small) { Text(stringResource(R.string.close)) } },
        )
    }
}

@Composable
private fun HelpSection(icon: ImageVector, @StringRes title: Int, @StringRes body: Int, @StringRes extra: Int? = null) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Icon(icon, null, Modifier.size(20.dp), tint = MaterialTheme.colorScheme.primary)
            Text(stringResource(title), style = MaterialTheme.typography.titleSmall)
        }
        Text(stringResource(body), modifier = Modifier.padding(start = 30.dp),
            style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        extra?.let { Text(stringResource(it), modifier = Modifier.padding(start = 30.dp),
            style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant) }
    }
}

@Composable
fun SectionTitle(@StringRes title: Int, help: HelpContent? = null) {
    Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
        Text(stringResource(title), style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
        help?.let { FeatureHelp(it) }
    }
}

@Composable
fun JeemiCard(modifier: Modifier = Modifier, content: @Composable ColumnScope.() -> Unit) {
    Card(modifier = modifier, shape = MaterialTheme.shapes.large,
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)) {
        Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp), content = content)
    }
}
