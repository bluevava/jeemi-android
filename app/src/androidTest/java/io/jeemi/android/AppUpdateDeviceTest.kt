package io.jeemi.android

import android.accessibilityservice.AccessibilityService
import android.content.ContextWrapper
import android.content.Intent
import android.graphics.Bitmap
import android.view.accessibility.AccessibilityNodeInfo
import androidx.activity.compose.setContent
import androidx.appcompat.app.AppCompatDelegate
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.unit.dp
import androidx.core.content.FileProvider
import androidx.core.os.LocaleListCompat
import androidx.lifecycle.ViewModelProvider
import androidx.test.platform.app.InstrumentationRegistry
import io.jeemi.android.ui.*
import io.jeemi.android.ui.theme.JeemiTheme
import io.jeemi.android.update.*
import kotlinx.coroutines.*
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.File
import java.io.FileInputStream
import java.net.HttpURLConnection
import java.net.URI
import java.net.URL
import java.security.MessageDigest

class AppUpdateDeviceTest {
    @get:Rule(order = 0) val library = TestLibrary()
    @get:Rule(order = 1) val compose = createAndroidComposeRule<MainActivity>()
    private val app get() = compose.activity.application as JeemiApplication
    private val model get() = ViewModelProvider(compose.activity)[JeemiViewModel::class.java]
    private val automation get() = InstrumentationRegistry.getInstrumentation().uiAutomation
    private val version = ReleaseVersion.parse("1.2.3")
    private val identity = UpdateIdentity("io.jeemi.android", "1.2.2", 41, listOf("x86_64", "x86"), 36, setOf("a".repeat(64)))
    private fun digest(bytes: ByteArray) = hex(MessageDigest.getInstance("SHA-256").digest(bytes))
    private fun asset(name: String, bytes: ByteArray) = ReleaseAsset(name, "$UPDATE_REPOSITORY/releases/download/v$version/$name", bytes.size.toLong(), digest(bytes))
    private fun manifest(bytes: ByteArray): JSONObject = JSONObject().put("schema", 1).put("version", version.toString())
        .put("tag", "v$version").put("versionCode", 42).put("minSdk", 26).put("applicationId", identity.packageName)
        .put("certificateSha256", identity.certificates.single()).put("artifacts", JSONArray().apply {
            listOf("arm64-v8a", "x86_64").forEach { abi -> put(JSONObject().put("architecture", updateArchitecture(abi)).put("abi", abi)
                .put("file", "Jeemi-Android-v$version-${updateArchitecture(abi)}.apk").put("bytes", bytes.size).put("sha256", digest(bytes))) }
        })
    private fun release(bytes: ByteArray, document: String = manifest(bytes).toString()): LatestRelease = LatestRelease(version,
        listOf(asset("release.json", document.toByteArray())) + listOf("arm64", "amd64").map { asset("Jeemi-Android-v$version-$it.apk", bytes) })
    private fun releaseJson(release: LatestRelease): String = JSONObject().put("tag_name", release.tag).put("draft", false).put("prerelease", false)
        .put("assets", JSONArray().apply { release.assets.forEach { put(JSONObject().put("name", it.name).put("browser_download_url", it.url)
            .put("digest", "sha256:${it.sha256}").put("size", it.bytes).put("state", "uploaded")) } }).toString()
    private fun eventually(condition: () -> Boolean) {
        val deadline = android.os.SystemClock.elapsedRealtime() + 15_000
        while (!condition()) {
            check(android.os.SystemClock.elapsedRealtime() < deadline) { "update_ui_timeout: ${automation.rootInActiveWindow?.packageName}" }
            compose.mainClock.advanceTimeByFrame()
            Thread.sleep(100)
        }
    }
    private fun shell(command: String) = automation.executeShellCommand(command).use {
        FileInputStream(it.fileDescriptor).use { input -> input.readBytes().toString(Charsets.UTF_8) }
    }

