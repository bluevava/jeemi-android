package io.jeemi.android.domain

import io.jeemi.android.data.InstalledApp
import io.jeemi.android.data.filterInstalledApps
import org.junit.Assert.*
import org.junit.Test

class InstalledAppsFilterTest {
    @Test fun namesPackagesAndSystemSwitchFilterWithoutChangingSourceOrder() {
        val apps = listOf(InstalledApp("com.example.reader", "阅读器", false),
            InstalledApp("com.android.settings", "Settings", true), InstalledApp("com.example.music", "Music", false))
        assertEquals(listOf(apps[0], apps[2]), filterInstalledApps(apps, "", false))
        assertEquals(listOf(apps[0]), filterInstalledApps(apps, "阅读", false))
        assertEquals(listOf(apps[0]), filterInstalledApps(apps, " EXAMPLE.READER ", false))
        assertTrue(filterInstalledApps(apps, "settings", false).isEmpty())
        assertEquals(listOf(apps[1]), filterInstalledApps(apps, "SETTINGS", true))
        assertTrue(filterInstalledApps(apps, "notinstalled", true).isEmpty())
        assertEquals(3, apps.size)
    }
}
