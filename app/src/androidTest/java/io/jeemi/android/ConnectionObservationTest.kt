package io.jeemi.android

import androidx.test.platform.app.InstrumentationRegistry
import io.jeemi.android.domain.RuntimeState
import io.jeemi.android.runtime.*
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test
import java.net.InetAddress
import java.net.ServerSocket
import java.util.concurrent.atomic.AtomicInteger
import kotlin.concurrent.thread

class ConnectionObservationTest {
    @Test fun failedSnapshotsPreserveStateBackOffAndRecoverWithoutFalseRates() = runBlocking {
        LocalConnections().use { server ->
            val context = InstrumentationRegistry.getInstrumentation().targetContext
            val runtime = VpnController(context).apply {
                api = CoreApi(server.port, "fixture-only")
                live.value = LiveSession("profile", "revision", "geo")
                state.value = RuntimeState.Running("revision")
            }
            val diagnostics = Diagnostics(runtime, this, context)
            val observer = launch { diagnostics.observeConnections() }
            suspend fun until(condition: () -> Boolean) = withTimeout(12000) { while (!condition()) delay(10) }
            try {
                until { diagnostics.connections.value.rows.firstOrNull()?.uploadRate != null }
                assertEquals(300, diagnostics.connections.value.activeCount)
                server.mode = "oversized"
                until { diagnostics.connections.value.limitExceeded }
                val failed = diagnostics.connections.value
                assertEquals("stale", failed.status)
                assertEquals(300, failed.rows.size)
                assertTrue(failed.rows.none { it.ended })
                assertTrue(failed.rows.all { it.uploadRate == null })
                val requests = server.requests.get()
                delay(1200); assertEquals(requests, server.requests.get())
                server.mode = "valid"
                until { diagnostics.connections.value.status == "live" }
                assertFalse(diagnostics.connections.value.error)
                assertTrue(diagnostics.connections.value.rows.all { it.uploadRate == null })
                until { diagnostics.connections.value.rows.first().uploadRate != null }
                observer.cancelAndJoin()
                val stopped = server.requests.get()
                val feed = diagnostics.connections.value
                diagnostics.closeConnections(feed.rows.map { it.id }, feed.sessionId)
                until { !diagnostics.connections.value.busy }
                assertEquals(100, diagnostics.connections.value.rows.size)
                assertTrue(diagnostics.connections.value.rows.all { it.ended })
                assertEquals(0, diagnostics.connections.value.activeCount)
                assertEquals(300, server.closed.get())
                delay(1200); assertEquals(stopped, server.requests.get())
                assertTrue(runtime.state.value is RuntimeState.Running)
                diagnostics.clearEndedConnections()
                assertTrue(diagnostics.connections.value.rows.isEmpty())
                // A new runtime session discards the old history and rate base.
                runtime.live.value = LiveSession("new-profile", "new-revision", "geo")
                val resumed = launch { diagnostics.observeConnections() }
                try {
                    until { diagnostics.connections.value.sessionId == runtime.live.value!!.sessionId && diagnostics.connections.value.status == "live" }
                    assertTrue(diagnostics.connections.value.rows.none { it.ended })
                    assertTrue(diagnostics.connections.value.rows.all { it.uploadRate == null })
                } finally { resumed.cancelAndJoin() }
            } finally { observer.cancelAndJoin(); runtime.clear(false) }
        }
    }
}

private class LocalConnections : AutoCloseable {
    private val listener = ServerSocket(0, 8, InetAddress.getByName("127.0.0.1"))
    val port get() = listener.localPort
    val requests = AtomicInteger()
    val closed = AtomicInteger()
    @Volatile var mode = "valid"
    private val worker = thread(isDaemon = true) {
        while (!listener.isClosed) {
            val client = runCatching { listener.accept() }.getOrNull() ?: break
            try { client.use {
                it.soTimeout = 2000
                val reader = it.getInputStream().bufferedReader()
                val request = reader.readLine()
                val headers = generateSequence { reader.readLine() }.takeWhile(String::isNotEmpty).toList()
                check(headers.any { value -> value.equals("Authorization: Bearer fixture-only", true) })
                val body = if (request.startsWith("DELETE ")) { closed.incrementAndGet(); "{}" } else {
                    val tick = requests.incrementAndGet()
                    if (mode == "oversized") """{"connections":[{"id":"1","metadata":{"host":"${"a".repeat(4097)}"}}]}"""
                    else (0 until 300).joinToString(",", "{\"connections\":[", "]}") { id ->
                        """{"id":"$id","metadata":{"host":"same.example.com","process":"com.android.settings"},"upload":${tick * 4096},"download":${tick * 8192},"start":"2026-09-10T00:00:00Z"}"""
                    }
                }
                val bytes = body.toByteArray()
                it.getOutputStream().write("HTTP/1.1 200 OK\r\nContent-Length: ${bytes.size}\r\nConnection: close\r\n\r\n".toByteArray())
                it.getOutputStream().write(bytes)
            } } catch (_: Exception) { }
        }
    }
    override fun close() { listener.close(); worker.join(2500) }
}
