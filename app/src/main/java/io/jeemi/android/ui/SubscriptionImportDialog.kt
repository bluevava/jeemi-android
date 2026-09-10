@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
package io.jeemi.android.ui

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import io.jeemi.android.R
import io.jeemi.android.data.scannedSubscriptionUrl
import io.jeemi.android.domain.*
import io.jeemi.android.ui.components.*
import kotlinx.coroutines.launch

@Composable
internal fun SubscriptionImportDialog(item: Subscription?, state: AppState, model: JeemiViewModel, dismiss: () -> Unit) {
    var name by rememberSaveable(item?.id) { mutableStateOf(item?.name.orEmpty()) }
    var url by rememberSaveable(item?.id) { mutableStateOf(item?.sourceUrl.orEmpty()) }
    var description by rememberSaveable(item?.id) { mutableStateOf(item?.description.orEmpty()) }
    var icon by rememberSaveable(item?.id) { mutableStateOf(item?.icon.orEmpty()) }
    var handler by rememberSaveable(item?.id) { mutableStateOf(item?.handlerId) }
    var method by rememberSaveable(item?.id) { mutableIntStateOf(if (item == null) 0 else 1) }
    var iconEditor by rememberSaveable { mutableStateOf(false) }
    var scanning by rememberSaveable { mutableStateOf(false) }
    var cameraDenied by rememberSaveable { mutableStateOf(false) }
    var detecting by remember { mutableStateOf(false) }
    var detectionError by remember { mutableStateOf(false) }
    val draft by model.importDraft.collectAsState()
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val permission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        scanning = granted; cameraDenied = !granted
    }
    fun scan() {
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) scanning = true
        else permission.launch(Manifest.permission.CAMERA)
    }
    fun detectIcon() {
        if (detecting || scannedSubscriptionUrl(url) == null) return
        val source = url; val originalIcon = icon
        detecting = true; detectionError = false
        scope.launch {
            try {
                val detected = runCatching { model.detectSubscriptionIcon(source) }.getOrNull()
                if (url == source && icon == originalIcon) {
                    if (detected != null) icon = detected else detectionError = true
                }
            } finally { detecting = false }
        }
    }
    EditorDialog(stringResource(if (item == null) R.string.import_subscription else R.string.edit), state.busy, dismiss, action = {
        if (state.downloadBusy) TextButton(onClick = model::cancelDownload, shape = MaterialTheme.shapes.small) { Text(stringResource(R.string.cancel)) }
        TextButton(onClick = {
            if (method == 0 && item == null) model.importUrl(name, url, description, icon, handler)
            else model.importText(name, draft, description, if (item != null) url else "", item?.id, icon, handler, true)
        }, enabled = !state.busy && !detecting && (if (method == 0 && item == null) scannedSubscriptionUrl(url) != null else draft.isNotBlank()), shape = MaterialTheme.shapes.small) {
            Text(stringResource(if (item == null) R.string.import_action else R.string.save))
        }
    }, help = ImportHelp) {
        Column(Modifier.verticalScroll(rememberScrollState()).padding(bottom = 20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            if (item == null) SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
                listOf(R.string.from_url, R.string.from_text).forEachIndexed { i, label ->
                    SegmentedButton(method == i, { method = i }, SegmentedButtonDefaults.itemShape(i, 2), enabled = !state.busy) { Text(stringResource(label)) }
                }
            }
            OutlinedTextField(name, { name = it.take(80) }, Modifier.fillMaxWidth(), label = { Text(stringResource(R.string.subscription_name_optional)) }, singleLine = true, enabled = !state.busy)
            if (method == 0 || item != null) OutlinedTextField(url, { url = it.take(8192) }, Modifier.fillMaxWidth(),
                label = { Text(stringResource(R.string.subscription_url)) }, singleLine = true, enabled = !state.busy,
                trailingIcon = { IconButton(onClick = ::scan, enabled = !state.busy) { Icon(Icons.Outlined.QrCodeScanner, stringResource(R.string.scan_subscription_qr)) } })
            Row {
                OutlinedButton(onClick = { iconEditor = true; if (icon.isBlank() && url.isNotBlank() && method == 0) detectIcon() }, enabled = !state.busy, shape = MaterialTheme.shapes.small) {
                    SubscriptionIcon(icon, if (icon == item?.icon) item.iconImage else "")
                    Spacer(Modifier.width(8.dp)); Text(stringResource(R.string.subscription_icon))
                }
                FeatureHelp(IconHelp)
            }
            ChoiceField(stringResource(R.string.associations), handler.orEmpty(), listOf("" to stringResource(R.string.no_handler)) +
                state.library.resources.filter { it.kind in listOf(ResourceKind.CONFIG, ResourceKind.SCRIPT) }.map { it.id to it.name }, !state.busy) { handler = it.ifEmpty { null } }
            OutlinedTextField(description, { description = it.take(500) }, Modifier.fillMaxWidth(), label = { Text(stringResource(R.string.description)) }, enabled = !state.busy)
            if (method == 1 || item != null) OutlinedTextField(draft, { model.importDraft.value = it }, Modifier.fillMaxWidth(), minLines = 10,
                label = { Text(stringResource(R.string.subscription_content)) }, enabled = !state.busy,
                textStyle = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace))
        }
    }
    if (iconEditor) AlertDialog(onDismissRequest = { iconEditor = false }, title = { SectionTitle(R.string.subscription_icon, IconHelp) },
        text = { Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            OutlinedTextField(icon, { icon = it.take(8192); detectionError = false }, Modifier.fillMaxWidth(), singleLine = true,
                label = { Text(stringResource(R.string.icon_value)) }, trailingIcon = { IconButton(onClick = { icon = "" }) { Icon(Icons.Outlined.Clear, stringResource(R.string.clear)) } })
            LazyVerticalGrid(GridCells.Adaptive(48.dp), Modifier.fillMaxWidth().heightIn(max = 240.dp)) {
                items(subscriptionEmojis) { emoji -> TextButton(onClick = { icon = emoji; detectionError = false }, contentPadding = PaddingValues(0.dp), modifier = Modifier.size(48.dp), shape = MaterialTheme.shapes.small) { Text(emoji) } }
            }
            TextButton(onClick = ::detectIcon, enabled = !detecting && scannedSubscriptionUrl(url) != null && (method == 0 || item?.sourceUrl?.isNotBlank() == true), shape = MaterialTheme.shapes.small) {
                if (detecting) CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp) else Icon(Icons.Outlined.ImageSearch, null)
                Spacer(Modifier.width(8.dp)); Text(stringResource(R.string.detect_subscription_icon))
            }
            if (detectionError) Text(stringResource(R.string.icon_not_found), color = MaterialTheme.colorScheme.error)
        } }, confirmButton = { TextButton(onClick = { iconEditor = false }, shape = MaterialTheme.shapes.small) { Text(stringResource(R.string.close)) } })
    if (scanning) QrScanner({ scanning = false }) { url = it; scanning = false }
    if (cameraDenied) AlertDialog(onDismissRequest = { cameraDenied = false }, title = { Text(stringResource(R.string.scan_subscription_qr)) },
        text = { Text(stringResource(R.string.camera_permission_denied)) },
        dismissButton = { TextButton(onClick = { cameraDenied = false }, shape = MaterialTheme.shapes.small) { Text(stringResource(R.string.cancel)) } },
        confirmButton = { TextButton(onClick = {
            cameraDenied = false
            context.startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:" + context.packageName)))
        }, shape = MaterialTheme.shapes.small) { Text(stringResource(R.string.open_system_settings)) } })
}

// Uses the device emoji font and accepts input from the keyboard emoji picker.
private val subscriptionEmojis = listOf("🌐", "🚀", "⚡", "🛡️", "🔒", "✈️", "☁️", "🏠", "⭐", "🔥", "💎", "🌈", "🍀", "🎯", "🎮", "🎬", "🎵", "💬", "💻", "📱", "🐱", "🐶", "🦊", "🐼", "🐯", "🐰", "🐻", "🦁", "🐸", "🐧", "🦉", "🦄", "🐳", "🐬", "🐠", "🦈", "🐙", "🐢", "🐨", "🐵", "🦋", "🐝", "🐲", "🍉", "🍎", "🎧", "🚢", "🛰️")
