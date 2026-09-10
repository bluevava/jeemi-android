package io.jeemi.android

import android.app.Application
import io.jeemi.android.data.GoBusinessEngine
import io.jeemi.android.data.LibraryRepository
import io.jeemi.android.runtime.VpnController

class JeemiApplication : Application() {
    val repository by lazy { LibraryRepository(noBackupFilesDir) }
    val engine by lazy { GoBusinessEngine() }
    val geodata by lazy { io.jeemi.android.data.GeoRepository(this) }
    val runtime by lazy { VpnController(this) }
}
