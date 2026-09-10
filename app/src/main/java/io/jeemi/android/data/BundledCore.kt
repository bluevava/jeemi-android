package io.jeemi.android.data

import android.content.Context
import java.io.File
import java.util.concurrent.TimeUnit

/** Runs only the APK-installed executable. Version probing never starts a proxy. */
class BundledCore(private val context: Context) {
    val executable: File get() = File(context.applicationInfo.nativeLibraryDir, "libmihomo_exec.so")
    fun version(): String {
        require(executable.isFile && executable.canExecute())
        val process = ProcessBuilder(executable.absolutePath, "-v").redirectErrorStream(true).start()
        try {
            require(process.waitFor(5, TimeUnit.SECONDS))
            val output = process.inputStream.use { it.readBounded(16 * 1024) }.toString(Charsets.UTF_8).trim()
            require(process.exitValue() == 0 && output.startsWith("Mihomo"))
            return output
        } finally { process.destroyForcibly() }
    }
}
