package io.jeemi.android

import android.content.pm.PackageManager
import android.content.res.Resources
import android.graphics.*
import android.graphics.drawable.*
import androidx.test.platform.app.InstrumentationRegistry
import io.jeemi.android.data.*
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.atomic.AtomicInteger

class InstalledAppInfoTest {
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext
    private class BrokenDrawable(private val failure: () -> Nothing) : Drawable() {
        override fun draw(canvas: Canvas) = failure()
        override fun setAlpha(alpha: Int) = Unit
        override fun setColorFilter(colorFilter: ColorFilter?) = Unit
        @Deprecated("Deprecated in Java") override fun getOpacity() = PixelFormat.TRANSLUCENT
    }

    @Test fun missingIllegalAndUninstalledAppsDoNotCrashOrRetryEachFrame() = runBlocking {
        val calls = AtomicInteger()
        val icons = InstalledAppIcons { calls.incrementAndGet(); throw PackageManager.NameNotFoundException() }
        for (name in listOf("", "/system/bin/netd", "com.app:remote", "com..app", "bad\u0000name", "x".repeat(256))) {
            assertNull(icons.load(name))
        }
        assertEquals(0, calls.get())
        repeat(10) { assertNull(icons.load("org.example.uninstalled")) }
        assertEquals(1, calls.get())
        val real = InstalledAppIcons(context.packageManager)
        assertNull(real.load("io.jeemi.invalid.missing.package"))
        // This instrumentation APK deliberately has no application icon.
        assertNull(real.load(InstrumentationRegistry.getInstrumentation().context.packageName))
    }
    @Test fun bitmapResultsAreIndependentSoftwareThumbnailsAndIgnoreBrokenSources() = runBlocking {
        val source = Bitmap.createBitmap(64, 64, Bitmap.Config.ARGB_8888).apply { eraseColor(Color.GREEN) }
        val icons = InstalledAppIcons { BitmapDrawable(context.resources, source) }
        val result = requireNotNull(icons.load("com.valid.bitmap"))
        assertNotSame(source, result)
        assertEquals(64, result.width); assertEquals(64, result.height)
        assertEquals(Bitmap.Config.ARGB_8888, result.config)
        source.eraseColor(Color.RED); source.recycle()
        assertEquals(Color.GREEN, result.getPixel(32, 32))
        assertFalse(result.isRecycled)
        assertNull(InstalledAppIcons { BitmapDrawable(context.resources, source) }.load("com.recycled.bitmap"))
        val huge = Bitmap.createBitmap(1025, 1024, Bitmap.Config.ARGB_8888)
        try { assertNull(InstalledAppIcons { BitmapDrawable(context.resources, huge) }.load("com.oversized.bitmap")) }
        finally { huge.recycle() }
    }
    @Test fun standardAdaptiveVectorAndHardwareIconsRenderIntoOwnedSmallBitmaps() = runBlocking {
        val real = InstalledAppIcons(context.packageManager)
        for (name in listOf(context.packageName, "com.android.settings")) {
            val bitmap = requireNotNull(real.load(name))
            assertEquals(64, bitmap.width); assertEquals(64, bitmap.height)
            assertFalse(bitmap.isRecycled); assertEquals(Bitmap.Config.ARGB_8888, bitmap.config)
        }
        val adaptive = AdaptiveIconDrawable(ColorDrawable(Color.BLUE), ColorDrawable(Color.YELLOW))
        assertNotNull(InstalledAppIcons { adaptive }.load("com.valid.adaptive"))
        val vector = requireNotNull(context.getDrawable(R.drawable.ic_vpn))
        assertNotNull(InstalledAppIcons { vector }.load("com.valid.vector"))
        // The bundled PNG is 1254x1254; a raster resource must be sampled before
        // allocating its decoded pixels, not decoded full-size then shrunk.
        val sampled = applicationIconResource(context.resources, R.drawable.jeemi_logo) as BitmapDrawable
        assertTrue(sampled.bitmap.width <= 128 && sampled.bitmap.height <= 128)
        assertNotNull(InstalledAppIcons { sampled }.load("com.valid.raster"))
        val software = Bitmap.createBitmap(64, 64, Bitmap.Config.ARGB_8888).apply { eraseColor(Color.BLUE) }
        val hardware = requireNotNull(software.copy(Bitmap.Config.HARDWARE, false))
        try {
            val rendered = requireNotNull(InstalledAppIcons { BitmapDrawable(context.resources, hardware) }.load("com.valid.hardware"))
            assertEquals(Bitmap.Config.ARGB_8888, rendered.config)
            assertEquals(Color.BLUE, rendered.getPixel(32, 32))
        } finally { software.recycle(); hardware.recycle() }
    }
    @Test fun drawableFailuresRestoreBoundsAndUseCachedFallbackIncludingMemoryFailures() = runBlocking {
        val failures = listOf<() -> Nothing>({ throw Resources.NotFoundException() }, { throw IllegalArgumentException() },
            { throw SecurityException() }, { throw StackOverflowError("injected recursive resource") }, { throw OutOfMemoryError("injected icon allocation failure") })
        for (failure in failures) {
            val calls = AtomicInteger()
            val drawable = BrokenDrawable(failure).apply { setBounds(1, 2, 3, 4) }
            val icons = InstalledAppIcons { calls.incrementAndGet(); drawable }
            repeat(3) { assertNull(icons.load("com.broken.icon")) }
            assertEquals(Rect(1, 2, 3, 4), drawable.bounds)
            assertEquals(1, calls.get())
        }
        val calls = AtomicInteger()
        val pressured = InstalledAppIcons { calls.incrementAndGet(); throw OutOfMemoryError("injected lookup allocation failure") }
        assertNull(pressured.load("com.first.app"))
        assertNull(pressured.load("com.second.app"))
        assertEquals(1, calls.get())
    }
    @Test fun boundedIconCacheDoesNotRecycleImagesStillUsedByTheUi() = runBlocking {
        val calls = AtomicInteger()
        val icons = InstalledAppIcons { calls.incrementAndGet(); ColorDrawable(Color.GREEN) }
        val first = requireNotNull(icons.load("com.app.first"))
        repeat(140) { assertNotNull(icons.load("com.app.item$it")) }
        assertEquals(141, calls.get())
        assertNotNull(icons.load("com.app.item139")); assertEquals(141, calls.get())
        assertFalse(first.isRecycled); assertEquals(Color.GREEN, first.getPixel(32, 32))
        assertNotNull(icons.load("com.app.first")); assertEquals(142, calls.get())
    }
    @Test fun labelFailuresAndMalformedUnicodeFallBackWithoutLeakingStyledContent() = runBlocking {
        val calls = AtomicInteger()
        val labels = InstalledAppLabels { name ->
            calls.incrementAndGet()
            when (name) {
                "com.valid.app" -> android.text.SpannableString("\u202e示例\nApp 😀\u0000")
                "com.null.app" -> null
                "com.broken.text" -> object : CharSequence {
                    override val length: Int get() = throw IllegalStateException()
                    override fun get(index: Int) = 'x'
                    override fun subSequence(startIndex: Int, endIndex: Int): CharSequence = ""
                }
                else -> throw PackageManager.NameNotFoundException()
            }
        }
        val names = listOf("com.valid.app", "com.null.app", "com.broken.text", "com.missing.app", "bad/name")
        val result = labels.load(names, "zh-CN")
        assertEquals("示例 App 😀", result["com.valid.app"])
        for (name in names.drop(1).dropLast(1)) assertEquals(name, result[name])
        assertFalse(result.containsKey("bad/name")); assertEquals(4, calls.get())
        labels.load(names, "zh-CN"); assertEquals(4, calls.get())
        labels.load(names, "en"); assertEquals(8, calls.get())
        val boundedCalls = AtomicInteger()
        val bounded = InstalledAppLabels { boundedCalls.incrementAndGet(); "Label" }
        assertEquals(256, bounded.load(List(1000) { "com.app.n$it" }, "en").size)
        assertEquals(256, boundedCalls.get())
        val memoryCalls = AtomicInteger()
        val pressured = InstalledAppLabels { memoryCalls.incrementAndGet(); throw OutOfMemoryError("injected label failure") }
        val fallback = pressured.load(listOf("com.first.app", "com.second.app"), "en")
        assertEquals("com.second.app", fallback["com.second.app"]); assertEquals(1, memoryCalls.get())
    }
    @Test fun cancellingOptionalLookupsDoesNotTurnCancellationIntoCachedFailure() = runBlocking {
        val iconCalls = AtomicInteger(); val nameCalls = AtomicInteger()
        val icons = InstalledAppIcons { iconCalls.incrementAndGet(); throw CancellationException("injected cancellation") }
        val names = InstalledAppLabels { nameCalls.incrementAndGet(); throw CancellationException("injected cancellation") }
        repeat(2) {
            try { icons.load("com.cancelled.app"); fail("cancelled icon lookup returned") } catch (_: CancellationException) { }
            try { names.load(listOf("com.cancelled.app"), "en"); fail("cancelled name lookup returned") } catch (_: CancellationException) { }
        }
        assertEquals(2, iconCalls.get()); assertEquals(2, nameCalls.get())
    }
}
