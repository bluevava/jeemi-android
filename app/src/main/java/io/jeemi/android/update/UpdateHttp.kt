package io.jeemi.android.update

import java.io.ByteArrayOutputStream
import java.io.File
import java.io.OutputStream
import java.net.HttpURLConnection
import java.net.URI
import java.nio.ByteBuffer
import java.security.MessageDigest
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CancellationException

/** Bounded HTTPS requests to the release service only; no credentials or response bodies in logs. */
internal class UpdateHttp(private val connect: (URI) -> HttpURLConnection = { it.toURL().openConnection() as HttpURLConnection }) {
    @Volatile private var connection: HttpURLConnection? = null
    @Volatile private var cancelled = false
    fun cancel() { cancelled = true; connection?.disconnect() }

    fun metadata(url: String, asset: ReleaseAsset? = null): String {
        val output = ByteArrayOutputStream()
        transfer(url, output, minOf(asset?.bytes ?: MAX_UPDATE_METADATA, MAX_UPDATE_METADATA), asset, 30, {})
        return Charsets.UTF_8.newDecoder().decode(ByteBuffer.wrap(output.toByteArray())).toString()
    }
    fun download(asset: ReleaseAsset, file: File, progress: (Long) -> Unit) {
        file.outputStream().use { transfer(asset.url, it, MAX_UPDATE_APK, asset, 300, progress) }
    }
    private fun transfer(url: String, output: OutputStream, limit: Long, asset: ReleaseAsset?, seconds: Long, progress: (Long) -> Unit) {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(seconds)
        fun check() {
            if (cancelled) throw CancellationException()
            if (System.nanoTime() >= deadline) throw UpdateFailure(UpdateProblem.NETWORK)
        }
        var uri = URI(url)
        repeat(6) { redirect ->
            check()
            updateRequire(isUpdateUrl(uri))
            val request = connect(uri)
            connection = request
            try {
                check()
                request.instanceFollowRedirects = false
                request.useCaches = false
                request.connectTimeout = minOf(10_000, TimeUnit.NANOSECONDS.toMillis(deadline - System.nanoTime()).coerceAtLeast(1).toInt())
                request.readTimeout = minOf(15_000, TimeUnit.NANOSECONDS.toMillis(deadline - System.nanoTime()).coerceAtLeast(1).toInt())
                request.setRequestProperty("User-Agent", "Jeemi-Android updater")
                request.setRequestProperty("Accept", if (uri.host == "api.github.com") "application/vnd.github+json" else "application/octet-stream")
                request.setRequestProperty("Accept-Encoding", "identity")
                val status = request.responseCode
                check()
                if (status in listOf(301, 302, 303, 307, 308)) {
                    updateRequire(redirect < 5)
                    uri = uri.resolve(request.getHeaderField("Location") ?: throw UpdateFailure(UpdateProblem.RELEASE))
                } else {
                    if (status == 404 && url == UPDATE_API) throw UpdateFailure(UpdateProblem.NO_RELEASE)
                    updateRequire(status == 200, UpdateProblem.NETWORK)
                    updateRequire(request.contentEncoding.isNullOrBlank() || request.contentEncoding.equals("identity", true))
                    updateRequire(request.contentLengthLong <= limit)
                    if (asset != null && request.contentLengthLong >= 0) updateRequire(request.contentLengthLong == asset.bytes)
                    val hash = MessageDigest.getInstance("SHA-256")
                    var bytes = 0L
                    request.inputStream.use { input ->
                        val buffer = ByteArray(32 * 1024)
                        while (true) {
                            check()
                            request.readTimeout = minOf(15_000, TimeUnit.NANOSECONDS.toMillis(deadline - System.nanoTime()).coerceAtLeast(1).toInt())
                            val count = input.read(buffer)
                            if (count < 0) break
                            bytes += count
                            updateRequire(bytes <= limit && (asset == null || bytes <= asset.bytes))
                            output.write(buffer, 0, count)
                            hash.update(buffer, 0, count)
                            progress(bytes)
                        }
                    }
                    check()
                    updateRequire(bytes > 0)
                    if (asset != null) updateRequire(bytes == asset.bytes && hex(hash.digest()) == asset.sha256, UpdateProblem.PACKAGE)
                    return
                }
            } finally { connection = null; request.disconnect() }
        }
        throw UpdateFailure(UpdateProblem.RELEASE)
    }
}

internal fun hex(bytes: ByteArray): String = bytes.joinToString("") { "%02x".format(it) }
