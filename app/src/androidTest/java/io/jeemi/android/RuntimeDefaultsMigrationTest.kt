package io.jeemi.android

import androidx.lifecycle.ViewModelProvider
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.rules.ActivityScenarioRule
import io.jeemi.android.data.LibraryRepository
import io.jeemi.android.data.withUpgradedRuntimeDefaults
import io.jeemi.android.domain.Library
import io.jeemi.android.domain.Preferences
import io.jeemi.android.domain.RuntimeState
import io.jeemi.android.ui.JeemiViewModel
import mobile.Mobile
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import java.io.File
import java.util.UUID

private val legacyFilter = listOf("+.lan", "+.local", "+.home.arpa", "+.localdomain", "+.localhost", "+.msftconnecttest.com", "+.msftncsi.com")
private fun legacyRuntime() = JSONObject().put("dnsFakeIpFilter", JSONArray(legacyFilter))
    .put("dnsFakeIpFilterEnabled", true).put("dnsFakeIpFilterMerge", "override")
    .put("ipv6", false).put("listenPort", 7893)

class RuntimeDefaultsMigrationTest {
    @get:Rule(order = 0) val library = TestLibrary { app ->
        val item = app.engine.normalize("Migration fixture", "# original retained\nrules: ['MATCH,DIRECT']")
        Library(listOf(item), item.id, Preferences(runtimeJson = legacyRuntime().toString()))
    }
    @get:Rule(order = 1) val activity = ActivityScenarioRule(MainActivity::class.java)
    private lateinit var app: JeemiApplication
    private lateinit var model: JeemiViewModel
    private fun ready(scenario: ActivityScenario<MainActivity> = activity.scenario) {
        scenario.onActivity { app = it.application as JeemiApplication; model = ViewModelProvider(it)[JeemiViewModel::class.java] }
        await { model.state.value.loaded || model.state.value.error != null }
        assertNull(model.state.value.error)
    }
    private fun await(condition: () -> Boolean) {
        val deadline = System.currentTimeMillis() + 30_000
        while (!condition()) { check(System.currentTimeMillis() < deadline) { "migration_timeout" }; Thread.sleep(50) }
    }

    @Test fun startupMigratesAtomicallyAndLaterUserEditsAreNotMigratedAgain() {
        ready()
        val saved = app.repository.load()
        val runtime = JSONObject(saved.preferences.runtimeJson)
        assertEquals(JSONObject(Mobile.runtimeDefaults()).getJSONArray("dnsFakeIpFilter").toString(), runtime.getJSONArray("dnsFakeIpFilter").toString())
        assertEquals(92, runtime.getJSONArray("dnsFakeIpFilter").length())
        assertEquals(1, runtime.getInt("dnsFakeIpFilterDefaultsVersion"))
        assertTrue(runtime.getBoolean("dnsFakeIpFilterEnabled"))
        assertEquals("override", runtime.getString("dnsFakeIpFilterMerge"))
        assertFalse(runtime.getBoolean("ipv6")); assertEquals(7893, runtime.getInt("listenPort"))
        assertEquals("# original retained\nrules: ['MATCH,DIRECT']", saved.selected!!.original)
        assertEquals(RuntimeState.Stopped, app.runtime.state.value)
        val before = model.state.value.saveRevision
        val edited = JSONObject(model.state.value.library.preferences.runtimeJson).put("dnsFakeIpFilter", JSONArray(legacyFilter))
        activity.scenario.onActivity { model.savePreferences(model.state.value.library.preferences.copy(runtimeJson = edited.toString())) }
        await { model.state.value.saveRevision > before || model.state.value.error != null }
        assertNull(model.state.value.error)
        val previousModel = model
        activity.scenario.close()
        ActivityScenario.launch(MainActivity::class.java).use { reopened ->
            ready(reopened)
            assertNotSame(previousModel, model)
            assertEquals(JSONArray(legacyFilter).toString(), JSONObject(app.repository.load().preferences.runtimeJson).getJSONArray("dnsFakeIpFilter").toString())
            assertEquals(RuntimeState.Stopped, app.runtime.state.value)
        }
    }

