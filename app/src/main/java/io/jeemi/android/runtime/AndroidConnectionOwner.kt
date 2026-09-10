package io.jeemi.android.runtime

import android.content.Context
import android.net.ConnectivityManager
import android.os.Build
import android.system.OsConstants
import io.jeemi.android.data.unambiguousApplicationPackage
import mobile.ConnectionOwner
import org.json.JSONObject
import java.io.File
import java.net.InetAddress
import java.net.InetSocketAddress

internal class AndroidConnectionOwner(private val context: Context) : ConnectionOwner {
    override fun lookup(network: String, source: String, destination: String, sourcePort: Long, destinationPort: Long): String {
        return runCatching {
            val uid = if (Build.VERSION.SDK_INT >= 29) context.getSystemService(ConnectivityManager::class.java)
                .getConnectionOwnerUid(if (network == "tcp") OsConstants.IPPROTO_TCP else OsConstants.IPPROTO_UDP,
                    InetSocketAddress(InetAddress.getByName(source), sourcePort.toInt()),
                    InetSocketAddress(InetAddress.getByName(destination), destinationPort.toInt()))
            else legacyOwner(network, source, sourcePort.toInt())
            if (uid < 0) return ""
            // Shared UIDs cannot identify a single package safely.
            val packages = runCatching { context.packageManager.getPackagesForUid(uid) }.getOrNull()
            JSONObject().put("uid", uid).put("package", unambiguousApplicationPackage(packages)).toString()
        }.getOrDefault("")
    }

    private fun legacyOwner(network: String, source: String, port: Int): Int {
        // Android 8/9 expose proc socket ownership to VPN apps on some devices.
        // OEM denial or ambiguous matches remain unavailable; never guess.
        val address = InetAddress.getByName(source).address
        val owners = mutableSetOf<Int>()
        listOf(network, network + "6").forEach { table ->
            runCatching { File("/proc/net/$table").bufferedReader().useLines { lines ->
                lines.drop(1).take(20000).forEach { line ->
                    val fields = line.trim().split(Regex("\\s+"))
                    val local = fields.getOrNull(1)?.split(':') ?: return@forEach
                    if (local.size == 2 && local[1].toIntOrNull(16) == port) {
                        val bytes = local[0].chunked(8).flatMap { word -> word.chunked(2).reversed() }
                            .map { it.toInt(16).toByte() }.toByteArray()
                        val parsed = InetAddress.getByAddress(bytes).address
                        if (parsed.contentEquals(address)) fields.getOrNull(7)?.toIntOrNull()?.let(owners::add)
                    }
                }
            } }
        }
        return owners.singleOrNull() ?: -1
    }
}
