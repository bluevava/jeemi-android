package io.jeemi.android.update

import android.content.Context
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.content.pm.PackageInfoCompat
import java.io.File
import java.security.MessageDigest
import java.util.zip.ZipFile
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext

internal interface AppUpdateSource {
    suspend fun prepare(progress: (UpdateProgress) -> Unit): UpdateResult
    suspend fun verify(update: DownloadedUpdate)
    fun cancel()
}

internal class AppUpdateRepository(private val context: Context,
    private val clientFactory: () -> UpdateHttp = { UpdateHttp() },
    private val identity: () -> UpdateIdentity = { installedUpdateIdentity(context) },
    private val inspect: suspend (File, UpdateRelease, UpdateIdentity) -> Unit = { file, release, installed ->
        verifyUpdatePackage(context, file, release, installed)
    }) : AppUpdateSource {
    @Volatile private var client: UpdateHttp? = null
    private val directory get() = File(context.cacheDir, "app-updates")
    override fun cancel() { client?.cancel() }

    override suspend fun prepare(progress: (UpdateProgress) -> Unit): UpdateResult = withContext(Dispatchers.IO) {
        val http = clientFactory()
        client = http
        var temporary: File? = null
        try {
            currentCoroutineContext().ensureActive()
            val identity = identity()
            progress(UpdateProgress(UpdatePhase.CHECKING))
            val latest = parseLatestRelease(http.metadata(UPDATE_API))
            val installed = ReleaseVersion.parse(identity.versionName.removeSuffix("-dev"))
            if (latest.version <= installed) return@withContext UpdateResult.Current(latest.version.toString())
            val manifest = latest.asset("release.json")
            updateRequire(manifest.bytes <= 64 * 1024)
            val release = parseUpdateManifest(http.metadata(manifest.url, manifest), latest, identity)
            currentCoroutineContext().ensureActive()
            updateRequire(directory.isDirectory || directory.mkdirs(), UpdateProblem.STORAGE)
            // At most one completed APK; leftovers from cancelled/process-killed downloads are removed on the next check.
            directory.listFiles()?.forEach { file -> updateRequire(file.isFile && file.delete(), UpdateProblem.STORAGE) }
            updateRequire(directory.usableSpace > release.asset.bytes + 16 * 1024 * 1024, UpdateProblem.STORAGE)
            val part = File(directory, "update.part")
            temporary = part
            var lastPercent = -1
            progress(UpdateProgress(UpdatePhase.DOWNLOADING, release.version.toString(), 0, release.asset.bytes))
            http.download(release.asset, part) { received ->
                val percent = (received * 100 / release.asset.bytes).toInt()
                if (percent != lastPercent) {
                    lastPercent = percent
                    progress(UpdateProgress(UpdatePhase.DOWNLOADING, release.version.toString(), received, release.asset.bytes))
                }
            }
            currentCoroutineContext().ensureActive()
            progress(UpdateProgress(UpdatePhase.VERIFYING, release.version.toString()))
            inspect(part, release, identity)
            currentCoroutineContext().ensureActive()
            val target = File(directory, release.asset.name)
            updateRequire(part.renameTo(target), UpdateProblem.STORAGE)
            temporary = null
            UpdateResult.Downloaded(DownloadedUpdate(release, target))
        } finally {
            temporary?.delete()
            http.cancel()
            client = null
        }
    }

    override suspend fun verify(update: DownloadedUpdate) = withContext(Dispatchers.IO) {
        updateRequire(update.file.parentFile?.canonicalFile == directory.canonicalFile && update.file.name == update.release.asset.name,
            UpdateProblem.PACKAGE)
        try {
            inspect(update.file, update.release, identity())
        } catch (failure: UpdateFailure) {
            update.file.delete()
            throw failure
        }
    }
}

@Suppress("DEPRECATION")
internal fun updatePackageFlags() = if (Build.VERSION.SDK_INT >= 28) PackageManager.GET_SIGNING_CERTIFICATES else PackageManager.GET_SIGNATURES
@Suppress("DEPRECATION")
internal fun updateCertificates(info: PackageInfo): Set<String> {
    val signatures = if (Build.VERSION.SDK_INT >= 28) info.signingInfo?.apkContentsSigners else info.signatures
    return signatures.orEmpty().map { hex(MessageDigest.getInstance("SHA-256").digest(it.toByteArray())) }.toSet()
}
@Suppress("DEPRECATION")
internal fun installedUpdateIdentity(context: Context): UpdateIdentity {
    val info = context.packageManager.getPackageInfo(context.packageName, updatePackageFlags())
    return UpdateIdentity(info.packageName, requireNotNull(info.versionName), PackageInfoCompat.getLongVersionCode(info),
        Build.SUPPORTED_ABIS.toList(), Build.VERSION.SDK_INT, updateCertificates(info))
}

@Suppress("DEPRECATION")
internal suspend fun verifyUpdatePackage(context: Context, file: File, release: UpdateRelease, installed: UpdateIdentity) {
    updateRequire(file.isFile && file.length() == release.asset.bytes, UpdateProblem.PACKAGE)
    val hash = MessageDigest.getInstance("SHA-256")
    file.inputStream().use { input ->
        val buffer = ByteArray(32 * 1024)
        while (true) {
            currentCoroutineContext().ensureActive()
            val count = input.read(buffer)
            if (count < 0) break
            hash.update(buffer, 0, count)
        }
    }
    updateRequire(hex(hash.digest()) == release.asset.sha256, UpdateProblem.PACKAGE)
    val archive = context.packageManager.getPackageArchiveInfo(file.absolutePath, updatePackageFlags())
        ?: throw UpdateFailure(UpdateProblem.PACKAGE)
    updateRequire(archive.packageName == installed.packageName && archive.packageName == release.packageName &&
        archive.versionName == release.version.toString() && PackageInfoCompat.getLongVersionCode(archive) == release.versionCode &&
        release.versionCode > installed.versionCode && archive.applicationInfo?.minSdkVersion == release.minSdk &&
        release.minSdk <= installed.sdk && updateCertificates(archive) == installed.certificates &&
        installed.certificates == setOf(release.certificate), UpdateProblem.PACKAGE)
    ZipFile(file).use { zip ->
        updateRequire(zip.getEntry("lib/${release.abi}/libmihomo_exec.so") != null, UpdateProblem.PACKAGE)
        val entries = zip.entries()
        var count = 0
        while (entries.hasMoreElements()) {
            currentCoroutineContext().ensureActive()
            updateRequire(++count <= 20_000, UpdateProblem.PACKAGE)
            val name = entries.nextElement().name
            if (name.startsWith("lib/") && name.endsWith(".so"))
                updateRequire(name.startsWith("lib/${release.abi}/"), UpdateProblem.PACKAGE)
        }
    }
}
