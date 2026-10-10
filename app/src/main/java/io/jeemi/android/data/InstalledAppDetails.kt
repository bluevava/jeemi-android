package io.jeemi.android.data

import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.os.SystemClock
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

internal enum class AppInstallation { INSTALLED, NOT_INSTALLED, UNKNOWN }
internal data class AppDetails(val label: String, val installation: AppInstallation)

/** Visible rule rows only. A missing icon or label cannot establish installation status. */
internal class InstalledAppDetails(
    private val findApplication: (String) -> ApplicationInfo,
    private val readLabel: (ApplicationInfo) -> CharSequence?,
) {
    constructor(packages: PackageManager) : this({ name ->
        @Suppress("DEPRECATION")
        packages.getApplicationInfo(name, 0)
    }, { it.loadLabel(packages) })

    private val loading = Mutex()
    private var retryAfter = 0L

    suspend fun load(name: String): AppDetails = withContext(Dispatchers.IO) {
        loading.withLock {
            ensureActive()
            if (!isApplicationPackage(name) || SystemClock.elapsedRealtime() < retryAfter) {
                return@withLock AppDetails(name, AppInstallation.UNKNOWN)
            }
            var installed = false
            fun fallback() = AppDetails(name, if (installed) AppInstallation.INSTALLED else AppInstallation.UNKNOWN)
            val details = try {
                val info = findApplication(name)
                if (info.packageName != name) fallback() else {
                    installed = true
                    AppDetails(safeApplicationLabel(readLabel(info)).ifBlank { name }, AppInstallation.INSTALLED)
                }
            } catch (cancelled: CancellationException) { throw cancelled
            } catch (_: PackageManager.NameNotFoundException) {
                if (installed) fallback() else AppDetails(name, AppInstallation.NOT_INSTALLED)
            } catch (_: OutOfMemoryError) {
                retryAfter = SystemClock.elapsedRealtime() + 60_000
                fallback()
            } catch (_: StackOverflowError) { fallback()
            } catch (_: Exception) { fallback() }
            ensureActive()
            details
        }
    }
}
