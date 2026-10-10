package io.jeemi.android

import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import androidx.test.platform.app.InstrumentationRegistry
import io.jeemi.android.data.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class AppInstallationTest {
    @Test fun realInstalledAppWithoutIconIsNotMarkedUninstalled() = runBlocking {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val details = InstalledAppDetails(instrumentation.targetContext.packageManager)
        assertEquals(AppInstallation.INSTALLED, details.load(instrumentation.context.packageName).installation)
        assertEquals(AppInstallation.INSTALLED, details.load(instrumentation.targetContext.packageName).installation)
        assertEquals(AppInstallation.NOT_INSTALLED, details.load("org.example.uninstalled").installation)
    }

    @Test fun labelFailuresCannotBecomeMissingAndLookupFailuresStayUnknown() = runBlocking {
        val packageName = "com.example.test"
        val info = ApplicationInfo().apply { this.packageName = packageName }
        for (failure in listOf<() -> Nothing>({ throw PackageManager.NameNotFoundException() }, { throw SecurityException() },
            { throw IllegalArgumentException() }, { throw OutOfMemoryError("injected") })) {
            val details = InstalledAppDetails({ info }, { failure() }).load(packageName)
            assertEquals(AppInstallation.INSTALLED, details.installation)
            assertEquals(packageName, details.label)
        }
        assertEquals(AppInstallation.UNKNOWN, InstalledAppDetails({ throw SecurityException() }, { "unused" }).load(packageName).installation)
        assertEquals(AppInstallation.NOT_INSTALLED, InstalledAppDetails({ throw PackageManager.NameNotFoundException() }, { "unused" }).load(packageName).installation)
        var calls = 0
        val pressured = InstalledAppDetails({ calls++; throw OutOfMemoryError("injected") }, { "unused" })
        assertEquals(AppInstallation.UNKNOWN, pressured.load(packageName).installation)
        assertEquals(AppInstallation.UNKNOWN, pressured.load("com.example.next").installation)
        assertEquals(1, calls)
        val guarded = InstalledAppDetails({ calls++; info }, { "unused" })
        assertEquals(AppInstallation.UNKNOWN, guarded.load("/data/app/example").installation)
        assertEquals(1, calls)
        try {
            InstalledAppDetails({ throw CancellationException() }, { "unused" }).load(packageName)
            fail("cancellation swallowed")
        } catch (_: CancellationException) { }
    }
}
