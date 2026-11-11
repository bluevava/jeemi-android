package io.jeemi.android

import android.content.ContextWrapper
import androidx.test.core.app.ApplicationProvider
import io.jeemi.android.data.GeoRepository
import org.junit.Assert.*
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.File
import java.security.MessageDigest
import java.util.UUID
import org.json.JSONArray

class GeoRepositoryTest {
    @Test fun bundledSelectionsFollowTheApkSnapshotAndUserAssetsStayPinned() {
        val app = ApplicationProvider.getApplicationContext<JeemiApplication>()
        val directory = File(app.cacheDir, "geo-upgrade-${UUID.randomUUID()}").apply { mkdirs() }
        val isolated = object : ContextWrapper(app) { override fun getNoBackupFilesDir() = directory }
        val repository = GeoRepository(isolated)
        try {
            val bundled = repository.assets()
            repository.restore()
            val site = bundled.single { it.kind == "geosite" }
            app.assets.open("geodata/${site.file}").use { repository.install(site.kind, it, "import", site.sha256, site.bytes) }
            val custom = repository.assets().single { it.kind == site.kind }
            val file = File(directory, "geodata/selection.json")
            val old = JSONArray(file.readText())
            repeat(old.length()) { index ->
                old.getJSONObject(index).takeIf { it.getString("source") == "bundled" }?.apply {
                    put("sha256", "0".repeat(64)); put("bytes", 1); put("date", "old-apk-snapshot")
                }
            }
            file.writeText(old.toString())
            val stored = file.readText()
            val expected = bundled.map { if (it.kind == site.kind) custom else it }
            assertEquals(expected, repository.assets())
            assertEquals(stored, file.readText()) // Reading never replaces the custom selection file.
            val session = File(directory, "session")
            assertEquals(expected.joinToString(":") { it.sha256 }, repository.materialize(session))
            expected.forEach { assertEquals(it.sha256, sha(File(session, it.file))) }
        } finally {
            check(directory.canonicalFile.parentFile == app.cacheDir.canonicalFile)
            directory.deleteRecursively()
        }
    }
    @Test fun coreValidatesBundledFormatsAndInvalidReplacementPreservesSelectionAndActiveFiles() {
        val app = ApplicationProvider.getApplicationContext<JeemiApplication>()
        val directory = File(app.cacheDir, "geo-test-" + UUID.randomUUID()).apply { mkdirs() }
        val isolated = object : ContextWrapper(app) {
            override fun getNoBackupFilesDir(): File = directory
        }
        val repository = GeoRepository(isolated)
        try {
            val bundled = repository.assets()
            assertEquals(setOf("geoip-mmdb", "geosite", "asn"), bundled.map { it.kind }.toSet())
            val session = File(directory, "active")
            val revision = repository.materialize(session)
            val hashes = bundled.associate { it.file to sha(File(session, it.file)) }
            bundled.forEach { asset ->
                app.assets.open("geodata/" + asset.file).use {
                    repository.install(asset.kind, it, "import", asset.sha256, asset.bytes)
                }
                val before = repository.assets()
                try {
                    repository.install(asset.kind, ByteArrayInputStream("invalid geo input".toByteArray()), "import")
                    fail("Invalid GEO was accepted: " + asset.kind)
                } catch (_: IllegalArgumentException) { }
                assertEquals(before, repository.assets())
                assertEquals(asset.sha256, sha(File(session, asset.file)))
            }
            assertTrue(repository.assets().all { it.source == "import" })
            repository.restore()
            assertEquals(bundled, repository.assets())
            assertEquals(revision, repository.materialize(File(directory, "next")))
            hashes.forEach { (name, hash) -> assertEquals(hash, sha(File(session, name))) }
        } finally {
            check(directory.canonicalFile.parentFile == app.cacheDir.canonicalFile)
            directory.deleteRecursively()
        }
    }
    private fun sha(file: File) = MessageDigest.getInstance("SHA-256").digest(file.readBytes()).joinToString("") { "%02x".format(it) }
}
