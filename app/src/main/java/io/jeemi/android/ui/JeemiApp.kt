@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
package io.jeemi.android.ui

import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.*
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import io.jeemi.android.R
import io.jeemi.android.domain.*
import io.jeemi.android.ui.components.*

internal val Destination.label: Int get() = when (this) {
    Destination.HOME -> R.string.home
    Destination.SUBSCRIPTIONS -> R.string.subscriptions
    Destination.CONFIG -> R.string.configuration
    Destination.MORE -> R.string.more_navigation
}
private val Destination.icon: ImageVector get() = when (this) {
    Destination.HOME -> Icons.Outlined.Home
    Destination.SUBSCRIPTIONS -> Icons.Outlined.Layers
    Destination.CONFIG -> Icons.Outlined.Tune
    Destination.MORE -> Icons.Outlined.MoreHoriz
}
private val Destination.help: HelpContent get() = when (this) {
    Destination.HOME -> ConnectionHelp
    Destination.SUBSCRIPTIONS -> ImportHelp
    Destination.CONFIG -> ConfigHelp
    Destination.MORE -> ToolsHelp
}

@Composable
fun JeemiApp(state: AppState, model: JeemiViewModel) {
    val context = LocalContext.current
    // The brand bitmap has no locale variants. Decode it independently of the
    // Drawable cache, which can be invalidated during an in-place locale change.
    val logo = remember {
        android.graphics.BitmapFactory.decodeResource(context.resources, R.drawable.jeemi_logo,
            android.graphics.BitmapFactory.Options().apply { inSampleSize = 4 })?.asImageBitmap().also {
            if (it == null) android.util.Log.w("Jeemi", "Bundled logo decode failed; using the vector icon")
        }
    }
    var destination by rememberSaveable { mutableStateOf(Destination.HOME) }
    var settingsOpen by rememberSaveable { mutableStateOf(false) }
    var moreOpen by rememberSaveable { mutableStateOf(false) }
    var morePage by rememberSaveable { mutableStateOf("connections") }
    var importRequested by rememberSaveable { mutableStateOf(false) }
    val authorization = rememberVpnAuthorization(model)
    val level = runCatching { org.json.JSONObject(state.library.preferences.runtimeJson).optString("logLevel", "silent") }.getOrDefault("silent")
    val runtime by model.runtime.collectAsState()
    LaunchedEffect(level, runtime) { if (state.loaded) model.diagnostics.applyLogLevel(level) }
    LaunchedEffect(level) { if (level == "silent" && destination == Destination.MORE && morePage == "logs") destination = Destination.HOME }
    val pageLabel = if (destination != Destination.MORE) destination.label else when (morePage) {
        "connections" -> R.string.connections; "logs" -> R.string.logs; else -> R.string.tools
    }
    val pageHelp = if (destination != Destination.MORE) destination.help else when (morePage) {
        "connections" -> ConnectionsHelp; "logs" -> LogsHelp; else -> DnsHelp
    }
    val pageState = rememberSaveableStateHolder()
    BackHandler(destination != Destination.HOME && !settingsOpen) { destination = Destination.HOME }
    BackHandler(settingsOpen && !state.busy) { settingsOpen = false }
    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            TopAppBar(
                title = {
                    if (settingsOpen) Text(stringResource(if (destination == Destination.HOME) R.string.home_settings else R.string.subscription_settings),
                        style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    else Row(verticalAlignment = Alignment.CenterVertically) {
                        if (logo != null) Image(logo, null, Modifier.size(28.dp))
                        else Icon(painterResource(R.drawable.ic_vpn), null, Modifier.size(28.dp))
                        Text(stringResource(R.string.app_name), Modifier.padding(start = 8.dp), style = MaterialTheme.typography.titleMedium)
                        FeatureHelp(pageHelp)
                        Text(stringResource(pageLabel), style = MaterialTheme.typography.titleSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                },
                navigationIcon = { if (settingsOpen) IconButton(onClick = { settingsOpen = false }, enabled = !state.busy) {
                    Icon(Icons.AutoMirrored.Outlined.ArrowBack, stringResource(R.string.back))
                } },
                actions = {
                    if (state.downloadBusy) TextButton(onClick = model::cancelDownload, shape = MaterialTheme.shapes.small) { Text(stringResource(R.string.cancel)) }
                    val restarting = runtime is RuntimeState.Running || runtime == RuntimeState.Restarting
                    val quickAction = stringResource(if (restarting) R.string.vpn_restart else R.string.vpn_quick_start)
                    IconButton(onClick = { authorization.request(runtime is RuntimeState.Running) },
                        enabled = state.loaded && !state.busy && state.candidate != null && !state.projectionFailed &&
                            !runtime.transitioning && !authorization.requesting) {
                        if (runtime.transitioning) CircularProgressIndicator(Modifier.size(20.dp).semantics { contentDescription = quickAction }, strokeWidth = 2.dp)
                        else Icon(if (restarting) Icons.Outlined.Refresh else Icons.Outlined.PlayArrow, quickAction)
                    }
                    if (!settingsOpen && destination in listOf(Destination.HOME, Destination.SUBSCRIPTIONS))
                    IconButton(onClick = { settingsOpen = true }, enabled = state.loaded && !state.busy) {
                        Icon(Icons.Outlined.Settings, stringResource(R.string.settings))
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.background),
            )
        },
        bottomBar = {
            if (!settingsOpen) NavigationBar(containerColor = MaterialTheme.colorScheme.surface) {
                Destination.entries.forEach { item ->
                    NavigationBarItem(selected = destination == item, onClick = { if (item == Destination.MORE) moreOpen = true else destination = item },
                        icon = { Icon(item.icon, null) },
                        label = { Text(stringResource(item.label), maxLines = 1, softWrap = false, textAlign = TextAlign.Center, overflow = TextOverflow.Ellipsis) })
                }
            }
        },
    ) { padding ->
        Box(Modifier.fillMaxSize().padding(padding).consumeWindowInsets(padding), contentAlignment = Alignment.TopCenter) {
            if (!state.loaded) {
                if (state.error == AppError.LOAD) Text(stringResource(R.string.load_error), Modifier.padding(24.dp))
                else CircularProgressIndicator(Modifier.padding(24.dp))
            } else if (settingsOpen) SettingsScreen(destination, state, model, Modifier.widthIn(max = 680.dp).fillMaxSize()) { settingsOpen = false }
            else pageState.SaveableStateProvider(destination) {
                // A single portrait column on both phone and tablet; no alternate rail or landscape route.
                val pageModifier = Modifier.widthIn(max = 680.dp).fillMaxSize()
                when (destination) {
                    Destination.HOME -> HomeScreen(state, model, pageModifier, authorization.requesting,
                        startVpn = { authorization.request(false) }) { import ->
                        importRequested = import; destination = Destination.SUBSCRIPTIONS
                    }
                    Destination.SUBSCRIPTIONS -> SubscriptionScreen(state, model, pageModifier,
                        importRequested = importRequested, onImportHandled = { importRequested = false })
                    Destination.CONFIG -> ConfigurationScreen(state, model, pageModifier)
                    Destination.MORE -> when (morePage) {
                        "connections" -> ConnectionsScreen(state, model, pageModifier)
                        "logs" -> LogsScreen(model, level, pageModifier)
                        else -> ToolsScreen(model, pageModifier)
                    }
                }
            }
            if (state.busy) LinearProgressIndicator(Modifier.fillMaxWidth())
        }
    }
    if (moreOpen) ModalBottomSheet(onDismissRequest = { moreOpen = false }) {
        Column(Modifier.fillMaxWidth().navigationBarsPadding().padding(bottom = 12.dp)) {
            listOf("connections" to R.string.connections, "logs" to R.string.logs, "tools" to R.string.tools)
                .filter { it.first != "logs" || level != "silent" }.forEach { (page, label) ->
                    TextButton(onClick = { morePage = page; destination = Destination.MORE; moreOpen = false }, modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp), shape = MaterialTheme.shapes.small) {
                        Icon(when (page) { "connections" -> Icons.Outlined.TableRows; "logs" -> Icons.Outlined.Article; else -> Icons.Outlined.Build }, null)
                        Spacer(Modifier.width(12.dp)); Text(stringResource(label))
                    }
                }
        }
    }
    val exportPicker = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("text/plain")) { uri ->
        if (uri != null) model.export(uri)
    }
    state.preview?.let { yaml ->
        EditorDialog(stringResource(state.previewTitle), state.busy, model::dismissPreview, action = {
            TextButton(onClick = { model.prepareExport(); exportPicker.launch(if (state.previewTitle == R.string.resource_package) "jeemi-package.json" else "jeemi-document.yaml") }, enabled = !state.busy, shape = MaterialTheme.shapes.small) { Text(stringResource(R.string.export)) }
        }, help = if (state.previewTitle == R.string.configuration_preview) PreviewHelp else DocumentHelp) {
            if (yaml.length > 65536) Text(stringResource(R.string.preview_truncated), style = MaterialTheme.typography.labelMedium)
            SelectionContainer {
                Text(yaml.take(65536), Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(bottom = 24.dp),
                    fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.bodySmall)
            }
        }
    }
    state.conversionReport?.let { ConversionReportDialog(it) { model.showConversionReport(null) } }
    state.chainComposition?.let { ChainCompositionDialog(it, state, model::dismissChainComposition) }
    state.error?.takeUnless { it == AppError.LOAD }?.let { error ->
        AlertDialog(onDismissRequest = model::dismissError, title = { Text(stringResource(R.string.operation_failed)) },
            text = { Text(state.businessIssue?.let { businessIssueText(it) } ?: stringResource(when (error) {
                AppError.IMPORT -> R.string.import_error
                AppError.SAVE -> R.string.save_error
                AppError.PREVIEW -> R.string.preview_error
                AppError.LOAD -> R.string.load_error
                AppError.RESOURCE -> R.string.resource_error
                AppError.NETWORK -> R.string.network_error
                AppError.EXPORT -> R.string.export_error
                AppError.CORE -> R.string.core_error
            })) }, confirmButton = { TextButton(onClick = model::dismissError, shape = MaterialTheme.shapes.small) { Text(stringResource(R.string.close)) } })
    }
}
