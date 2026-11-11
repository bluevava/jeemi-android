package io.jeemi.android

import android.app.Activity
import android.app.ActivityManager
import android.app.Notification
import android.app.NotificationManager
import android.content.ComponentName
import android.content.pm.ServiceInfo
import androidx.test.platform.app.InstrumentationRegistry
import io.jeemi.android.domain.RuntimeState
import io.jeemi.android.runtime.JeemiVpnService
import org.junit.Assert.*

/** Exercises Android task removal while the real core carries separate-UID traffic. */
internal class VpnTaskRemovalProbe(private val app: JeemiApplication) : AutoCloseable {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val tasks = app.getSystemService(ActivityManager::class.java)
    private val notifications = app.getSystemService(NotificationManager::class.java)
    private val sessionId = requireNotNull(app.runtime.live.value).sessionId
    private val api = requireNotNull(app.runtime.api)
    private val service = requireNotNull(app.runtime.service)
    private val homes = java.io.File(app.noBackupFilesDir, "sessions").listFiles().orEmpty().map { it.name }
    private var reopened: Activity? = null

    fun remove(activity: MainActivity) {
        val info = app.packageManager.getServiceInfo(ComponentName(app, JeemiVpnService::class.java), 0)
        assertEquals(0, info.flags and ServiceInfo.FLAG_STOP_WITH_TASK)
        instrumentation.runOnMainSync { activity.finishAndRemoveTask() }
        eventually { activity.isDestroyed && tasks.appTasks.isEmpty() }
        assertFalse(app.uiVisible.value)
        // Observe a complete background liveness interval with no UI task.
        Thread.sleep(11_000)
        assertSameSession()
        assertTrue(notification().flags and Notification.FLAG_FOREGROUND_SERVICE != 0)
    }

    fun verifyReturnAndStop() {
        assertTrue(tasks.appTasks.isEmpty())
        assertSameSession()
        val monitor = instrumentation.addMonitor(MainActivity::class.java.name, null, false)
        try {
            notification().contentIntent.send()
            reopened = requireNotNull(instrumentation.waitForMonitorWithTimeout(monitor, 10_000))
            eventually { tasks.appTasks.isNotEmpty() }
            assertSameSession()
        } finally {
            instrumentation.removeMonitor(monitor)
        }
        // Remove the newly created task again; stopping must work with no Activity.
        instrumentation.runOnMainSync { reopened!!.finishAndRemoveTask() }
        eventually { reopened!!.isDestroyed && tasks.appTasks.isEmpty() }
        notification().actions.single { it.title.toString() == app.getString(R.string.vpn_stop) }.actionIntent.send()
        eventually { app.runtime.state.value == RuntimeState.Stopped }
        assertNull(app.runtime.live.value)
        assertNull(app.runtime.api)
        assertNull(app.runtime.service)
        assertTrue(java.io.File(app.noBackupFilesDir, "sessions").listFiles().orEmpty().isEmpty())
        assertTrue(notifications.activeNotifications.none { it.id == 1 })
        Thread.sleep(2000)
        assertEquals(RuntimeState.Stopped, app.runtime.state.value)
    }

    private fun notification(): Notification = notifications.activeNotifications.single { it.id == 1 }.notification
    private fun assertSameSession() {
        assertTrue(app.runtime.state.value is RuntimeState.Running)
        assertEquals(sessionId, app.runtime.live.value?.sessionId)
        assertSame(api, app.runtime.api)
        assertSame(service, app.runtime.service)
        assertEquals(homes, java.io.File(app.noBackupFilesDir, "sessions").listFiles().orEmpty().map { it.name })
        assertTrue(api.call("/configs").getJSONObject("tun").getBoolean("enable"))
    }
    private fun eventually(condition: () -> Boolean) {
        val deadline = android.os.SystemClock.elapsedRealtime() + 20_000
        while (!condition()) {
            check(android.os.SystemClock.elapsedRealtime() < deadline) { "task_removal_timeout" }
            Thread.sleep(100)
        }
    }
    override fun close() {
        reopened?.takeUnless { it.isDestroyed }?.let { activity ->
            instrumentation.runOnMainSync { activity.finishAndRemoveTask() }
        }
    }
}
