package io.jeemi.android

import androidx.test.core.app.ApplicationProvider
import androidx.test.platform.app.InstrumentationRegistry
import io.jeemi.android.data.*
import io.jeemi.android.domain.*
import io.jeemi.android.ui.JeemiViewModel
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.withLock
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

class SubscriptionAutoUpdateTest {
    @get:Rule val library = TestLibrary()
    private val app get() = ApplicationProvider.getApplicationContext<JeemiApplication>()
    private val source = "proxies: [{name: Test, type: socks5, server: example.invalid, port: 1080}]\n" +
        "proxy-groups: [{name: Proxy, type: select, proxies: [Test, DIRECT]}]\nrules: ['MATCH,Proxy']\n"
    private fun profile(id: String) = app.engine.normalize(id, source).copy(id = id, sourceUrl = "https://example.invalid/$id",
        updatedAt = 1_000, autoUpdateIntervalMinutes = 10, description = "Keep notes", selections = mapOf("Proxy" to "Test"))
    private fun save(value: Library) = runBlocking { app.repository.mutations.withLock { app.repository.save(value.copy(bundledRoutingVersion = 1)) } }
    private fun eventually(condition: () -> Boolean) {
        val deadline = android.os.SystemClock.elapsedRealtime() + 20_000
        while (!condition()) { check(android.os.SystemClock.elapsedRealtime() < deadline) { "auto_update_timeout" }; Thread.sleep(50) }
    }
    private fun fetcher(body: () -> String, calls: AtomicInteger, status: () -> Int = { 200 }) = SubscriptionFetcher(openConnection = { url ->
        calls.incrementAndGet()
        object : HttpURLConnection(url) {
            override fun connect() = Unit
            override fun usingProxy() = false
            override fun disconnect() = Unit
            override fun getResponseCode() = status()
            override fun getInputStream() = ByteArrayInputStream(body().toByteArray())
            override fun getHeaderField(name: String?): String? = when (name) {
                "Subscription-Userinfo" -> "upload=10; download=20; total=100"
                "Profile-Update-Interval" -> "24"
                else -> null
            }
        }
    })

    @Test fun onlySelectedRefreshesAndFailuresKeepTheOldLibraryUntilNextInterval() = runBlocking {
        val first = profile("first")
        val second = profile("second")
        save(Library(listOf(first, second), first.id))
        val calls = AtomicInteger()
        var now = 601_000L
        var body = source + "# new revision\n"
        var status = 200
        val updater = SubscriptionAutoUpdater(app, { now }) { fetcher({ body }, calls, { status }) }
        try {
            updater.updateDue()
            var stored = app.repository.load()
            assertEquals(1, calls.get())
            assertEquals(body, stored.selected!!.original)
            assertEquals(first.description, stored.selected!!.description)
            assertEquals(first.selections, stored.selected!!.selections)
            assertEquals(10, stored.selected!!.updateIntervalMinutes)
            assertEquals(20L, stored.selected!!.downloadedBytes)
            assertEquals(second, stored.subscriptions[1])
            assertEquals(RuntimeState.Stopped, app.runtime.state.value)
            updater.updateDue(); assertEquals(1, calls.get())

            now += 600_000
            body = "proxies: ["
            updater.updateDue()
            assertEquals(stored, app.repository.load())
            assertEquals(SubscriptionUpdateError.VALIDATION, updater.errors.value[first.id])
            updater.updateDue(); assertEquals(2, calls.get())
            now += 600_000
            status = 500
            updater.updateDue()
            assertEquals(stored, app.repository.load())
            assertEquals(SubscriptionUpdateError.DOWNLOAD, updater.errors.value[first.id])

            status = 200; body = source
            save(stored.selecting(second.id))
            updater.updateDue()
            assertEquals(4, calls.get())
            assertEquals(second.id, app.repository.load().selectedId)
            assertEquals(now, app.repository.load().selected!!.updatedAt)
            stored = app.repository.load().copy(selectedId = null)
            save(stored); updater.updateDue(); assertEquals(4, calls.get())
            val text = second.copy(sourceUrl = "", autoUpdateIntervalMinutes = 0)
            save(stored.copy(subscriptions = listOf(text), selectedId = text.id))
            updater.updateDue(); assertEquals(4, calls.get())
        } finally { updater.close() }
    }