    @Test fun releaseMetadataSelectsDeviceAbiAndRejectsInconsistentOrUntrustedPackages() {
        val bytes = "synthetic APK".toByteArray()
        val release = release(bytes)
        assertEquals(release, parseLatestRelease(releaseJson(release)))
        assertEquals("x86_64", parseUpdateManifest(manifest(bytes).toString(), release, identity).abi)
        assertEquals("arm64-v8a", parseUpdateManifest(manifest(bytes).toString(), release, identity.copy(abis = listOf("arm64-v8a"))).abi)
        listOf<Pair<String, Any>>("draft" to true, "prerelease" to true, "tag_name" to "v1.2.3-beta").forEach { (key, value) ->
            assertThrows(Exception::class.java) { parseLatestRelease(JSONObject(releaseJson(release)).put(key, value).toString()) }
        }
        for (change in listOf<(JSONObject) -> Unit>(
            { it.put("applicationId", "another.app") }, { it.put("versionCode", 41) }, { it.put("minSdk", 37) },
            { it.put("certificateSha256", "b".repeat(64)) }, { it.put("version", "1.2.4") },
            { it.getJSONArray("artifacts").getJSONObject(1).put("bytes", bytes.size + 1) },
            { it.getJSONArray("artifacts").getJSONObject(1).put("sha256", "0".repeat(64)) })) {
            val changed = manifest(bytes).also(change)
            assertThrows(UpdateFailure::class.java) { parseUpdateManifest(changed.toString(), release, identity) }
        }
        val wrongUrl = JSONObject(releaseJson(release)).apply {
            getJSONArray("assets").getJSONObject(0).put("browser_download_url", "https://github.com/another/repo/release.json")
        }
        assertThrows(UpdateFailure::class.java) { parseLatestRelease(wrongUrl.toString()) }
        assertThrows(UpdateFailure::class.java) { parseUpdateManifest(manifest(bytes).toString(), release, identity.copy(packageName = "io.jeemi.android.debug")) }
    }

    @Test fun repositoryStreamsVerifiesAndCleansRejectedDownloadsWithoutTouchingLibrary() = runBlocking {
        val root = File(app.cacheDir, "app-update-repository-test").apply { mkdirs() }
        val context = object : ContextWrapper(app) { override fun getCacheDir() = root }
        val bytes = ByteArray(90_000) { (it % 251).toByte() }
        val document = manifest(bytes).toString()
        val latest = release(bytes, document)
        val requests = mutableListOf<String>()
        var corrupt = false
        val http = { UpdateHttp { uri ->
            requests += uri.toString()
            val body = when (uri.toString()) {
                UPDATE_API -> releaseJson(latest).toByteArray()
                latest.asset("release.json").url -> document.toByteArray()
                else -> if (corrupt) bytes.copyOf(bytes.size - 1) else bytes
            }
            object : HttpURLConnection(URL(UPDATE_API)) {
                override fun connect() {}
                override fun disconnect() {}
                override fun usingProxy() = false
                override fun getResponseCode() = 200
                override fun getContentLengthLong() = -1L
                override fun getInputStream() = ByteArrayInputStream(body)
            }
        } }
        var checks = 0
        val repository = AppUpdateRepository(context, http, { identity }) { file, selected, installed ->
            checks++
            assertArrayEquals(bytes, file.readBytes())
            assertEquals("x86_64", selected.abi)
            assertEquals(identity, installed)
        }
        val before = app.repository.load()
        try {
            val progress = mutableListOf<UpdateProgress>()
            val result = repository.prepare { progress += it } as UpdateResult.Downloaded
            assertEquals(1, checks)
            assertEquals(3, requests.size)
            assertTrue(requests.last().endsWith("-amd64.apk"))
            assertEquals(1, File(root, "app-updates").listFiles()!!.size)
            assertTrue(progress.any { it.phase == UpdatePhase.DOWNLOADING && it.received == bytes.size.toLong() })
            repository.verify(result.update)
            assertEquals(2, checks)
            corrupt = true
            try { repository.prepare {}; fail("Truncated APK accepted") } catch (_: UpdateFailure) { }
            assertTrue(File(root, "app-updates").listFiles()!!.isEmpty())
            assertEquals(2, checks)
            requests.clear()
            assertEquals(UpdateResult.Current("1.2.3"), AppUpdateRepository(context, http, { identity.copy(versionName = "1.2.4-dev") }).prepare {})
            assertEquals(listOf(UPDATE_API), requests)
            assertEquals(before, app.repository.load())
        } finally {
            check(root.canonicalFile.parentFile == app.cacheDir.canonicalFile)
            root.deleteRecursively()
        }
    }

