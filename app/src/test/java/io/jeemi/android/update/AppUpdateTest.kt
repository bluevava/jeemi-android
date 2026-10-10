package io.jeemi.android.update

import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.File
import java.net.HttpURLConnection
import java.net.URI
import java.net.URL
import java.security.MessageDigest

class AppUpdateTest {
    @Test fun versionOrderAndArchitectureAreNumericAndDeviceBased() {
        assertTrue(ReleaseVersion.parse("0.1.10") > ReleaseVersion.parse("0.1.9"))
        assertTrue(ReleaseVersion.parse("10.0.0") > ReleaseVersion.parse("2.99.99"))
        for (bad in listOf("1.2", "01.2.3", "1.2.3-beta", "../1.2.3", "999999999999.0.0"))
            assertThrows(UpdateFailure::class.java) { ReleaseVersion.parse(bad) }
        assertEquals("arm64-v8a", updateAbi(listOf("arm64-v8a", "armeabi-v7a")))
        assertEquals("x86_64", updateAbi(listOf("x86_64", "x86", "arm64-v8a")))
        assertThrows(UpdateFailure::class.java) { updateAbi(listOf("armeabi-v7a", "x86")) }
    }

    private class Response(val body: ByteArray, val status: Int = 200, val location: String? = null,
        val advertised: Long = body.size.toLong()) : HttpURLConnection(URL(UPDATE_API)) {
        var closed = false
        override fun connect() {}
        override fun disconnect() { closed = true }
        override fun usingProxy() = false
        override fun getResponseCode() = status
        override fun getContentLengthLong() = advertised
        override fun getHeaderField(name: String?) = if (name == "Location") location else null
        override fun getInputStream() = ByteArrayInputStream(body)
    }
    private fun asset(body: ByteArray) = ReleaseAsset("test.apk", "$UPDATE_REPOSITORY/releases/download/v1.0.0/test.apk",
        body.size.toLong(), hex(MessageDigest.getInstance("SHA-256").digest(body)))

    @Test fun requestRejectsOtherHostsDowngradesAndOversizedBodies() {
        listOf("http://github.com/anything", "https://github.com.evil.invalid/x", "https://github.com:444/x",
            "https://user@github.com/x", "https://github.com/x#fragment").forEach {
            assertFalse(isUpdateUrl(URI(it)))
        }
        val response = Response(byteArrayOf(), 302, "http://github.com/file")
        var connections = 0
        assertThrows(UpdateFailure::class.java) { UpdateHttp { connections++; response }.metadata(UPDATE_API) }
        assertEquals(1, connections)
        assertTrue(response.closed)
        val huge = Response(ByteArray(MAX_UPDATE_METADATA.toInt() + 1), advertised = -1)
        assertThrows(UpdateFailure::class.java) { UpdateHttp { huge }.metadata(UPDATE_API) }
        assertTrue(huge.closed)
    }

    @Test fun downloadRequiresFullMatchingDigestAndLengthAndAlwaysClosesConnection() {
        val bytes = "synthetic update".toByteArray()
        val file = File.createTempFile("jeemi-update-", ".apk")
        try {
            val good = Response(bytes)
            val observed = mutableListOf<Long>()
            UpdateHttp { good }.download(asset(bytes), file) { observed += it }
            assertArrayEquals(bytes, file.readBytes())
            assertEquals(bytes.size.toLong(), observed.last())
            assertTrue(good.closed)
            val wrongHash = asset(bytes).copy(sha256 = "0".repeat(64))
            assertThrows(UpdateFailure::class.java) { UpdateHttp { Response(bytes) }.download(wrongHash, file) {} }
            assertThrows(UpdateFailure::class.java) { UpdateHttp { Response(bytes.copyOf(3), advertised = -1) }.download(asset(bytes), file) {} }
            assertThrows(UpdateFailure::class.java) { UpdateHttp { Response(bytes + 1, advertised = -1) }.download(asset(bytes), file) {} }
            assertThrows(Exception::class.java) { UpdateHttp { Response(byteArrayOf(0xc3.toByte(), 0x28)) }.metadata(UPDATE_API) }
        } finally { file.delete() }
    }

    @Test fun cancellationNoReleaseAndRedirectBudgetHaveFiniteOutcomes() {
        val cancelled = UpdateHttp { error("cancelled request must not connect") }
        cancelled.cancel()
        assertThrows(CancellationException::class.java) { cancelled.metadata(UPDATE_API) }
        assertEquals(UpdateProblem.NO_RELEASE,
            assertThrows(UpdateFailure::class.java) { UpdateHttp { Response(byteArrayOf(), 404) }.metadata(UPDATE_API) }.problem)
        var redirects = 0
        assertThrows(UpdateFailure::class.java) {
            UpdateHttp { redirects++; Response(byteArrayOf(), 302, UPDATE_API) }.metadata(UPDATE_API)
        }
        assertEquals(6, redirects)
    }

    @Test fun duplicateChecksCancellationAndRetryDoNotLeaveBusyState() = runBlocking {
        var requests = 0
        var cancelCount = 0
        var result = CompletableDeferred<UpdateResult>()
        val source = object : AppUpdateSource {
            override suspend fun prepare(progress: (UpdateProgress) -> Unit): UpdateResult { requests++; return result.await() }
            override suspend fun verify(update: DownloadedUpdate) {}
            override fun cancel() { cancelCount++ }
        }
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        try {
            val updater = AppUpdater(scope, source)
            updater.check(); updater.check()
            assertEquals(1, requests)
            updater.leaveForeground()
            assertEquals(AppUpdateState.Cancelled, updater.state.value)
            assertEquals(1, cancelCount)
            result = CompletableDeferred(UpdateResult.Current("1.2.3"))
            updater.check()
            assertEquals(AppUpdateState.Current("1.2.3"), updater.state.value)
            updater.check()
            assertEquals(3, requests) // Immediate completion must also release the operation slot.
        } finally { scope.cancel() }
    }

    @Test fun installerRequestsAreOneShotAndReturningDoesNotReopenInstallation() = runBlocking {
        val update = DownloadedUpdate(UpdateRelease(ReleaseVersion.parse("1.2.3"), 7, 26, "io.jeemi.android",
            "a".repeat(64), "arm64-v8a", asset(byteArrayOf(1))), File("unused.apk"))
        var verifies = 0
        val source = object : AppUpdateSource {
            override suspend fun prepare(progress: (UpdateProgress) -> Unit) = UpdateResult.Downloaded(update)
            override suspend fun verify(update: DownloadedUpdate) { verifies++ }
            override fun cancel() {}
        }
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        try {
            val updater = AppUpdater(scope, source)
            updater.check()
            assertEquals(update, updater.takeInstallRequest())
            assertNull(updater.takeInstallRequest())
            updater.installationProblem(UpdateProblem.PERMISSION)
            assertNull(updater.takeInstallRequest())
            updater.install()
            assertEquals(1, verifies)
            assertEquals(update, updater.takeInstallRequest())
            updater.check(); updater.leaveForeground()
            assertNull(updater.takeInstallRequest())
            assertTrue(updater.state.value is AppUpdateState.Ready)
        } finally { scope.cancel() }
    }
}
