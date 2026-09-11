package io.jeemi.android

import android.graphics.Bitmap
import androidx.test.core.app.ApplicationProvider
import io.jeemi.android.data.SelectorIcons
import io.jeemi.android.data.SelectorIconStore
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.util.UUID

class ManagedResourcesTest {
    @Test fun selectorThumbnailsPersistAcrossLoaderRecreationAndStayBounded() = runBlocking {
        val app = ApplicationProvider.getApplicationContext<JeemiApplication>()
        val directory = File(app.cacheDir, "icon-cache-test-" + UUID.randomUUID()).apply { mkdirs() }
        val bitmap = Bitmap.createBitmap(64, 64, Bitmap.Config.ARGB_8888).apply { eraseColor(0xff725bca.toInt()) }
        try {
            val first = SelectorIcons(fetch = { bitmap }, store = SelectorIconStore(directory))
            assertNotNull(first.load("https://example.invalid/first.png"))
            var calls = 0
            val restored = SelectorIcons(fetch = { calls++; null }, store = SelectorIconStore(directory))
            val cached = restored.load("https://example.invalid/first.png")
            assertNotNull(cached)
            assertEquals(bitmap.getPixel(1, 1), cached!!.getPixel(1, 1))
            assertEquals(0, calls)
            repeat(70) { first.load("https://example.invalid/$it.png") }
            assertEquals(64, directory.listFiles()!!.size)
            assertTrue(directory.listFiles()!!.all { it.name.matches(Regex("[a-f0-9]{64}\\.png")) && it.length() <= 32768 })
            assertTrue(directory.listFiles()!!.sumOf { it.length() } <= 2 * 1024 * 1024)
        } finally {
            check(directory.canonicalFile.parentFile == app.cacheDir.canonicalFile)
            directory.deleteRecursively()
        }
    }
}
