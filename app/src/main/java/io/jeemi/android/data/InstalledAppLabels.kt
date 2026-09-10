package io.jeemi.android.data

import android.content.pm.PackageManager
import android.os.SystemClock
import android.util.LruCache
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

internal class InstalledAppLabels(private val readLabel: (String) -> CharSequence?) {
    constructor(packages: PackageManager) : this({ name ->
        @Suppress("DEPRECATION")
        val info = packages.getApplicationInfo(name, 0)
        if (info.packageName == name) info.loadLabel(packages) else null
    })
    private val cache = LruCache<String, String>(256)
    private val loading = Mutex()
    private var retryAfter = 0L

    suspend fun load(names: List<String>, locale: String): Map<String, String> = withContext(Dispatchers.IO) {
        loading.withLock {
            names.asSequence().filter(::isApplicationPackage).distinct().take(256).associateWith { name ->
                ensureActive()
                val key = locale + ":" + name
                cache.get(key) ?: if (SystemClock.elapsedRealtime() < retryAfter) name else {
                    val label = try { safeApplicationLabel(readLabel(name)).ifBlank { name }
                    } catch (cancelled: CancellationException) { throw cancelled
                    } catch (_: OutOfMemoryError) {
                        cache.evictAll(); retryAfter = SystemClock.elapsedRealtime() + 60_000; name
                    } catch (_: StackOverflowError) { name
                    } catch (_: Exception) { name }
                    ensureActive()
                    label.also { cache.put(key, it) }
                }
            }
        }
    }
}
