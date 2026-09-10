package io.jeemi.android.domain

import io.jeemi.android.data.SubscriptionFetcher
import org.junit.Assert.*
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.net.InetAddress
import java.net.ServerSocket
import java.util.zip.GZIPOutputStream

class SubscriptionFetcherTest {
    private data class Response(val body: ByteArray = byteArrayOf(), val status: Int = 200, val headers: String = "")
    private fun server(responses: List<Response>, run: (String) -> Unit) {
        val socket = ServerSocket(0, 4, InetAddress.getByName("127.0.0.1"))
        socket.soTimeout = 5000
        val worker = Thread {
            try {
                responses.forEach { response -> socket.accept().use { client ->
                    client.soTimeout = 5000
                    val reader = client.getInputStream().bufferedReader(Charsets.US_ASCII)
                    while (!reader.readLine().isNullOrEmpty()) { /* consume request headers */ }
                    val header = "HTTP/1.1 ${response.status} Result\r\nContent-Length: ${response.body.size}\r\nConnection: close\r\n${response.headers}\r\n"
                    client.getOutputStream().apply { write(header.toByteArray(Charsets.US_ASCII)); write(response.body); flush() }
                } }
            } catch (_: java.io.IOException) { /* socket is also closed by a failing assertion */ }
        }.apply { isDaemon = true; start() }
        try { run("http://127.0.0.1:${socket.localPort}") } finally { socket.close(); worker.join(1000) }
    }
    private fun gzip(bytes: ByteArray) = ByteArrayOutputStream().also { output -> GZIPOutputStream(output).use { it.write(bytes) } }.toByteArray()

    @Test fun followsRedirectAndDecodesBoundedGzip() {
        val expected = "mode: rule\n"
        server(listOf(Response(status = 302, headers = "Location: /body\r\n"), Response(gzip(expected.toByteArray()), headers = "Content-Encoding: gzip\r\n"))) { base ->
            assertEquals(expected, SubscriptionFetcher().fetch("$base/redirect"))
        }
    }

    @Test fun rejectsOversizedDecompressionAndInvalidUtf8() {
        val expanded = ByteArray(4 * 1024 * 1024 + 1) { 'a'.code.toByte() }
        server(listOf(Response(gzip(expanded), headers = "Content-Encoding: gzip\r\n"), Response(byteArrayOf(0xc3.toByte(), 0x28)))) { base ->
            assertThrows(Exception::class.java) { SubscriptionFetcher().fetch("$base/large") }
            assertThrows(Exception::class.java) { SubscriptionFetcher().fetch("$base/utf8") }
        }
    }

    @Test fun cancellationDoesNotStartARequest() {
        val fetcher = SubscriptionFetcher()
        fetcher.cancel()
        assertThrows(java.io.InterruptedIOException::class.java) { fetcher.fetch("http://127.0.0.1:1/") }
        assertTrue(fetcher.isCancelled)
    }
    @Test fun capturesResponseMetadataWithoutRewritingTheBody() {
        val source = "socks5://user:password@proxy.example.invalid:1080#Imported"
        server(listOf(Response(source.toByteArray(), headers = "Profile-Title: base64:SGVhZGVyIHRpdGxl\r\nSubscription-Userinfo: name=Other; upload=1; download=2; total=100; expire=2000000000\r\n"))) { base ->
            val fetcher = SubscriptionFetcher()
            assertEquals(source, fetcher.fetch(base))
            assertEquals("Header title", fetcher.suggestedName)
            assertEquals(mapOf("upload" to 1L, "download" to 2L, "total" to 100L, "expire" to 2000000000L), fetcher.usage)
        }
    }
}
