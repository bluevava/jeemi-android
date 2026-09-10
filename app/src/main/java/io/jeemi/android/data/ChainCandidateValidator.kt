package io.jeemi.android.data

import io.jeemi.android.JeemiApplication
import mobile.Mobile
import java.io.File
import java.util.UUID
import java.util.concurrent.TimeUnit

/** Validate affected chain candidates with the bundled core, without starting
 * a VPN, listeners or a controller. One private GEO snapshot per transaction. */
internal class ChainCandidateValidator(private val app: JeemiApplication) : AutoCloseable {
    private val directory = File(app.cacheDir, "chain-validation-${UUID.randomUUID()}")
    private var ready = false

    fun validate(candidate: String) {
        try {
            if (!ready) { app.geodata.materialize(directory); ready = true }
            // Missing GEO must fail locally instead of triggering a download.
            val input = Mobile.composeConfiguration(candidate,
                "geox-url: {mmdb: 'jeemi-resource://managed', geoip: 'jeemi-resource://managed', geosite: 'jeemi-resource://managed', asn: 'jeemi-resource://managed'}",
                "[{\"path\":\"/geox-url\",\"strategy\":\"replace\",\"conflictPolicy\":\"error\"}]")
            File(directory, "config.yaml").writeText(input)
            val process = ProcessBuilder(BundledCore(app).executable.absolutePath, "-t", "-d", directory.absolutePath)
                .redirectErrorStream(true).redirectOutput(File(directory, "validation.log")).start()
            try { check(process.waitFor(20, TimeUnit.SECONDS) && process.exitValue() == 0) }
            finally { process.destroyForcibly() }
        } catch (_: Exception) { throw IllegalArgumentException("chain_proxy:candidate_invalid") }
    }

    override fun close() {
        check(directory.canonicalFile.parentFile == app.cacheDir.canonicalFile)
        directory.deleteRecursively()
    }
}
