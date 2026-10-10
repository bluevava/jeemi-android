package io.jeemi.android.update

import org.json.JSONObject
import java.io.File
import java.net.URI

internal const val UPDATE_REPOSITORY = "https://github.com/bluevava/jeemi-android"
internal const val UPDATE_API = "https://api.github.com/repos/bluevava/jeemi-android/releases/latest"
internal const val MAX_UPDATE_APK = 96L * 1024 * 1024
internal const val MAX_UPDATE_METADATA = 256L * 1024

internal enum class UpdateProblem { NETWORK, NO_RELEASE, RELEASE, INCOMPATIBLE, PACKAGE, STORAGE, PERMISSION, INSTALLER }
internal class UpdateFailure(val problem: UpdateProblem) : Exception(problem.name)
internal fun updateRequire(value: Boolean, problem: UpdateProblem = UpdateProblem.RELEASE) {
    if (!value) throw UpdateFailure(problem)
}

internal data class ReleaseVersion(val major: Int, val minor: Int, val patch: Int) : Comparable<ReleaseVersion> {
    override fun compareTo(other: ReleaseVersion): Int = compareValuesBy(this, other, { it.major }, { it.minor }, { it.patch })
    override fun toString() = "$major.$minor.$patch"
    companion object {
        fun parse(value: String): ReleaseVersion {
            updateRequire(value.matches(Regex("(0|[1-9][0-9]{0,8})\\.(0|[1-9][0-9]{0,8})\\.(0|[1-9][0-9]{0,8})")))
            val parts = value.split('.').map(String::toInt)
            return ReleaseVersion(parts[0], parts[1], parts[2])
        }
    }
}

internal data class UpdateIdentity(val packageName: String, val versionName: String, val versionCode: Long,
    val abis: List<String>, val sdk: Int, val certificates: Set<String>)
internal data class ReleaseAsset(val name: String, val url: String, val bytes: Long, val sha256: String)
internal data class LatestRelease(val version: ReleaseVersion, val assets: List<ReleaseAsset>) {
    val tag get() = "v$version"
    fun asset(name: String) = assets.singleOrNull { it.name == name } ?: throw UpdateFailure(UpdateProblem.RELEASE)
}
internal data class UpdateRelease(val version: ReleaseVersion, val versionCode: Long, val minSdk: Int,
    val packageName: String, val certificate: String, val abi: String, val asset: ReleaseAsset)
internal data class DownloadedUpdate(val release: UpdateRelease, val file: File)
internal sealed interface UpdateResult {
    data class Current(val latest: String) : UpdateResult
    data class Downloaded(val update: DownloadedUpdate) : UpdateResult
}
internal enum class UpdatePhase { CHECKING, DOWNLOADING, VERIFYING }
internal data class UpdateProgress(val phase: UpdatePhase, val version: String = "", val received: Long = 0, val total: Long = 0)

internal fun updateAbi(abis: List<String>): String = abis.firstOrNull { it == "arm64-v8a" || it == "x86_64" }
    ?: throw UpdateFailure(UpdateProblem.INCOMPATIBLE)
internal fun updateArchitecture(abi: String) = when (abi) {
    "arm64-v8a" -> "arm64"; "x86_64" -> "amd64"; else -> throw UpdateFailure(UpdateProblem.INCOMPATIBLE)
}
internal fun isUpdateUrl(uri: URI): Boolean = uri.scheme == "https" && uri.userInfo == null &&
    uri.port in listOf(-1, 443) && uri.fragment == null && uri.host in setOf(
        "api.github.com", "github.com", "release-assets.githubusercontent.com", "objects.githubusercontent.com")

internal fun parseLatestRelease(text: String): LatestRelease {
    val json = JSONObject(text)
    updateRequire(json.getBoolean("draft") == false && json.getBoolean("prerelease") == false)
    val tag = json.getString("tag_name")
    updateRequire(tag.startsWith('v'))
    val version = ReleaseVersion.parse(tag.substring(1))
    val entries = json.getJSONArray("assets")
    updateRequire(entries.length() in 1..32)
    val assets = List(entries.length()) { index ->
        val entry = entries.getJSONObject(index)
        val name = entry.getString("name")
        updateRequire(name.matches(Regex("[A-Za-z0-9._-]{1,128}")))
        val url = entry.getString("browser_download_url")
        updateRequire(url == "$UPDATE_REPOSITORY/releases/download/$tag/$name")
        val digest = entry.getString("digest")
        updateRequire(digest.matches(Regex("sha256:[0-9a-f]{64}")))
        val bytes = entry.getLong("size")
        updateRequire(entry.getString("state") == "uploaded" && bytes in 1..MAX_UPDATE_APK)
        ReleaseAsset(name, url, bytes, digest.substringAfter(':'))
    }
    updateRequire(assets.map { it.name }.distinct().size == assets.size)
    return LatestRelease(version, assets)
}

internal fun parseUpdateManifest(text: String, latest: LatestRelease, identity: UpdateIdentity): UpdateRelease {
    val json = JSONObject(text)
    updateRequire(json.getInt("schema") == 1 && json.getString("tag") == latest.tag &&
        json.getString("version") == latest.version.toString())
    val packageName = json.getString("applicationId")
    updateRequire(packageName == "io.jeemi.android" && packageName == identity.packageName, UpdateProblem.INCOMPATIBLE)
    val versionCode = json.getLong("versionCode")
    updateRequire(versionCode in 1..2_100_000_000L && versionCode > identity.versionCode)
    val minSdk = json.getInt("minSdk")
    updateRequire(minSdk in 26..identity.sdk, UpdateProblem.INCOMPATIBLE)
    val certificate = json.getString("certificateSha256")
    updateRequire(certificate.matches(Regex("[0-9a-f]{64}")) && identity.certificates == setOf(certificate), UpdateProblem.INCOMPATIBLE)
    val abi = updateAbi(identity.abis)
    val architecture = updateArchitecture(abi)
    val file = "Jeemi-Android-${latest.tag}-$architecture.apk"
    val asset = latest.asset(file)
    val artifacts = json.getJSONArray("artifacts")
    updateRequire(artifacts.length() == 2)
    val entries = List(artifacts.length()) { artifacts.getJSONObject(it) }
    updateRequire(entries.map { it.getString("abi") }.toSet() == setOf("arm64-v8a", "x86_64"))
    val entry = entries.single { it.getString("abi") == abi }
    updateRequire(entry.getString("architecture") == architecture && entry.getString("file") == file &&
        entry.getLong("bytes") == asset.bytes && entry.getString("sha256") == asset.sha256)
    return UpdateRelease(latest.version, versionCode, minSdk, packageName, certificate, abi, asset)
}
