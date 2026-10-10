package io.jeemi.android.runtime

import android.os.ParcelFileDescriptor
import android.system.ErrnoException
import android.system.Os
import android.system.OsConstants
import android.system.StructPollfd
import java.io.FileDescriptor
import org.json.JSONObject
import mobile.Mobile

internal fun ownedTunnelActive(tun: ParcelFileDescriptor): Boolean =
    tunnelDescriptorActive(tun.fileDescriptor) && Mobile.androidTunnelActive(tun.fd.toLong())

/** Observe this session's descriptor without reading packets or preparing another VPN. */
internal fun tunnelDescriptorActive(descriptor: FileDescriptor): Boolean {
    if (!descriptor.valid()) return false
    val poll = StructPollfd().apply { fd = descriptor; events = 0 }
    try {
        Os.poll(arrayOf(poll), 0)
    } catch (error: ErrnoException) {
        if (error.errno == OsConstants.EINTR) return true // Retry on the next observation.
        if (error.errno == OsConstants.EBADF) return false
        throw error
    }
    val lost = OsConstants.POLLERR or OsConstants.POLLHUP or OsConstants.POLLNVAL
    return poll.revents.toInt() and lost == 0
}

internal fun coreTunnelEnabled(config: JSONObject): Boolean = config.optJSONObject("tun")?.let {
    it.optBoolean("enable") && it.optInt("file-descriptor") == 3
} == true

/** A deactivated tunnel is an external stop, even while the core process remains alive. */
internal class TunnelDeactivated : Exception()
