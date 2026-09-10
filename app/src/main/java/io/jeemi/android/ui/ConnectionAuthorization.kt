package io.jeemi.android.ui

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.pm.PackageManager
import android.net.VpnService
import android.os.Build
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.ContextCompat
import io.jeemi.android.R

@Composable
internal fun rememberVpnAuthorization(model: JeemiViewModel): VpnAuthorization {
    val context = LocalContext.current
    val preferences = remember { context.getSharedPreferences("permission-prompts", Context.MODE_PRIVATE) }
    var requesting by rememberSaveable { mutableStateOf(false) }
    var restartRequested by rememberSaveable { mutableStateOf(false) }
    fun startPrepared() {
        requesting = false
        if (VpnService.prepare(context) == null) {
            if (restartRequested) model.restartVpn() else model.startVpn()
        }
        else Toast.makeText(context, R.string.vpn_permission_denied, Toast.LENGTH_LONG).show()
    }
    val notifications = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {
        // Notification refusal does not revoke VPN consent or forbid a foreground service.
        startPrepared()
    }
    fun afterVpnConsent() {
        if (Build.VERSION.SDK_INT >= 33 && ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED &&
            !preferences.getBoolean("notifications", false)) {
            preferences.edit().putBoolean("notifications", true).apply()
            notifications.launch(Manifest.permission.POST_NOTIFICATIONS)
        } else startPrepared()
    }
    val vpn = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        if (result.resultCode == Activity.RESULT_OK) afterVpnConsent()
        else { requesting = false; Toast.makeText(context, R.string.vpn_permission_denied, Toast.LENGTH_LONG).show() }
    }
    return VpnAuthorization(requesting) { restart ->
        if (!requesting) {
            requesting = true
            restartRequested = restart
            try {
                val intent = VpnService.prepare(context)
                if (intent == null) afterVpnConsent() else vpn.launch(intent)
            } catch (_: Exception) {
                requesting = false
                Toast.makeText(context, R.string.vpn_permission_unavailable, Toast.LENGTH_LONG).show()
            }
        }
    }
}

internal data class VpnAuthorization(val requesting: Boolean, val request: (restart: Boolean) -> Unit)
