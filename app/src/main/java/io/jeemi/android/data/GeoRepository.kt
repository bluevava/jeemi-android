package io.jeemi.android.data

import android.content.Context
import android.util.AtomicFile
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest
import java.util.UUID
import java.util.concurrent.TimeUnit

data class GeoAsset(val kind: String, val file: String, val sha256: String, val bytes: Long, val source: String, val date: String)

/** Immutable blobs keep a running session independent from later GEO selections. */
class GeoRepository(private val context: Context) {
    private val root = File(context.noBackupFilesDir, "geodata").apply { mkdirs() }
    private val selection = AtomicFile(File(root, "selection.json"))
    private fun bundled(): List<GeoAsset> {
        val json = JSONObject(context.assets.open("geodata/manifest.json").bufferedReader().use { it.readText() })
        val files = json.getJSONArray("files")
        return List(files.length()) { files.getJSONObject(it).let { item ->
            GeoAsset(item.getString("kind"), item.getString("file"), item.getString("sha256"), item.getLong("bytes"), "bundled", json.getString("snapshot"))
        } }
    }
    fun assets(): List<GeoAsset> {
        if (!selection.baseFile.exists() && !File(selection.baseFile.path + ".bak").exists()) return bundled()
        val array = JSONArray(selection.openRead().use { it.readBounded(64 * 1024).toString(Charsets.UTF_8) })
        return List(array.length()) { array.getJSONObject(it).let { item ->
            GeoAsset(item.getString("kind"), canonical(item.getString("kind")), item.getString("sha256"),
                item.getLong("bytes"), item.getString("source"), item.getString("date"))
        } }
    }
    fun restore() = save(bundled())
    fun materialize(directory: File): String {
        directory.mkdirs()
        val selected = assets()
        selected.forEach { asset ->
            val input = if (asset.source == "bundled") context.assets.open("geodata/" + asset.file)
                else File(root, "blobs/" + asset.sha256 + "/" + asset.file).inputStream()
            input.use { source -> File(directory, asset.file).outputStream().use { source.copyTo(it) } }
        }
        return selected.joinToString(":") { it.sha256 }
    }
    fun install(kind: String, input: InputStream, source: String, expected: String? = null, expectedBytes: Long? = null) {
        val name = canonical(kind)
        val stage = File(root, "stage-" + UUID.randomUUID()).apply { mkdirs() }
        try {
            val candidate = File(stage, name)
            val digest = MessageDigest.getInstance("SHA-256")
            var bytes = 0L
            candidate.outputStream().use { output ->
                val buffer = ByteArray(64 * 1024)
                while (true) {
                    val count = input.read(buffer)
                    if (count < 0) break
                    bytes += count
                    require(bytes <= 128L * 1024 * 1024)
                    digest.update(buffer, 0, count); output.write(buffer, 0, count)
                }
            }
            require(bytes > 0 && (expectedBytes == null || bytes == expectedBytes))
            val hash = digest.digest().joinToString("") { "%02x".format(it) }
            require(expected == null || hash == expected)
            validate(kind, stage)
            require(candidate.isFile && fileHash(candidate) == hash)
            val blob = File(root, "blobs/" + hash).apply { mkdirs() }
            val stored = File(blob, name)
            if (!stored.exists()) require(candidate.renameTo(stored))
            val updated = GeoAsset(kind, name, hash, bytes, source, java.time.Instant.now().toString())
            val previous = assets()
            save(if (previous.any { it.kind == kind }) previous.map { if (it.kind == kind) updated else it } else previous + updated)
        } finally {
            // This directory was created above under our private GEO root.
            check(stage.canonicalFile.parentFile == root.canonicalFile)
            stage.deleteRecursively()
        }
    }
    fun update(kind: String) {
        val upstreamName = when (kind) {
            "geoip-mmdb" -> "geoip.metadb"; "geoip-dat" -> "geoip.dat"
            "geosite" -> "geosite.dat"; "asn" -> "GeoLite2-ASN.mmdb"; else -> error("invalid_geo_kind")
        }
        val connection = connect("https://api.github.com/repos/MetaCubeX/meta-rules-dat/releases/tags/latest")
        val release = try { connection.inputStream.use { JSONObject(it.readBounded(4 * 1024 * 1024).toString(Charsets.UTF_8)) } }
            finally { connection.disconnect() }
        val assets = release.getJSONArray("assets")
        val item = (0 until assets.length()).map { assets.getJSONObject(it) }.first { it.getString("name") == upstreamName }
        val digest = item.getString("digest").removePrefix("sha256:")
        require(digest.matches(Regex("[a-f0-9]{64}")))
        val download = connect(item.getString("browser_download_url"))
        try { download.inputStream.use { install(kind, it, "download", digest, item.getLong("size")) } }
        finally { download.disconnect() }
    }
    private fun connect(url: String): HttpURLConnection {
        require(url.startsWith("https://"))
        return (URL(url).openConnection() as HttpURLConnection).apply {
            connectTimeout = 15000; readTimeout = 30000
            setRequestProperty("User-Agent", "Jeemi-Android")
        }
    }
    private fun validate(kind: String, home: File) {
        val rule = when (kind) { "geosite" -> "GEOSITE,CN,DIRECT"; "asn" -> "IP-ASN,4134,DIRECT,no-resolve"; else -> "GEOIP,CN,DIRECT,no-resolve" }
        val mode = kind == "geosite" || kind == "geoip-dat"
        File(home, "config.yaml").writeText("mode: rule\nlog-level: silent\ngeodata-mode: " + mode +
            "\ngeodata-loader: memconservative\ngeo-auto-update: false\n" +
            // Invalid input must fail, never be silently replaced by an upstream download.
            "geox-url: {mmdb: 'jeemi-resource://managed', geoip: 'jeemi-resource://managed', " +
            "geosite: 'jeemi-resource://managed', asn: 'jeemi-resource://managed'}\nrules:\n  - " + rule + "\n  - MATCH,DIRECT\n")
        val process = ProcessBuilder(BundledCore(context).executable.absolutePath, "-t", "-d", home.absolutePath)
            .redirectOutput(File(home, "validation.log")).redirectErrorStream(true).start()
        try { require(process.waitFor(20, TimeUnit.SECONDS) && process.exitValue() == 0) }
        finally { process.destroyForcibly() }
    }
    private fun fileHash(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buffer = ByteArray(64 * 1024)
            while (true) { val count = input.read(buffer); if (count < 0) break; digest.update(buffer, 0, count) }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }
    private fun save(items: List<GeoAsset>) {
        val array = JSONArray().apply { items.forEach { item ->
            put(JSONObject().put("kind", item.kind).put("sha256", item.sha256).put("bytes", item.bytes)
                .put("source", item.source).put("date", item.date))
        } }
        val stream = selection.startWrite()
        try { stream.write(array.toString().toByteArray()); selection.finishWrite(stream) }
        catch (error: Exception) { selection.failWrite(stream); throw error }
    }
    private fun canonical(kind: String): String = when (kind) {
        "geoip-mmdb" -> "geoip.metadb"; "geoip-dat" -> "GeoIP.dat"
        "geosite" -> "GeoSite.dat"; "asn" -> "ASN.mmdb"; else -> error("invalid_geo_kind")
    }
}
