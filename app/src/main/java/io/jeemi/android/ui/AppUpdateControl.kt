@file:OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
package io.jeemi.android.ui

import android.content.ClipData
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.core.content.FileProvider
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.jeemi.android.BuildConfig
import io.jeemi.android.R
import io.jeemi.android.ui.components.FeatureHelp
import io.jeemi.android.ui.components.HelpContent
import io.jeemi.android.update.*

internal val AppUpdateHelp = HelpContent(R.string.app_update_check, R.string.help_app_update_purpose,
    R.string.help_app_update_scenarios, R.string.help_app_update_cautions)

/** Keep launchers outside LazyColumn items so scrolling cannot drop an installation result. */
@Composable
internal fun rememberAppUpdate(updater: AppUpdater): AppUpdateState {
    val context = LocalContext.current
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val phase by lifecycle.currentStateFlow.collectAsState()
    val state by updater.state.collectAsStateWithLifecycle()
    var permissionDigest by rememberSaveable { mutableStateOf<String?>(null) }
    val installer = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) {
        // The system owns installation. Cancellation leaves the verified APK available for an explicit retry.
    }
    val permission = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) {
        val expected = permissionDigest
        permissionDigest = null
        val ready = updater.state.value as? AppUpdateState.Ready
        if (expected != null && ready?.update?.release?.asset?.sha256 == expected) {
            if (context.packageManager.canRequestPackageInstalls()) updater.install()
            else updater.installationProblem(UpdateProblem.PERMISSION)
        }
    }
    DisposableEffect(lifecycle, updater) {
        val observer = LifecycleEventObserver { _, event -> if (event == Lifecycle.Event.ON_STOP) updater.leaveForeground() }
        lifecycle.addObserver(observer)
        onDispose { lifecycle.removeObserver(observer); updater.leaveForeground() }
    }
    LaunchedEffect(state, phase) {
        if (phase != Lifecycle.State.RESUMED) return@LaunchedEffect
        val update = updater.takeInstallRequest() ?: return@LaunchedEffect
        try {
            if (!context.packageManager.canRequestPackageInstalls()) {
                permissionDigest = update.release.asset.sha256
                permission.launch(Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:${context.packageName}")))
            } else {
                val uri = FileProvider.getUriForFile(context, "${context.packageName}.updates", update.file)
                installer.launch(Intent(Intent.ACTION_VIEW).setDataAndType(uri, "application/vnd.android.package-archive")
                    .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION).apply { clipData = ClipData.newRawUri("APK", uri) })
            }
        } catch (_: android.content.ActivityNotFoundException) {
            permissionDigest = null
            updater.installationProblem(UpdateProblem.INSTALLER)
        } catch (_: SecurityException) {
            permissionDigest = null
            updater.installationProblem(UpdateProblem.INSTALLER)
        } catch (_: IllegalArgumentException) {
            permissionDigest = null
            updater.installationProblem(UpdateProblem.PACKAGE)
        }
    }
    return state
}

@Composable
internal fun AppUpdateControl(state: AppUpdateState, updater: AppUpdater) {
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text(stringResource(R.string.app_update_version, BuildConfig.VERSION_NAME), Modifier.weight(1f),
                style = MaterialTheme.typography.bodySmall)
            FeatureHelp(AppUpdateHelp)
        }
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(onClick = updater::check, enabled = state !is AppUpdateState.Working,
                modifier = Modifier.testTag("app-update-check").heightIn(min = 48.dp), shape = MaterialTheme.shapes.small) {
                Text(stringResource(R.string.app_update_check))
            }
            if (state is AppUpdateState.Working)
                TextButton(onClick = updater::cancel, modifier = Modifier.heightIn(min = 48.dp), shape = MaterialTheme.shapes.small) {
                    Text(stringResource(R.string.cancel))
                }
            if (state is AppUpdateState.Ready)
                Button(onClick = updater::install, modifier = Modifier.testTag("app-update-install").heightIn(min = 48.dp), shape = MaterialTheme.shapes.small) {
                    Text(stringResource(R.string.app_update_install))
                }
        }
        val status = when (state) {
            is AppUpdateState.Working -> when (state.progress.phase) {
                UpdatePhase.CHECKING -> stringResource(R.string.app_update_checking)
                UpdatePhase.VERIFYING -> stringResource(R.string.app_update_verifying)
                UpdatePhase.DOWNLOADING -> stringResource(R.string.app_update_downloading, state.progress.version,
                    (state.progress.received * 100 / state.progress.total.coerceAtLeast(1)).toInt())
            }
            is AppUpdateState.Current -> stringResource(R.string.app_update_current, state.latest)
            is AppUpdateState.Ready -> stringResource(R.string.app_update_ready, state.update.release.version.toString())
            AppUpdateState.Cancelled -> stringResource(R.string.app_update_cancelled)
            else -> null
        }
        status?.let { Text(it, style = MaterialTheme.typography.bodySmall, modifier = Modifier.testTag("app-update-status")) }
        if (state is AppUpdateState.Working && state.progress.phase == UpdatePhase.DOWNLOADING)
            LinearProgressIndicator(progress = { (state.progress.received.toFloat() / state.progress.total.coerceAtLeast(1)).coerceIn(0f, 1f) },
                modifier = Modifier.fillMaxWidth())
        val error = when (state) {
            is AppUpdateState.Failed -> state.problem
            is AppUpdateState.Ready -> state.problem
            else -> null
        }
        error?.let { Text(stringResource(when (it) {
            UpdateProblem.NETWORK -> R.string.app_update_network_error
            UpdateProblem.NO_RELEASE -> R.string.app_update_no_release
            UpdateProblem.RELEASE -> R.string.app_update_release_error
            UpdateProblem.INCOMPATIBLE -> R.string.app_update_incompatible
            UpdateProblem.PACKAGE -> R.string.app_update_package_error
            UpdateProblem.STORAGE -> R.string.app_update_storage_error
            UpdateProblem.PERMISSION -> R.string.app_update_permission_error
            UpdateProblem.INSTALLER -> R.string.app_update_installer_error
        }), color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
    }
}
