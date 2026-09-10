package io.jeemi.android.ui

import android.Manifest
import android.app.NotificationManager
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.net.VpnService
import android.provider.Settings
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.OpenInNew
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import io.jeemi.android.R

@Composable
internal fun PermissionSettings() {
    val context = LocalContext.current
    fun statuses(): List<Boolean> {
        val notifications = context.getSystemService(NotificationManager::class.java)
        return listOf(runCatching { VpnService.prepare(context) == null }.getOrDefault(false),
            notifications.areNotificationsEnabled() && notifications.getNotificationChannel("vpn")?.importance != NotificationManager.IMPORTANCE_NONE,
            ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED)
    }
    var allowed by remember { mutableStateOf(statuses()) }
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { allowed = statuses() }
    val titles = listOf(R.string.vpn_authorization, R.string.notification_permission, R.string.camera_permission)
    titles.forEachIndexed { index, title ->
        ListItem(headlineContent = { Text(stringResource(title)) },
            supportingContent = { Text(stringResource(if (allowed[index]) R.string.permission_granted else R.string.permission_not_granted)) },
            trailingContent = { IconButton(onClick = {
                val intent = when (index) {
                    0 -> Intent(Settings.ACTION_VPN_SETTINGS)
                    1 -> Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName)
                    else -> Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:" + context.packageName))
                }
                runCatching { context.startActivity(intent) }
            }) { Icon(Icons.AutoMirrored.Outlined.OpenInNew, stringResource(R.string.open_system_settings), Modifier.size(20.dp)) } })
    }
}
