package io.jeemi.android

import androidx.test.core.app.ApplicationProvider
import org.json.JSONObject
import org.junit.rules.ExternalResource
import java.io.File
import java.net.InetAddress
import java.net.ServerSocket
import java.util.concurrent.atomic.AtomicInteger
import kotlin.concurrent.thread

class RuntimeResourceServer : ExternalResource() {
    private lateinit var socket: ServerSocket
    private lateinit var worker: Thread
    val url get() = "http://127.0.0.1:${socket.localPort}"
    val rules = AtomicInteger()
    val nodes = AtomicInteger()
    val scripts = AtomicInteger()
    @Volatile var online = true
    @Volatile var scriptDelayMillis = 0L
    @Volatile var script = "function main(config) { config['script-test'] = 'downloaded'; return config; }"
    override fun before() {
        socket = ServerSocket(0, 8, InetAddress.getByName("127.0.0.1"))
        worker = thread(isDaemon = true) {
            while (!socket.isClosed) {
                val client = try { socket.accept() } catch (_: Exception) { break }
                thread(isDaemon = true) {
                    runCatching { client.use {
                        it.soTimeout = 5000
                        val reader = it.getInputStream().bufferedReader()
                        val path = reader.readLine().orEmpty().split(' ').getOrNull(1)
                        while (!reader.readLine().isNullOrEmpty()) { }
                        val body = when (path) {
                            "/rules.yaml" -> { rules.incrementAndGet(); "payload:\n  - '+.example.invalid'\n" }
                            "/nodes.yaml" -> { nodes.incrementAndGet(); "proxies: [{name: Remote.Test, type: socks5, server: node.example.invalid, port: 1080}]" }
                            else -> { scripts.incrementAndGet(); Thread.sleep(scriptDelayMillis); script }
                        }.toByteArray()
                        val status = if (online) "200 OK" else "503 Unavailable"
                        it.getOutputStream().write(("HTTP/1.1 $status\r\nContent-Length: ${body.size}\r\nConnection: close\r\n\r\n").toByteArray() + body)
                    } }
                }
            }
        }
    }
    override fun after() { socket.close(); worker.join(1000) }
}

class InstalledDashboardFixture : ExternalResource() {
    val version = "v0.0." + System.currentTimeMillis()
    private lateinit var directory: File
    override fun before() {
        val app = ApplicationProvider.getApplicationContext<JeemiApplication>()
        directory = File(app.noBackupFilesDir, "runtime/mihomo/external-ui/zashboard/$version")
        check(!directory.exists())
        File(directory, "dist").mkdirs()
        val content = "<!doctype html><title>Jeemi dashboard fixture</title>JEEMI_DASHBOARD_OK"
        File(directory, "dist/index.html").writeText(content)
        File(directory, "metadata.json").writeText(JSONObject()
            .put("release", JSONObject().put("version", version))
            .put("files", JSONObject().put("index.html", content.toByteArray().size)).toString())
    }
    override fun after() {
        val app = ApplicationProvider.getApplicationContext<JeemiApplication>()
        check(directory.canonicalFile.parentFile == File(app.noBackupFilesDir, "runtime/mihomo/external-ui/zashboard").canonicalFile)
        directory.deleteRecursively()
    }
}