    @Test fun archiveIdentityAndPrivateFileSharingRejectUnrelatedPathsAndCorruption() = runBlocking {
        val installed = installedUpdateIdentity(app)
        assertEquals(app.packageName, installed.packageName)
        assertEquals(1, installed.certificates.size)
        assertEquals(64, installed.certificates.single().length)
        val ownApk = File(app.applicationInfo.sourceDir)
        val archive = app.packageManager.getPackageArchiveInfo(ownApk.absolutePath, updatePackageFlags())!!
        assertEquals(installed.certificates, updateCertificates(archive))
        val folder = File(app.cacheDir, "app-updates").apply { mkdirs() }
        val partial = File(folder, "update.part")
        try {
            ownApk.copyTo(partial, overwrite = true)
            val stagedArchive = app.packageManager.getPackageArchiveInfo(partial.absolutePath, updatePackageFlags())!!
            assertEquals(installed.certificates, updateCertificates(stagedArchive))
        } finally { partial.delete() }
        val file = File(folder, "fixture.apk").apply { writeText("invalid APK") }
        try {
            val uri = FileProvider.getUriForFile(app, "${app.packageName}.updates", file)
            assertEquals("content", uri.scheme)
            assertEquals("invalid APK", app.contentResolver.openInputStream(uri)!!.bufferedReader().use { it.readText() })
            assertThrows(IllegalArgumentException::class.java) {
                FileProvider.getUriForFile(app, "${app.packageName}.updates", File(app.noBackupFilesDir, "library-v1.json"))
            }
            val selected = parseUpdateManifest(manifest(file.readBytes()).toString(), release(file.readBytes()), identity)
            try { verifyUpdatePackage(app, file, selected, identity); fail("Invalid APK accepted") } catch (_: UpdateFailure) { }
            file.appendText("corruption")
            try { verifyUpdatePackage(app, file, selected, identity); fail("Changed APK accepted") } catch (_: UpdateFailure) { }
        } finally { file.delete() }
    }

    @Test fun aboutUpdateButtonAndHelpRemainBilingual() {
        compose.waitUntil(20_000) { model.state.value.loaded }
        val previous = AppCompatDelegate.getApplicationLocales()
        try {
            for (tag in listOf("zh-CN", "en")) {
                compose.runOnUiThread { AppCompatDelegate.setApplicationLocales(LocaleListCompat.forLanguageTags(tag)) }
                compose.onNodeWithTag("app-update-check").performScrollTo().assertIsDisplayed().assertIsEnabled()
                capture("about-$tag")
                compose.onNodeWithContentDescription(compose.activity.getString(R.string.help_for, compose.activity.getString(R.string.app_update_check)))
                    .performScrollTo().performClick()
                compose.onNodeWithText(compose.activity.getString(R.string.help_app_update_scenarios)).assertExists()
                compose.onNodeWithText(compose.activity.getString(R.string.close)).performClick()
                assertEquals(AppUpdateState.Idle, model.appUpdater.state.value)
            }
        } finally { compose.runOnUiThread { AppCompatDelegate.setApplicationLocales(previous) } }
    }

    private fun show(updater: AppUpdater) {
        compose.runOnUiThread { compose.activity.setContent {
            JeemiTheme(io.jeemi.android.domain.AppTheme.SYSTEM) { Column(Modifier.fillMaxSize().padding(24.dp)) { AppUpdateControl(rememberAppUpdate(updater), updater) } }
        } }
    }

