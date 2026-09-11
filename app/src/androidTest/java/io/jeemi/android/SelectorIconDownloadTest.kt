package io.jeemi.android

import android.graphics.Bitmap
import io.jeemi.android.data.SelectorIcons
import io.jeemi.android.data.fetchSelectorIcon
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.net.InetAddress
import java.net.ServerSocket
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

class SelectorIconDownloadTest {
    private data class Response(val body: ByteArray = byteArrayOf(), val status: Int = 200,
        val headers: String = "", val length: Int = body.size)

    private fun server(responses: List<Response>, run: (String) -> Unit) {
        val socket = ServerSocket(0, 4, InetAddress.getByName("127.0.0.1")).apply { soTimeout = 5000 }
        val requests = CopyOnWriteArrayList<String>()
        val worker = Thread {
            try {
                for (response in responses) socket.accept().use { client ->
                    client.soTimeout = 5000
                    val reader = client.getInputStream().bufferedReader(Charsets.US_ASCII)
                    val headers = buildString {
                        while (true) { val line = reader.readLine(); if (line.isNullOrEmpty()) break; appendLine(line) }
                    }
                    requests.add(headers)
                    val header = "HTTP/1.1 ${response.status} Result\r\nContent-Length: ${response.length}\r\nConnection: close\r\n${response.headers}\r\n"
                    client.getOutputStream().apply { write(header.toByteArray(Charsets.US_ASCII)); write(response.body); flush() }
                }
            } catch (_: java.io.IOException) { /* the caller also closes this local fixture on failure */ }
        }.apply { isDaemon = true; start() }
        try {
            run("http://127.0.0.1:${socket.localPort}")
            assertEquals(responses.size, requests.size)
            for (request in requests) for (header in listOf("Authorization:", "Cookie:", "Referer:")) {
                assertFalse("Optional image requests must not inherit credentials", request.contains(header, ignoreCase = true))
            }
        } finally { socket.close(); worker.join(1000) }
    }

    private fun png(width: Int = 16, height: Int = 16): ByteArray = ByteArrayOutputStream().also { output ->
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        try { bitmap.eraseColor(0xff7733aa.toInt()); bitmap.compress(Bitmap.CompressFormat.PNG, 100, output) }
        finally { bitmap.recycle() }
    }.toByteArray()

    @Test fun downloadsDecodesAndCachesAnImageAfterASameOriginRedirect() {
        server(listOf(Response(status = 302, headers = "Location: /icon.png\r\n"), Response(png()))) { base ->
            runBlocking {
                val icons = SelectorIcons()
                val bitmap = requireNotNull(icons.load("$base/start"))
                assertEquals(64, bitmap.width); assertEquals(64, bitmap.height)
                assertEquals(Bitmap.Config.ARGB_8888, bitmap.config)
                assertEquals(0xff7733aa.toInt(), bitmap.getPixel(32, 32))
                assertSame(bitmap, icons.load("$base/start"))
            }
        }
    }

    @Test fun unavailableInvalidOversizedAndCrossOriginImagesAreOptionalMisses() {
        server(listOf(Response(status = 404), Response("<html>not an image</html>".toByteArray()),
            Response(length = 512 * 1024 + 1), Response(png(2048, 1)),
            Response(status = 302, headers = "Location: http://localhost:1/icon.png\r\n"))) { base ->
            runBlocking {
                for (path in listOf("missing", "html", "bytes", "pixels", "redirect")) {
                    assertNull(fetchSelectorIcon("$base/$path"))
                }
            }
        }
    }

    @Test fun cancellingADownloadClosesTheConnectionAndReleasesTheLoader() {
        val socket = ServerSocket(0, 1, InetAddress.getByName("127.0.0.1")).apply { soTimeout = 5000 }
        val started = CountDownLatch(1)
        val disconnected = CountDownLatch(1)
        val worker = Thread {
            try { socket.accept().use { client ->
                client.soTimeout = 5000
                val reader = client.getInputStream().bufferedReader(Charsets.US_ASCII)
                while (!reader.readLine().isNullOrEmpty()) { }
                client.getOutputStream().apply {
                    write("HTTP/1.1 200 OK\r\nContent-Length: 100\r\nConnection: close\r\n\r\n".toByteArray(Charsets.US_ASCII)); flush()
                }
                started.countDown()
                try { if (client.getInputStream().read() < 0) disconnected.countDown() }
                catch (_: java.io.IOException) { disconnected.countDown() }
            } } catch (_: java.io.IOException) { }
        }.apply { isDaemon = true; start() }
        try { runBlocking {
            val icons = SelectorIcons()
            val job = launch(Dispatchers.Default) { icons.load("http://127.0.0.1:${socket.localPort}/slow") }
            assertTrue(started.await(5, TimeUnit.SECONDS))
            withTimeout(3000) { job.cancelAndJoin() }
            assertTrue(disconnected.await(3, TimeUnit.SECONDS))
            assertNull(icons.load("invalid"))
        } } finally { socket.close(); worker.join(1000) }
    }
}
