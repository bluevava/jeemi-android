package io.jeemi.android.data

import java.net.HttpURLConnection
import java.net.URI
import java.net.URL
import java.nio.ByteBuffer
import java.nio.charset.CharacterCodingException
import java.util.zip.GZIPInputStream
import java.io.ByteArrayOutputStream
import java.io.EOFException
import java.io.IOException
import java.io.InterruptedIOException
import java.util.concurrent.TimeUnit

internal typealias SubscriptionRoute = (URL) -> HttpURLConnection

/** Explicit user initiated requests only. No cookies, logs or background refresh. */
class SubscriptionFetcher internal constructor(
    private val openConnection: SubscriptionRoute = { it.openConnection() as HttpURLConnection },
    private val directFallback: () -> SubscriptionRoute? = { null }
) {
    var suggestedName: String = ""
        private set
    var usage: Map<String, Long> = emptyMap()
        private set
    @Volatile private var active: HttpURLConnection? = null
    @Volatile var isCancelled: Boolean = false
        private set
    fun cancel() { isCancelled = true; active?.disconnect() }

    fun fetch(address: String): String {
        usage = emptyMap(); suggestedName = ""
        val original = requireSubscriptionUrl(address)
        checkCancellation()
        try {
            return download(original, openConnection)
        } catch (_: IOException) {
            // Resolve a route only after acquisition fails. Never retry a
            // cancelled operation or a later normalization/validation failure.
            checkCancellation()
            val direct = directFallback() ?: throw IOException("subscription_download_failed")
            checkCancellation()
            try { return download(original, direct) }
            catch (_: IOException) {
                checkCancellation()
                // Network exceptions can contain signed subscription URLs.
                throw IOException("subscription_direct_retry_failed")
            }
        }
    }

    private fun checkCancellation() {
        if (isCancelled || Thread.currentThread().isInterrupted) throw InterruptedIOException("download_cancelled")
    }

    private fun download(original: URI, route: SubscriptionRoute): String {
        var uri = original
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(30)
        fun checkDeadline() {
            checkCancellation()
            if (System.nanoTime() > deadline) throw InterruptedIOException("download_timed_out")
        }
        repeat(6) { redirect ->
            checkDeadline()
            require(uri.scheme in listOf("https", "http") && !uri.host.isNullOrBlank() && uri.userInfo == null)
            val connection = route(uri.toURL())
            active = connection
            try {
                checkDeadline()
                val remaining = TimeUnit.NANOSECONDS.toMillis(deadline - System.nanoTime()).coerceIn(1, 30_000).toInt()
                connection.connectTimeout = minOf(10_000, remaining)
                connection.readTimeout = minOf(20_000, remaining)
                connection.instanceFollowRedirects = false
                connection.useCaches = false
                connection.setRequestProperty("User-Agent", "Jeemi subscription client")
                connection.setRequestProperty("Accept", "application/yaml, application/json, text/yaml, text/plain, */*")
                connection.setRequestProperty("Accept-Encoding", "gzip")
                val status = connection.responseCode
                checkDeadline()
                if (status in listOf(301, 302, 303, 307, 308)) {
                    require(redirect < 5)
                    val next = uri.resolve(connection.getHeaderField("Location") ?: error("redirect_missing"))
                    require(uri.scheme != "https" || next.scheme == "https")
                    uri = next
                } else {
                    if (status != 200) throw IOException("subscription_http_$status")
                    require(connection.contentLengthLong <= 4 * 1024 * 1024)
                    val compressed = connection.contentEncoding.equals("gzip", true)
                    val input = if (compressed) GZIPInputStream(connection.inputStream) else connection.inputStream
                    val bytes = input.use { stream ->
                        val output = ByteArrayOutputStream()
                        val buffer = ByteArray(8192)
                        while (true) {
                            checkDeadline()
                            val remaining = TimeUnit.NANOSECONDS.toMillis(deadline - System.nanoTime()).coerceAtLeast(1)
                            connection.readTimeout = minOf(20_000, remaining.toInt())
                            val count = stream.read(buffer)
                            if (count < 0) break
                            require(output.size() + count <= 4 * 1024 * 1024)
                            output.write(buffer, 0, count)
                        }
                        checkDeadline()
                        output.toByteArray()
                    }
                    if (!compressed && connection.contentLengthLong >= 0 && bytes.size.toLong() != connection.contentLengthLong)
                        throw EOFException("subscription_body_truncated")
                    val text = try { Charsets.UTF_8.newDecoder().decode(ByteBuffer.wrap(bytes)).toString() }
                    catch (_: CharacterCodingException) { throw IllegalArgumentException("invalid_subscription_utf8") }
                    checkDeadline()
                    usage = parseSubscriptionUsage(connection.getHeaderField("Subscription-Userinfo").orEmpty())
                    suggestedName = subscriptionHeaderName(connection.getHeaderField("Profile-Title").orEmpty(),
                        connection.getHeaderField("Subscription-Userinfo").orEmpty())
                    return text
                }
            } finally { connection.disconnect(); active = null }
        }
        error("redirect_limit")
    }
}

internal fun parseSubscriptionUsage(header: String): Map<String, Long> {
    if (header.length > 8192) return emptyMap()
    return header.split(';').mapNotNull { part ->
        val values = part.trim().split('=', limit = 2)
        if (values.size != 2 || values[0].lowercase() !in listOf("upload", "download", "total", "expire")) null
        else values[1].trim().toLongOrNull()?.takeIf { it in 0..9_007_199_254_740_991L }?.let { values[0].lowercase() to it }
    }.toMap()
}

internal fun requireSubscriptionUrl(value: String): URI {
    require(value.trim().length in 1..8192)
    val uri = URI(value.trim())
    require(uri.scheme?.lowercase() in listOf("https", "http") && !uri.host.isNullOrBlank() && uri.userInfo == null)
    // Preserve existing escaping and signed query parameters exactly.
    return URI(uri.scheme.lowercase() + ":" + value.trim().substringAfter(':').substringBefore('#'))
}
internal fun scannedSubscriptionUrl(value: String): String? = runCatching { requireSubscriptionUrl(value).toASCIIString() }.getOrNull()

internal fun subscriptionHeaderName(title: String, userinfo: String): String {
    val decodedTitle = decodeProfileTitle(title)
    if (decodedTitle.isNotEmpty()) return decodedTitle
    if (userinfo.length > 8192) return ""
    return userinfo.split(';').mapNotNull { part ->
        val pair = part.trim().split('=', limit = 2)
        if (pair.size == 2 && pair[0].equals("name", true)) decodeProfileTitle(pair[1]).takeIf { it.isNotEmpty() } else null
    }.lastOrNull().orEmpty()
}
private fun decodeProfileTitle(raw: String): String = runCatching {
    require(raw.length <= 8192)
    var value = raw.trim()
    if (value.startsWith("base64:", true)) value = Charsets.UTF_8.newDecoder().decode(ByteBuffer.wrap(
        java.util.Base64.getDecoder().decode(value.substring(7).trim()))).toString()
    require(value.none { it.isISOControl() })
    value.trim().replace(Regex("\\s+"), " ").let { text ->
        if (text.length <= 80) text else text.substring(0, if (text[79].isHighSurrogate()) 79 else 80)
    }
}.getOrDefault("")

internal fun importedSubscriptionName(explicit: String, suggested: String, existing: Set<String>, unnamed: (Int) -> String): String {
    explicit.trim().takeIf { it.isNotEmpty() }?.let { return it }
    suggested.takeIf { it.isNotEmpty() }?.let { return it }
    return generateSequence(1) { it + 1 }.map(unnamed).first { it !in existing }
}
