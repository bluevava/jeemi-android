package io.jeemi.android

import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.lifecycle.ViewModelProvider
import io.jeemi.android.domain.*
import io.jeemi.android.ui.JeemiViewModel
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

class ScriptSourceTransactionTest {
    private val id = "b".repeat(32)
    @get:Rule(order = 0) val server = RuntimeResourceServer()
    @get:Rule(order = 1) val library = TestLibrary { app ->
        val source = "proxies: [{name: Local.Test, type: socks5, server: node.example.invalid, port: 1080}]\n" +
            "proxy-groups: [{name: Route, type: select, proxies: [DIRECT, Local.Test]}]\nrules: ['MATCH,Route']\n"
        val first = app.engine.normalize("Selected", source + "test-marker: allow\n").copy(handlerId = id)
        val other = app.engine.normalize("Unselected", source + "test-marker: reject\n").copy(handlerId = id)
        val script = LocalResource(id, "Script", ResourceKind.SCRIPT, "function main(config) { return config; }", formatVersion = 2)
        Library(listOf(first, other), first.id, resources = listOf(script))
    }
    @get:Rule(order = 2) val compose = createAndroidComposeRule<MainActivity>()
    private val model get() = ViewModelProvider(compose.activity)[JeemiViewModel::class.java]
    private fun current() = model.state.value.library.resources.single { it.id == id }
    private fun finished() {
        compose.waitUntil(45_000) { !model.state.value.resourceDownloading && !model.state.value.busy }
    }
    @Test fun urlSaveCacheReuseRefreshFailureAndCancellationPreserveTransactions() {
        compose.waitUntil(20_000) { model.state.value.loaded }
        compose.runOnUiThread {
            model.openResource(current())
            model.scriptInput.value = server.url + "/script.js"
            model.saveResource()
        }
        finished()
        assertNull(model.state.value.error)
        assertEquals(1, server.scripts.get())
        assertEquals(server.url + "/script.js", current().sourceUrl)
        assertTrue(current().content.contains("downloaded"))
        val cached = current()
        compose.runOnUiThread {
            model.openResource(cached.copy(name = "Renamed"))
            model.saveResource()
        }
        finished()
        assertEquals(1, server.scripts.get())
        assertEquals("Renamed", current().name)
        val beforeFailure = current()
        server.script = "function main(config) { if (config['test-marker'] === 'reject') config.rules = ['RULE-SET,Missing,DIRECT']; return config; }"
        compose.runOnUiThread { model.refreshScript(id) }
        finished()
        assertNotNull("The unselected affected subscription must be validated", model.state.value.error)
        assertEquals(beforeFailure, current())
        server.scriptDelayMillis = 2000
        server.script = "function main(config) { config['script-test'] = 'cancelled'; return config; }"
        val count = server.scripts.get()
        compose.runOnUiThread { model.dismissError(); model.refreshScript(id) }
        compose.waitUntil(10_000) { server.scripts.get() > count }
        compose.runOnUiThread { model.cancelResourceNetwork() }
        Thread.sleep(2200)
        assertEquals(beforeFailure, current())
        assertNull(model.state.value.error)
        assertEquals(RuntimeState.Stopped, model.runtime.value)
    }
}
