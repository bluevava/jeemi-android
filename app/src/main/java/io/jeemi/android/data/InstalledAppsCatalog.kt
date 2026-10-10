package io.jeemi.android.data

import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.os.SystemClock
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.util.Locale

internal data class InstalledApp(val packageName: String, val name: String, val system: Boolean)

/** Explicit picker only. Connections still resolve known packages without enumerating apps. */
internal class InstalledAppsCatalog(private val packages: PackageManager) {
    private var retryAfter = 0L

    @Suppress("DEPRECATION")
    suspend fun load(): List<InstalledApp> = withContext(Dispatchers.IO) {
        check(SystemClock.elapsedRealtime() >= retryAfter)
        try {
            val applications = packages.getInstalledApplications(0)
            // Fail visibly instead of silently hiding apps on an exceptional device.
            check(applications.size <= 8192)
            val result = ArrayList<InstalledApp>(applications.size)
            for (info in applications) {
                ensureActive()
                val name = info.packageName ?: continue
                if (!isApplicationPackage(name)) continue
                val label = try { safeApplicationLabel(info.loadLabel(packages)).ifBlank { name } }
                catch (cancelled: CancellationException) { throw cancelled }
                catch (_: StackOverflowError) { name }
                catch (_: Exception) { name }
                result += InstalledApp(name, label, info.flags and (ApplicationInfo.FLAG_SYSTEM or ApplicationInfo.FLAG_UPDATED_SYSTEM_APP) != 0)
            }
            ensureActive()
            result.distinctBy { it.packageName }.sortedWith(compareBy<InstalledApp> { it.name.lowercase(Locale.ROOT) }.thenBy { it.packageName })
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (_: OutOfMemoryError) {
            retryAfter = SystemClock.elapsedRealtime() + 60_000
            throw IllegalStateException("app_catalog_unavailable")
        }
    }
}

internal fun filterInstalledApps(apps: List<InstalledApp>, query: String, showSystem: Boolean): List<InstalledApp> {
    val text = query.trim()
    return apps.filter { (showSystem || !it.system) && (it.name.contains(text, ignoreCase = true) || it.packageName.contains(text, ignoreCase = true)) }
}