    @Test fun busyTransactionsAreSkippedAndCancellationCannotCommitDownloadedData() = runBlocking {
        save(Library(listOf(profile("first")), "first"))
        val before = app.repository.load()
        val started = CountDownLatch(1)
        val disconnected = CountDownLatch(1)
        val calls = AtomicInteger()
        val updater = SubscriptionAutoUpdater(app, { 601_000L }) {
            SubscriptionFetcher(openConnection = { url ->
                calls.incrementAndGet()
                object : HttpURLConnection(url) {
                    override fun connect() = Unit
                    override fun usingProxy() = false
                    override fun disconnect() { disconnected.countDown() }
                    override fun getResponseCode(): Int {
                        started.countDown()
                        disconnected.await(10, TimeUnit.SECONDS)
                        return 200
                    }
                    override fun getInputStream() = ByteArrayInputStream(source.toByteArray())
                }
            })
        }
        try {
            app.repository.mutations.withLock { updater.updateDue() }
            assertEquals(0, calls.get())
            val job = launch(Dispatchers.Default) { updater.updateDue() }
            assertTrue(started.await(5, TimeUnit.SECONDS))
            job.cancelAndJoin()
            assertTrue(disconnected.await(1, TimeUnit.SECONDS))
            assertEquals(before, app.repository.load())
            assertTrue(updater.errors.value.isEmpty())
        } finally { updater.close() }
    }

    @Test fun visibilityAndEditorPauseControlTheAutomaticLoopAndResumeOnlyOnce() {
        save(Library(listOf(profile("first")), "first"))
        val now = java.util.concurrent.atomic.AtomicLong(601_000L)
        val calls = AtomicInteger()
        val visible = kotlinx.coroutines.flow.MutableStateFlow(false)
        val updater = SubscriptionAutoUpdater(app, now::get, visible) { fetcher({ source }, calls) }
        try {
            updater.initialized()
            Thread.sleep(200)
            assertEquals(0, calls.get())
            val pause = updater.pause()
            visible.value = true
            Thread.sleep(200)
            assertEquals(0, calls.get())
            pause.close()
            eventually { app.repository.load().selected!!.updatedAt == now.get() }
            assertEquals(1, calls.get())
            visible.value = false
            Thread.sleep(200)
            now.addAndGet(20 * 600_000L)
            Thread.sleep(200)
            assertEquals(1, calls.get())
            visible.value = true
            eventually { app.repository.load().selected!!.updatedAt == now.get() }
            assertEquals(2, calls.get())
            Thread.sleep(200)
            assertEquals(2, calls.get())
        } finally { updater.close() }
    }

