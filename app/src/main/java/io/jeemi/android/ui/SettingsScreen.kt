package io.jeemi.android.ui

import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import io.jeemi.android.R
import io.jeemi.android.domain.*
import io.jeemi.android.ui.components.*

@Composable
internal fun SettingsScreen(destination: Destination, state: AppState, model: JeemiViewModel,
    modifier: Modifier, dismiss: () -> Unit) {
    val preferences = state.library.preferences
    var theme by rememberSaveable { mutableStateOf(preferences.theme) }
    val selectedLanguage = selectedAppLanguage()
    var language by rememberSaveable { mutableStateOf(selectedLanguage) }
    var density by rememberSaveable { mutableStateOf(preferences.nodeDensity) }
    var sort by rememberSaveable { mutableStateOf(preferences.nodeSort) }
    var concurrency by rememberSaveable { mutableStateOf(preferences.testConcurrency.toString()) }
    var reset by rememberSaveable { mutableStateOf(preferences.connectionReset) }
    var geoMode by rememberSaveable { mutableStateOf(preferences.geoMode) }
    var loader by rememberSaveable { mutableStateOf(preferences.geoLoader) }
    var importingGeo by rememberSaveable { mutableStateOf("geoip-mmdb") }
    val uriHandler = LocalUriHandler.current
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) model.importGeo(importingGeo, uri)
    }
    Column(modifier) {
        LazyColumn(Modifier.weight(1f), contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
            if (destination == Destination.HOME) {
                item {
                    CollapsibleCard(stringResource(R.string.permissions), PermissionsHelp) { PermissionSettings() }
                }
                item {
                    CollapsibleCard(stringResource(R.string.appearance), AppearanceHelp) {
                        ChoiceField(stringResource(R.string.appearance), theme.name, AppTheme.entries.map {
                            it.name to stringResource(it.label)
                        }, !state.busy) { theme = AppTheme.valueOf(it) }
                    }
                }
                item {
                    CollapsibleCard(stringResource(R.string.language), AppearanceHelp) {
                        ChoiceField(stringResource(R.string.language), language.name, AppLanguage.entries.map {
                            it.name to stringResource(it.label)
                        }, !state.busy) { language = AppLanguage.valueOf(it) }
                    }
                }
                item {
                    CollapsibleCard(stringResource(R.string.core_management), CoreHelp) {
                        Text(stringResource(R.string.core_bundled_version), style = MaterialTheme.typography.titleMedium)
                        Text(stringResource(R.string.core_platform, Build.SUPPORTED_ABIS.first()), style = MaterialTheme.typography.bodySmall)
                        state.coreVersion?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
                        if (state.coreCheckFailed) Text(stringResource(R.string.core_check_failed), color = MaterialTheme.colorScheme.error)
                        OutlinedButton(onClick = model::checkCore, enabled = !state.busy && !state.coreChecking, shape = MaterialTheme.shapes.small) {
                            Text(stringResource(if (state.coreChecking) R.string.core_checking else R.string.core_check))
                        }
                        TextButton(onClick = { uriHandler.openUri("https://github.com/MetaCubeX/mihomo/releases") }, shape = MaterialTheme.shapes.small) {
                            Text(stringResource(R.string.core_release))
                        }
                    }
                }
                item {
                    CollapsibleCard(stringResource(R.string.geo_management), GeoHelp) {
                        ChoiceField(stringResource(R.string.geo_mode), geoMode, listOf("mmdb" to "MetaDB / MMDB", "dat" to "DAT"), !state.busy) { geoMode = it }
                        ChoiceField(stringResource(R.string.geo_loader), loader,
                            listOf("memconservative" to stringResource(R.string.geo_memory), "standard" to stringResource(R.string.geo_standard)), !state.busy) { loader = it }
                        listOf("geoip-mmdb" to "GeoIP · MetaDB", "geosite" to "GeoSite · DAT",
                            "asn" to "ASN · MMDB", "geoip-dat" to "GeoIP · DAT").forEach { (kind, title) ->
                            val asset = state.geo.firstOrNull { it.kind == kind }
                            HorizontalDivider()
                            Column {
                                Text(title, style = MaterialTheme.typography.titleSmall)
                                Text(stringResource(when (asset?.source) {
                                    "bundled" -> R.string.geo_bundled; "import" -> R.string.geo_imported
                                    "download" -> R.string.geo_downloaded; else -> R.string.geo_missing
                                }), style = MaterialTheme.typography.labelSmall)
                                asset?.let {
                                    Text(it.date.take(10) + " · " + "%.1f MiB".format(it.bytes / 1048576.0),
                                        style = MaterialTheme.typography.bodySmall)
                                    val digest = "SHA-256 " + it.sha256.take(16)
                                    Text(digest, style = MaterialTheme.typography.labelSmall)
                                }
                                Row {
                                    IconButton(onClick = { importingGeo = kind; picker.launch(arrayOf("*/*")) }, enabled = !state.busy) {
                                        Icon(Icons.Outlined.FileOpen, stringResource(R.string.geo_import))
                                    }
                                    IconButton(onClick = { model.updateGeo(kind) }, enabled = !state.busy) {
                                        Icon(Icons.Outlined.CloudDownload, stringResource(R.string.geo_update))
                                    }
                                }
                            }
                        }
                        OutlinedButton(onClick = model::restoreGeo, enabled = !state.busy && preferences.geoMode == "mmdb", shape = MaterialTheme.shapes.small) {
                            Text(stringResource(R.string.geo_restore))
                        }
                    }
                }
            } else {
                item {
                    JeemiCard(Modifier.fillMaxWidth()) {
                        SectionTitle(R.string.view_options, DisplayHelp)
                        ChoiceField(stringResource(R.string.node_density), density.name, NodeDensity.entries.map {
                            it.name to stringResource(when (it) {
                                NodeDensity.LARGE -> R.string.density_large; NodeDensity.MEDIUM -> R.string.density_medium; NodeDensity.SMALL -> R.string.density_small
                            })
                        }, !state.busy) { density = NodeDensity.valueOf(it) }
                        ChoiceField(stringResource(R.string.node_sort), sort.name, NodeSort.entries.map {
                            it.name to stringResource(when (it) {
                                NodeSort.ORIGINAL -> R.string.sort_original; NodeSort.NAME -> R.string.sort_name
                                NodeSort.TYPE -> R.string.sort_type; NodeSort.DELAY -> R.string.sort_delay
                            })
                        }, !state.busy) { sort = NodeSort.valueOf(it) }
                        OutlinedTextField(concurrency, { concurrency = it }, label = { Text(stringResource(R.string.test_concurrency)) },
                            singleLine = true, enabled = !state.busy, modifier = Modifier.fillMaxWidth())
                        ChoiceField(stringResource(R.string.connection_reset), reset, listOf("off" to stringResource(R.string.reset_off),
                            "selector" to stringResource(R.string.reset_selector), "all" to stringResource(R.string.reset_all)), !state.busy) { reset = it }
                    }
                }
            }
        }
        HorizontalDivider()
        Row(Modifier.fillMaxWidth().padding(16.dp), horizontalArrangement = Arrangement.End, verticalAlignment = Alignment.CenterVertically) {
            TextButton(onClick = dismiss, enabled = !state.busy, shape = MaterialTheme.shapes.small) { Text(stringResource(R.string.cancel)) }
            Spacer(Modifier.width(12.dp))
            Button(onClick = {
                model.savePreferences(preferences.copy(theme = theme, nodeDensity = density, nodeSort = sort,
                    testConcurrency = concurrency.toIntOrNull() ?: 0, connectionReset = reset, geoMode = geoMode, geoLoader = loader)) {
                    dismiss(); language.apply()
                }
            }, enabled = !state.busy, shape = MaterialTheme.shapes.small) { Text(stringResource(R.string.save)) }
        }
    }
}
