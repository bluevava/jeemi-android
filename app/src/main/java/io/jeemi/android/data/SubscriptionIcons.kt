package io.jeemi.android.data

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import java.io.ByteArrayOutputStream
import java.net.HttpURLConnection
import java.net.URI
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.Base64
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit

internal data class SubscriptionImage(val url: String, val image: String)

/** Desktop origin-only discovery; no subscription query, authorization or Referer is sent. */
internal class SubscriptionIcons {
    private val cache = ConcurrentHashMap<String, SubscriptionImage>()
    @Volatile private var active: HttpURLConnection? = null
    fun cancel() { active?.disconnect() }

    fun detect(source: String): SubscriptionImage? {
        val parsed = requireSubscriptionUrl(source)
        val origin = URI(parsed.scheme, parsed.rawAuthority, null, null, null)
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(6)
        for (path in listOf("/favicon.ico", "/favicon.png", "/sub.ico", "/sub.png")) {
            if (System.nanoTime() >= deadline) break
            load(origin.resolve(path).toASCIIString(), deadline)?.let { return it }
        }
        return null
    }

    fun load(address: String, deadline: Long = System.nanoTime() + TimeUnit.SECONDS.toNanos(6),
        cancelled: () -> Boolean = { false }): SubscriptionImage? {
        cache[address]?.let { return it }
        return runCatching {
            val origin = requireSubscriptionUrl(address)
            var current = origin
            repeat(6) {
                val remaining = TimeUnit.NANOSECONDS.toMillis(deadline - System.nanoTime()).coerceAtMost(1500).toInt()
                require(remaining > 0 && !cancelled())
                val connection = current.toURL().openConnection() as HttpURLConnection
                active = connection
                try {
                    require(!cancelled())
                    connection.connectTimeout = remaining; connection.readTimeout = remaining
                    connection.instanceFollowRedirects = false; connection.useCaches = false
                    connection.setRequestProperty("Accept", "image/png,image/x-icon,image/vnd.microsoft.icon,image/*;q=0.8")
                    connection.setRequestProperty("User-Agent", "Jeemi subscription icon detector")
                    val status = connection.responseCode
                    if (status in listOf(301, 302, 303, 307, 308)) {
                        val next = requireSubscriptionUrl(current.resolve(connection.getHeaderField("Location") ?: error("redirect")).toString())
                        require(next.scheme.equals(origin.scheme, true) && next.rawAuthority.equals(origin.rawAuthority, true))
                        current = next
                    } else {
                        require(status in 200..299 && connection.contentLengthLong <= 512 * 1024)
                        val bytes = connection.inputStream.use { stream ->
                            val output = ByteArrayOutputStream(); val buffer = ByteArray(4096)
                            while (true) {
                                require(System.nanoTime() < deadline && !cancelled())
                                val count = stream.read(buffer)
                                if (count < 0) break
                                require(output.size() + count <= 512 * 1024)
                                output.write(buffer, 0, count)
                            }
                            output.toByteArray()
                        }
                        require(!cancelled())
                        val image = encodeSubscriptionImage(bytes) ?: return null
                        val result = SubscriptionImage(address, image)
                        if (cache.size >= 64) cache.keys.firstOrNull()?.let(cache::remove)
                        cache[address] = result
                        return result
                    }
                } finally { connection.disconnect(); if (active === connection) active = null }
            }
            null
        }.getOrNull()
    }
}

internal fun encodeSubscriptionImage(bytes: ByteArray): String? = runCatching {
    require(bytes.isNotEmpty() && bytes.size <= 512 * 1024)
    val bitmap = decodeIcon(bytes) ?: return null
    try {
        val scaled = Bitmap.createScaledBitmap(bitmap, 64, 64, true)
        val output = ByteArrayOutputStream()
        try { check(scaled.compress(Bitmap.CompressFormat.PNG, 100, output)) }
        finally { if (scaled !== bitmap) scaled.recycle() }
        Base64.getEncoder().encodeToString(output.toByteArray())
    } finally { bitmap.recycle() }
}.getOrNull()

private fun decodeIcon(bytes: ByteArray): Bitmap? {
    val data = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
    if (bytes.size >= 6 && data.getShort(0).toInt() == 0 && data.getShort(2).toInt() == 1) {
        val count = data.getShort(4).toInt() and 65535
        if (count !in 1..256 || 6 + count * 16 > bytes.size) return null
        val entries = (0 until count).sortedByDescending { index -> bytes[6 + index * 16].toInt() and 255 }
        for (index in entries) {
            val length = data.getInt(6 + index * 16 + 8); val start = data.getInt(6 + index * 16 + 12)
            if (start < 6 + count * 16 || length < 8 || start.toLong() + length > bytes.size) continue
            val payload = bytes.copyOfRange(start, start + length)
            decodeBitmap(payload)?.let { return it }
            decodeDib(payload)?.let { return it }
        }
        return null
    }
    return decodeBitmap(bytes)
}
private fun decodeBitmap(bytes: ByteArray): Bitmap? {
    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
    if (bounds.outWidth !in 1..1024 || bounds.outHeight !in 1..1024) return null
    return BitmapFactory.decodeByteArray(bytes, 0, bytes.size,
        BitmapFactory.Options().apply { inSampleSize = (maxOf(bounds.outWidth, bounds.outHeight) / 128).coerceAtLeast(1) })
}
private fun decodeDib(bytes: ByteArray): Bitmap? {
    if (bytes.size < 40) return null
    val data = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
    val header = data.getInt(0); val width = data.getInt(4); val doubledHeight = data.getInt(8)
    val height = doubledHeight / 2; val bits = data.getShort(14).toInt()
    if (header < 40 || width !in 1..512 || height !in 1..512 || doubledHeight % 2 != 0 ||
        data.getShort(12).toInt() != 1 || bits !in listOf(24, 32) || data.getInt(16) != 0) return null
    val stride = ((width * bits + 31) / 32) * 4
    val maskOffset = header.toLong() + stride * height
    if (maskOffset > bytes.size) return null
    val maskStride = ((width + 31) / 32) * 4
    val hasMask = maskOffset + maskStride * height <= bytes.size
    val pixels = IntArray(width * height)
    var hasAlpha = false
    for (y in 0 until height) for (x in 0 until width) {
        val offset = header + (height - 1 - y) * stride + x * (bits / 8)
        val alpha = if (bits == 32) bytes[offset + 3].toInt() and 255 else 255
        hasAlpha = hasAlpha || alpha != 0
        pixels[y * width + x] = (alpha shl 24) or ((bytes[offset + 2].toInt() and 255) shl 16) or
            ((bytes[offset + 1].toInt() and 255) shl 8) or (bytes[offset].toInt() and 255)
    }
    for (y in 0 until height) for (x in 0 until width) {
        val index = y * width + x
        if (!hasAlpha) pixels[index] = pixels[index] or (255 shl 24)
        if (hasMask && (bytes[maskOffset.toInt() + (height - 1 - y) * maskStride + x / 8].toInt() and (128 shr (x % 8))) != 0)
            pixels[index] = pixels[index] and 0x00ffffff
    }
    return Bitmap.createBitmap(pixels, width, height, Bitmap.Config.ARGB_8888)
}