    @Test fun authorizationAndInstallationCanBeCancelledThenRetriedWithoutReopening() {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
        val file = File(app.cacheDir, "app-updates/fixture.apk").apply { parentFile!!.mkdirs(); File(app.applicationInfo.sourceDir).copyTo(this, overwrite = true) }
        val update = DownloadedUpdate(UpdateRelease(version, 42, 26, app.packageName, "a".repeat(64), "x86_64", asset(file.name, byteArrayOf(1))), file)
        var verifies = 0
        val source = object : AppUpdateSource {
            override suspend fun prepare(progress: (UpdateProgress) -> Unit) = UpdateResult.Downloaded(update)
            override suspend fun verify(update: DownloadedUpdate) { verifies++ }
            override fun cancel() {}
        }
        val updater = AppUpdater(scope, source)
        try {
            assertFalse("Run this test on the fresh debug install provided by connectedDebugAndroidTest", app.packageManager.canRequestPackageInstalls())
            show(updater)
            compose.onNodeWithTag("app-update-check").performClick()
            eventually { automation.rootInActiveWindow?.packageName == "com.android.settings" }
            assertTrue(automation.performGlobalAction(AccessibilityService.GLOBAL_ACTION_BACK))
            eventually { (updater.state.value as? AppUpdateState.Ready)?.problem == UpdateProblem.PERMISSION }
            compose.onNodeWithTag("app-update-install").assertIsEnabled().performClick()
            eventually { automation.rootInActiveWindow?.packageName == "com.android.settings" }
            fun checkable(node: AccessibilityNodeInfo?): AccessibilityNodeInfo? {
                if (node == null) return null
                if (node.isCheckable && node.isEnabled) return node
                repeat(node.childCount) { checkable(node.getChild(it))?.let { found -> return found } }
                return null
            }
            eventually { checkable(automation.rootInActiveWindow) != null }
            val toggle = checkable(automation.rootInActiveWindow) ?: error("install_permission_toggle_missing")
            assertFalse(toggle.isChecked)
            assertTrue(toggle.performAction(AccessibilityNodeInfo.ACTION_CLICK))
            eventually { app.packageManager.canRequestPackageInstalls() }
            assertTrue(automation.performGlobalAction(AccessibilityService.GLOBAL_ACTION_BACK))
            eventually { automation.rootInActiveWindow?.packageName?.toString()?.contains("packageinstaller") == true }
            // Only open and cancel the system prompt; never replace the app running the test.
            val cancelLabel = android.content.res.Resources.getSystem().getString(android.R.string.cancel)
            eventually { automation.rootInActiveWindow?.findAccessibilityNodeInfosByText(cancelLabel)?.any { it.isEnabled } == true }
            var cancel = automation.rootInActiveWindow!!.findAccessibilityNodeInfosByText(cancelLabel).first { it.isEnabled }
            while (!cancel.isClickable && cancel.parent != null) cancel = cancel.parent
            assertTrue(cancel.performAction(AccessibilityNodeInfo.ACTION_CLICK))
            eventually { automation.rootInActiveWindow?.packageName == app.packageName }
            compose.onNodeWithTag("app-update-install").assertIsDisplayed().assertIsEnabled()
            assertEquals(2, verifies)
            assertNull(updater.takeInstallRequest())
            assertTrue(file.isFile)
        } finally {
            compose.runOnUiThread { updater.leaveForeground(); scope.cancel() }
            // Revoking this app-op kills the caller on API 36. Gradle uninstalls this isolated debug app after the run.
            // For manual instrumentation, restore the permission from adb after the runner has finished.
            file.delete()
        }
    }

    @Test fun puttingAppInBackgroundCancelsAnUnfinishedCheck() {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
        val updater = AppUpdater(scope, object : AppUpdateSource {
            override suspend fun prepare(progress: (UpdateProgress) -> Unit): UpdateResult { awaitCancellation() }
            override suspend fun verify(update: DownloadedUpdate) {}
            override fun cancel() {}
        })
        try {
            show(updater)
            compose.onNodeWithTag("app-update-check").performClick()
            assertTrue(updater.state.value is AppUpdateState.Working)
            assertTrue(automation.performGlobalAction(AccessibilityService.GLOBAL_ACTION_HOME))
            eventually { updater.state.value == AppUpdateState.Cancelled }
            compose.activityRule.scenario.onActivity { it.startActivity(Intent(it, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_REORDER_TO_FRONT)) }
            eventually { compose.activityRule.scenario.state == androidx.lifecycle.Lifecycle.State.RESUMED }
            compose.onNodeWithTag("app-update-check").assertIsEnabled()
        } finally { compose.runOnUiThread { scope.cancel() } }
    }

    private fun capture(name: String) {
        compose.waitForIdle()
        InstrumentationRegistry.getInstrumentation().waitForIdleSync()
        val prefix = InstrumentationRegistry.getArguments().getString("visualPrefix", "phone")
        check(prefix.matches(Regex("[A-Za-z0-9_-]+")))
        val image = automation.takeScreenshot()
        val file = File(app.getExternalFilesDir(null), "update-$prefix-$name.png")
        file.outputStream().use { image.compress(Bitmap.CompressFormat.PNG, 100, it) }; image.recycle()
        shell("cp ${file.absolutePath} /data/local/tmp/jeemi-${file.name}")
    }
}
