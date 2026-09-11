package io.jeemi.android.data

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.os.SystemClock
import android.util.LruCache
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.util.Base64

/** Optional thumbnails. Group names and candidate revisions never change. */
internal class SelectorIcons(
    private val fetch: suspend (String) -> Bitmap? = ::fetchSelectorIcon,
    private val clock: () -> Long = SystemClock::elapsedRealtime,
    private val store: SelectorIconStore? = null,
) {
    private data class Entry(val bitmap: Bitmap?, val retryAfter: Long)
    private val cache = LruCache<String, Entry>(64)
    // Serialize downloads and coalesce identical URLs without an unbounded job/key map.
    private val loading = Mutex()
    private var pausedUntil = 0L

    suspend fun load(address: String): Bitmap? = withContext(Dispatchers.IO) {
        val url = try { requireSubscriptionUrl(address).toASCIIString() }
            catch (_: Exception) { return@withContext null }
        loading.withLock {
            ensureActive()
            cache.get(url)?.let { entry ->
                if (entry.bitmap != null && !entry.bitmap.isRecycled) return@withLock entry.bitmap
                if (clock() < entry.retryAfter) return@withLock null
                cache.remove(url)
            }
            if (clock() < pausedUntil) return@withLock null
            try {
                store?.read(url)?.let { bitmap ->
                    cache.put(url, Entry(bitmap, 0))
                    return@withLock bitmap
                }
                val bitmap = fetch(url)
                ensureActive()
                bitmap?.let { store?.write(url, it) }
                cache.put(url, Entry(bitmap, clock() + 60_000))
                bitmap
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: OutOfMemoryError) {
                cache.evictAll()
                pausedUntil = clock() + 60_000
                null
            } catch (_: Exception) {
                cache.put(url, Entry(null, clock() + 60_000))
                null
            }
        }
    }
}

internal suspend fun fetchSelectorIcon(url: String): Bitmap? = coroutineScope {
    val request = SubscriptionIcons()
    val context = currentCoroutineContext()
    // Cancellation closes a blocked socket; the predicate also covers cancellation
    // immediately before the connection is assigned or a redirect is followed.
    val disconnect = launch(start = CoroutineStart.UNDISPATCHED) {
        try { awaitCancellation() } finally { request.cancel() }
    }
    try {
        withContext(Dispatchers.IO) {
            val encoded = request.load(url, cancelled = { !context.isActive })?.image
                ?: return@withContext null
            ensureActive()
            val bytes = Base64.getDecoder().decode(encoded)
            BitmapFactory.decodeByteArray(bytes, 0, bytes.size,
                BitmapFactory.Options().apply { inPreferredConfig = Bitmap.Config.ARGB_8888 })
        }
    } finally {
        disconnect.cancel()
    }
}