    @Test fun hidingTheInterfaceCancelsDownloadAndReturningRetriesWithoutAFailurePenalty() {
        save(Library(listOf(profile("first")), "first"))
        val before = app.repository.load()
        val visible = kotlinx.coroutines.flow.MutableStateFlow(true)
        val started = CountDownLatch(1)
        val disconnected = CountDownLatch(1)
        val calls = AtomicInteger()
        val updater = SubscriptionAutoUpdater(app, { 601_000L }, visible) {
            SubscriptionFetcher(openConnection = { url ->
                val first = calls.incrementAndGet() == 1
                object : HttpURLConnection(url) {
                    override fun connect() = Unit
                    override fun usingProxy() = false
                    override fun disconnect() { if (first) disconnected.countDown() }
                    override fun getResponseCode(): Int {
                        if (first) { started.countDown(); disconnected.await(10, TimeUnit.SECONDS) }
                        return 200
                    }
                    override fun getInputStream() = ByteArrayInputStream(source.toByteArray())
                }
            })
        }
        try {
            updater.initialized()
            assertTrue(started.await(5, TimeUnit.SECONDS))
            visible.value = false
            assertTrue(disconnected.await(3, TimeUnit.SECONDS))
            eventually {
                app.repository.mutations.tryLock().also { locked -> if (locked) app.repository.mutations.unlock() }
            }
            assertEquals(before, app.repository.load())
            assertTrue(updater.errors.value.isEmpty())
            assertEquals(1, calls.get())
            visible.value = true
            eventually { app.repository.load().selected!!.updatedAt == 601_000L }
            assertEquals(2, calls.get())
            assertTrue(updater.errors.value.isEmpty())
        } finally { disconnected.countDown(); updater.close() }
    }

    @Test fun legacyIntervalsRemainUnwrittenAndInvalidIntervalsAreRejected() {
        val directory = File(app.cacheDir, "subscription-interval-${java.util.UUID.randomUUID()}").apply { mkdirs() }
        try {
            val repository = LibraryRepository(directory)
            val item = profile("legacy").copy(autoUpdateIntervalMinutes = null)
            val value = Library(listOf(item), item.id)
            repository.save(value)
            val file = File(directory, "library-v1.json")
            val original = file.readText()
            assertFalse(JSONObject(original).getJSONArray("subscriptions").getJSONObject(0).has("autoUpdateIntervalMinutes"))
            assertEquals(60, repository.load().selected!!.updateIntervalMinutes)
            assertEquals(original, file.readText())
            for (interval in subscriptionUpdateIntervals) {
                val changed = value.copy(subscriptions = listOf(item.copy(autoUpdateIntervalMinutes = interval)))
                repository.save(changed); assertEquals(changed, repository.load())
            }
            for (bad in listOf(5, "60", JSONObject.NULL)) {
                val json = JSONObject(original)
                json.getJSONArray("subscriptions").getJSONObject(0).put("autoUpdateIntervalMinutes", bad)
                file.writeText(json.toString())
                assertThrows(Exception::class.java) { repository.load() }
            }
        } finally { directory.deleteRecursively() }
    }

    @Test fun backgroundCommitIsObservedBeforeTheNextForegroundSaveAndIntervalOnlyEditKeepsTime() = runBlocking {
        val item = profile("first")
        save(Library(listOf(item, profile("second")), item.id))
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val store = androidx.lifecycle.ViewModelStore()
        lateinit var model: JeemiViewModel
        instrumentation.runOnMainSync { model = JeemiViewModel(app); store.put("model", model) }
        val updater = SubscriptionAutoUpdater(app, { 601_000L }) { fetcher({ source + "# background\n" }, AtomicInteger()) }
        try {
            eventually { model.state.value.loaded }
            updater.updateDue()
            instrumentation.runOnMainSync { model.select("second") }
            eventually { model.state.value.library.selectedId == "second" }
            assertEquals(source + "# background\n", app.repository.load().subscriptions.first().original)
            val current = model.state.value.library.selected!!
            instrumentation.runOnMainSync {
                model.importText(current.name, current.original, current.description, current.sourceUrl, current.id,
                    icon = current.icon, autoUpdateIntervalMinutes = 180, expected = current)
            }
            eventually { model.state.value.library.selected?.autoUpdateIntervalMinutes == 180 || model.state.value.error != null }
            assertNull(model.state.value.error)
            assertEquals(current.updatedAt, app.repository.load().selected!!.updatedAt)
            assertEquals(180, app.repository.load().selected!!.autoUpdateIntervalMinutes)
            assertEquals(RuntimeState.Stopped, app.runtime.state.value)
        } finally { updater.close(); instrumentation.runOnMainSync { store.clear() } }
    }
}
