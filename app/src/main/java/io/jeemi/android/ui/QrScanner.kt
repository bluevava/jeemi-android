@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
package io.jeemi.android.ui

import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.google.zxing.BarcodeFormat
import com.google.zxing.ResultPoint
import com.journeyapps.barcodescanner.*
import io.jeemi.android.R
import io.jeemi.android.data.scannedSubscriptionUrl
import io.jeemi.android.ui.components.FeatureHelp

@Composable
internal fun QrScanner(dismiss: () -> Unit, fillUrl: (String) -> Unit) {
    val context = LocalContext.current
    val owner = LocalLifecycleOwner.current
    val onCode by rememberUpdatedState(fillUrl)
    var error by remember { mutableStateOf<Int?>(null) }
    var delivered by remember { mutableStateOf(false) }
    val scanner = remember { BarcodeView(context).apply { decoderFactory = DefaultDecoderFactory(listOf(BarcodeFormat.QR_CODE)) } }
    DisposableEffect(scanner, owner) {
        scanner.addStateListener(object : CameraPreview.StateListener {
            override fun previewSized() {}
            override fun previewStarted() {}
            override fun previewStopped() {}
            override fun cameraClosed() {}
            override fun cameraError(failure: Exception) { error = R.string.camera_unavailable }
        })
        scanner.decodeContinuous(object : BarcodeCallback {
            override fun barcodeResult(result: BarcodeResult) {
                if (delivered || !owner.lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) return
                val value = scannedSubscriptionUrl(result.text.orEmpty())
                if (value == null) error = R.string.qr_invalid_url
                else { delivered = true; scanner.pause(); onCode(value) }
            }
            override fun possibleResultPoints(points: List<ResultPoint>) {}
        })
        fun resume() { try { scanner.resume() } catch (_: Exception) { error = R.string.camera_unavailable } }
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME && !delivered) resume()
            if (event == Lifecycle.Event.ON_PAUSE) scanner.pause()
        }
        owner.lifecycle.addObserver(observer)
        if (owner.lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) resume()
        onDispose { owner.lifecycle.removeObserver(observer); scanner.pause(); scanner.stopDecoding() }
    }
    Dialog(dismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Scaffold(topBar = { TopAppBar(title = { Text(stringResource(R.string.scan_subscription_qr)) },
            navigationIcon = { IconButton(onClick = dismiss) { Icon(Icons.AutoMirrored.Outlined.ArrowBack, stringResource(R.string.back)) } },
            actions = { FeatureHelp(ScanHelp) }) }) { padding ->
            Box(Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.Center) {
                AndroidView(factory = { scanner }, modifier = Modifier.fillMaxSize())
                Box(Modifier.size(240.dp).border(2.dp, Color.White, MaterialTheme.shapes.large))
                error?.let { message ->
                    Surface(Modifier.align(Alignment.BottomCenter).padding(24.dp), shape = MaterialTheme.shapes.medium) {
                        Text(stringResource(message), Modifier.padding(16.dp), color = MaterialTheme.colorScheme.error)
                    }
                }
            }
        }
    }
}
