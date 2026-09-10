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

class GeoRepositoryTest {
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
