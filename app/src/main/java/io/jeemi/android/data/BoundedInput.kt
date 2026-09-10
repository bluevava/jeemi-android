package io.jeemi.android.data

import java.io.ByteArrayOutputStream
import java.io.InputStream

// InputStream.readNBytes is unavailable on the Android 8 baseline.
fun InputStream.readBounded(limit: Int): ByteArray {
    val result = ByteArrayOutputStream()
    val buffer = ByteArray(8192)
    while (true) {
        val count = read(buffer, 0, minOf(buffer.size, limit + 1 - result.size()))
        if (count < 0) return result.toByteArray()
        result.write(buffer, 0, count)
        require(result.size() <= limit) { "input_size_limit" }
    }
}
