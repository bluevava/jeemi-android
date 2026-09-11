package io.jeemi.android.data

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.AtomicFile
import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.file.Files
import java.security.MessageDigest

/** Optional private thumbnails: 64 files, <=32 KiB each, no source URL on disk. */
internal class SelectorIconStore(private val directory: File) {
    private fun file(url: String): File = File(directory,
        MessageDigest.getInstance("SHA-256").digest(url.toByteArray()).joinToString("") { "%02x".format(it) } + ".png")

    fun read(url: String): Bitmap? = try {
        val target = file(url)
        if (Files.isSymbolicLink(directory.toPath()) || Files.isSymbolicLink(target.toPath())) null else {
            val bytes = target.inputStream().use { it.readBounded(32 * 1024) }
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
            if (bounds.outWidth !in 1..64 || bounds.outHeight !in 1..64) null else {
                BitmapFactory.decodeByteArray(bytes, 0, bytes.size,
                    BitmapFactory.Options().apply { inPreferredConfig = Bitmap.Config.ARGB_8888 })?.also {
                    target.setLastModified(System.currentTimeMillis())
                }
            }
        }
    } catch (_: Exception) { null }

    fun write(url: String, bitmap: Bitmap) {
        try {
            if (bitmap.isRecycled || bitmap.width !in 1..64 || bitmap.height !in 1..64 ||
                Files.isSymbolicLink(directory.toPath())) return
            val bytes = ByteArrayOutputStream().use {
                if (!bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)) return
                it.toByteArray()
            }
            if (bytes.size > 32 * 1024 || (!directory.isDirectory && !directory.mkdirs())) return
            val target = file(url)
            if (Files.isSymbolicLink(target.toPath())) return
            val atomic = AtomicFile(target)
            val output = atomic.startWrite()
            try { output.write(bytes); atomic.finishWrite(output) }
            catch (error: Exception) { atomic.failWrite(output); throw error }
            directory.listFiles()?.filter { it.name.matches(Regex("[0-9a-f]{64}\\.png")) }
                ?.sortedByDescending { it.lastModified() }?.drop(64)?.forEach { it.delete() }
        } catch (_: Exception) { /* An optional thumbnail never prevents a usable result. */ }
    }
}
