// Author: Bluevava
// Open-source repository: https://github.com/bluevava/jeemi-android

package io.jeemi.android

import android.app.Application
import io.jeemi.android.data.GoBusinessEngine
import io.jeemi.android.data.LibraryRepository
import io.jeemi.android.runtime.VpnController

class JeemiApplication : Application() {
    val repository by lazy { LibraryRepository(noBackupFilesDir) }
    val engine by lazy { GoBusinessEngine() }
    val geodata by lazy { io.jeemi.android.data.GeoRepository(this) }
    internal val selectorIcons by lazy { io.jeemi.android.data.SelectorIcons(
        store = io.jeemi.android.data.SelectorIconStore(java.io.File(noBackupFilesDir, "selector-icons"))) }
    val runtime by lazy { VpnController(this) }
}
