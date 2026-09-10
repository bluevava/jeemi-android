package io.jeemi.android.data

import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.drawable.Drawable
import android.os.SystemClock
import android.util.LruCache
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/** Load only icons requested by visible connection rows; never enumerate apps. */
internal class InstalledAppIcons(private val readDrawable: (String) -> Drawable?) {
    constructor(packages: PackageManager) : this({ installedAppDrawable(packages, it) })
    private data class Entry(val bitmap: Bitmap?)
    // Each thumbnail is 64 x 64 ARGB pixels: at most 2 MiB for 128 entries.
    // Cache missing packages too, so repeated connections do not retry each frame.
    private val cache = LruCache<String, Entry>(128)
    private val loading = Mutex()
    private var retryAfter = 0L

    suspend fun load(packageName: String): Bitmap? {
        if (!isApplicationPackage(packageName)) return null
        return withContext(Dispatchers.IO) {
            loading.withLock {
                ensureActive()
                cache.get(packageName)?.let {
                    if (it.bitmap?.isRecycled != true) return@withLock it.bitmap
                    cache.remove(packageName)
                }
                if (SystemClock.elapsedRealtime() < retryAfter) return@withLock null
                val bitmap = try {
                    readDrawable(packageName)?.let(::applicationIconThumbnail)
                } catch (cancelled: CancellationException) { throw cancelled
                } catch (_: OutOfMemoryError) {
                    // Release cache references, not pixels still used by visible
                    // rows. Avoid repeatedly retrying optional work under pressure.
                    cache.evictAll(); retryAfter = SystemClock.elapsedRealtime() + 60_000; null
                } catch (_: StackOverflowError) { null
                } catch (_: Exception) { null }
                try { ensureActive() } catch (cancelled: CancellationException) { bitmap?.recycle(); throw cancelled }
                cache.put(packageName, Entry(bitmap))
                bitmap
            }
        }
    }
}
