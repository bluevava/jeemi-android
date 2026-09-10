package io.jeemi.android.data

import android.content.pm.PackageManager
import android.content.res.Resources
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.Drawable
import android.util.DisplayMetrics
import android.util.TypedValue

internal fun installedAppDrawable(packages: PackageManager, name: String): Drawable? {
    @Suppress("DEPRECATION")
    val info = packages.getApplicationInfo(name, 0)
    if (info.packageName != name || info.icon == 0) return null
    return applicationIconResource(packages.getResourcesForApplication(info), info.icon)
}

internal fun applicationIconResource(resources: Resources, icon: Int): Drawable? {
    val value = TypedValue()
    resources.getValue(icon, value, true)
    if (value.string?.endsWith(".xml", ignoreCase = true) == true) {
        // Vector/adaptive/layer drawables need the resource loader; request the
        // low-density variant rather than decoding for the screen's full DPI.
        return resources.getDrawableForDensity(icon, DisplayMetrics.DENSITY_LOW, null)
    }
    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true; inScaled = false }
    BitmapFactory.decodeResource(resources, icon, bounds)
    if (bounds.outWidth <= 0 || bounds.outHeight <= 0 || bounds.outWidth.toLong() * bounds.outHeight > 16_777_216) return null
    var sample = 1
    while (maxOf(bounds.outWidth, bounds.outHeight) / sample > 128) sample *= 2
    val options = BitmapFactory.Options().apply { inSampleSize = sample; inScaled = false; inPreferredConfig = Bitmap.Config.ARGB_8888 }
    return BitmapFactory.decodeResource(resources, icon, options)?.let { BitmapDrawable(resources, it) }
}

// The UI owns only a fresh 64x64 software bitmap. Do not retain a resource's
// original bitmap or allow a recycled/hardware bitmap into Compose rendering.
internal fun applicationIconThumbnail(drawable: Drawable): Bitmap? {
    val source = (drawable as? BitmapDrawable)?.bitmap
    if (drawable is BitmapDrawable && (source == null || source.isRecycled ||
            source.width.toLong() * source.height > 1_048_576)) return null
    if (drawable.intrinsicWidth > 2048 || drawable.intrinsicHeight > 2048) return null
    val result = Bitmap.createBitmap(64, 64, Bitmap.Config.ARGB_8888)
    var succeeded = false
    try {
        val canvas = Canvas(result)
        if (source != null) {
            val readable = if (source.config == Bitmap.Config.HARDWARE) source.copy(Bitmap.Config.ARGB_8888, false) else source
            if (readable == null) return null
            try { canvas.drawBitmap(readable, null, Rect(0, 0, 64, 64), Paint(Paint.FILTER_BITMAP_FLAG))
            } finally { if (readable !== source) readable.recycle() }
        } else {
            val previous = Rect(drawable.bounds)
            try { drawable.setBounds(0, 0, 64, 64); drawable.draw(canvas)
            } finally { drawable.bounds = previous }
        }
        succeeded = true
        return result
    } finally { if (!succeeded) result.recycle() }
}
