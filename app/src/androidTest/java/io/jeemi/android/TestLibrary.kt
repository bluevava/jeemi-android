package io.jeemi.android
import androidx.test.core.app.ApplicationProvider
import io.jeemi.android.domain.Library
import org.junit.rules.ExternalResource

/** Used only on the explicitly selected isolated test emulator. */
class TestLibrary(private val installBundledRouting: Boolean = false,
    private val initial: (JeemiApplication) -> Library = { Library() }) : ExternalResource() {
    private var previous: Library? = null
    override fun before() {
        val app = ApplicationProvider.getApplicationContext<JeemiApplication>()
        check(app.packageName.endsWith(".debug"))
        previous = app.repository.load()
        val fixture = initial(app)
        app.repository.save(if (installBundledRouting) fixture else fixture.copy(bundledRoutingVersion = 1))
    }
    override fun after() {
        val app = ApplicationProvider.getApplicationContext<JeemiApplication>()
        previous?.let { app.repository.save(it) }
    }
}
