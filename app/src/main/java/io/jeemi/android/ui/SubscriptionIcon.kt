package io.jeemi.android.ui

import android.graphics.BitmapFactory
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import java.util.Base64

@Composable
internal fun SubscriptionIcon(icon: String, image: String = "") {
    val bitmap = remember(image) {
        if (image.length !in 1..65536) null else runCatching {
            val bytes = Base64.getDecoder().decode(image)
            BitmapFactory.decodeByteArray(bytes, 0, bytes.size)?.asImageBitmap()
        }.getOrNull()
    }
    if (bitmap != null && icon.startsWith("http", true)) Image(bitmap, null, Modifier.size(26.dp))
    else Text(if (icon.isBlank() || icon.startsWith("http", true)) "🌐" else icon, fontSize = 24.sp, maxLines = 1)
}