    @Test fun legacyLibrariesPreserveCustomControlsAndDeferInvalidCandidates() {
        ready()
        val directory = File(app.cacheDir, "runtime-migration-${UUID.randomUUID()}").apply { mkdirs() }
        try {
            val repository = LibraryRepository(directory)
            val base = app.repository.load()
            val cases = listOf(
                legacyRuntime().put("dnsFakeIpFilterEnabled", false),
                legacyRuntime().put("dnsFakeIpFilterMerge", "append"),
                legacyRuntime().put("dnsFakeIpFilter", JSONArray()),
                legacyRuntime().put("dnsFakeIpFilter", JSONArray(legacyFilter.reversed())),
                legacyRuntime().put("dnsFakeIpFilter", JSONArray(listOf("custom.invalid"))),
            )
            for (schema in 1..5) for (input in cases) {
                val original = base.copy(preferences = base.preferences.copy(runtimeJson = input.toString()))
                repository.save(original)
                val file = File(directory, "library-v1.json")
                file.writeText(JSONObject(file.readText()).put("schemaVersion", schema).toString())
                val loaded = repository.load()
                val migrated = withUpgradedRuntimeDefaults(loaded, app)
                val actual = JSONObject(migrated.preferences.runtimeJson)
                assertEquals(1, actual.getInt("dnsFakeIpFilterDefaultsVersion"))
                actual.remove("dnsFakeIpFilterDefaultsVersion")
                val expected = JSONObject(input.toString())
                if (input.getJSONArray("dnsFakeIpFilter").toString() == JSONArray(legacyFilter).toString()) {
                    expected.put("dnsFakeIpFilter", JSONObject(Mobile.runtimeDefaults()).getJSONArray("dnsFakeIpFilter"))
                }
                assertEquals(expected.keys().asSequence().toSet(), actual.keys().asSequence().toSet())
                expected.keys().asSequence().forEach { key -> assertEquals(expected.get(key).toString(), actual.get(key).toString()) }
                assertEquals(loaded.copy(preferences = loaded.preferences.copy(runtimeJson = migrated.preferences.runtimeJson)), migrated)
                repository.save(migrated)
                assertEquals(migrated, withUpgradedRuntimeDefaults(repository.load(), app))
            }
            val legacyPaths = base.copy(preferences = Preferences(runtimeValues = mapOf(
                "/dns/fake-ip-filter" to JSONArray(legacyFilter).toString(), "/ipv6" to "false")))
            val converted = JSONObject(withUpgradedRuntimeDefaults(legacyPaths, app).preferences.runtimeJson)
            assertEquals(92, converted.getJSONArray("dnsFakeIpFilter").length())
            assertFalse(converted.getBoolean("ipv6"))
            val broken = base.copy(preferences = base.preferences.copy(runtimeJson = legacyRuntime().toString()),
                subscriptions = base.subscriptions.map { it.copy(original = "dns: [\nrules: ['MATCH,DIRECT']") })
            repository.save(broken)
            val before = File(directory, "library-v1.json").readText()
            assertEquals(broken, withUpgradedRuntimeDefaults(repository.load(), app))
            assertEquals(before, File(directory, "library-v1.json").readText())
            val normalized = app.engine.normalizePreferences(broken.preferences)
            assertEquals(0, JSONObject(normalized.runtimeJson).optInt("dnsFakeIpFilterDefaultsVersion"))
            val repaired = broken.copy(preferences = normalized, subscriptions = base.subscriptions)
            val retried = JSONObject(withUpgradedRuntimeDefaults(repaired, app).preferences.runtimeJson)
            assertEquals(92, retried.getJSONArray("dnsFakeIpFilter").length())
            assertEquals(1, retried.getInt("dnsFakeIpFilterDefaultsVersion"))
        } finally { directory.deleteRecursively() }
    }
}
