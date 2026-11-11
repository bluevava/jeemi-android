// Author: Bluevava
// Open-source repository: https://github.com/bluevava/jeemi-android

package io.jeemi.android

import android.app.Application
import io.jeemi.android.data.GoBusinessEngine
import io.jeemi.android.data.LibraryRepository
import io.jeemi.android.runtime.VpnController
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

class JeemiApplication : Application() {
    private var startedActivities = 0
    private val mutableUiVisible = MutableStateFlow(false)
    internal val uiVisible = mutableUiVisible.asStateFlow()
    @Synchronized internal fun uiStarted() { startedActivities++; mutableUiVisible.value = true }
    @Synchronized internal fun uiStopped() {
        startedActivities = (startedActivities - 1).coerceAtLeast(0)
        mutableUiVisible.value = startedActivities > 0
    }
    val repository by lazy { LibraryRepository(noBackupFilesDir) }
    val engine by lazy { GoBusinessEngine() }
    val geodata by lazy { io.jeemi.android.data.GeoRepository(this) }
    internal val selectorIcons by lazy { io.jeemi.android.data.SelectorIcons(
        store = io.jeemi.android.data.SelectorIconStore(java.io.File(noBackupFilesDir, "selector-icons"))) }
    val runtime by lazy { VpnController(this) }
    internal val subscriptionAutoUpdater by lazy { io.jeemi.android.data.SubscriptionAutoUpdater(this) }
    internal fun subscriptionFetcher() = io.jeemi.android.data.SubscriptionFetcher(
        directFallback = { io.jeemi.android.data.directSubscriptionRoute(this, runtime) })
}
