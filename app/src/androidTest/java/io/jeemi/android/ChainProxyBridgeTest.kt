package io.jeemi.android

import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.jeemi.android.data.*
import io.jeemi.android.domain.*
import mobile.Mobile
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.UUID
import java.util.concurrent.TimeUnit

internal const val CHAIN_SOURCE = "proxies:\n  - {name: HK GM, type: socks5, server: 192.0.2.1, port: 1080}\nproxy-groups: [{name: Main, type: select, proxies: [HK GM]}]\nrules: ['MATCH,Main']\n"
internal const val CHAIN_LANDING = "anytls://fixture@192.0.2.10:443?type=tcp&security=tls&tfo=false#Landing"
internal fun chainMutation(library: String, input: JSONObject): String {
    input.put("revision", chainState(library).revision)
    return JSONObject(Mobile.mutateChainLibrary(library, input.toString())).getJSONObject("library").toString()
}

@RunWith(AndroidJUnit4::class)
class ChainProxyBridgeTest {
    @Test fun oldLibraryReparsesOriginalAndNewLibraryRoundTripsWithIndependentAssociations() {
        val app = ApplicationProvider.getApplicationContext<JeemiApplication>()
        val root = File(app.cacheDir, "chain-bridge-${UUID.randomUUID()}").apply { mkdirs() }
        try {
            val original = "bad-protocol://fixture\n$CHAIN_LANDING"
            val profile = app.engine.normalize("Migration", original).copy(handlerId = "script")
            var chains = chainMutation(EMPTY_CHAIN_LIBRARY, JSONObject().put("action", "save_group").put("kind", "manual").put("name", "Manual"))
            val group = chainState(chains).groups.single()
            chains = chainMutation(chains, JSONObject().put("action", "import_nodes").put("groupId", group.id).put("contents", CHAIN_LANDING))
            val script = LocalResource("script", "Script", ResourceKind.SCRIPT, "function main(c) { c['x-script'] = true; return c; }")
            val linked = profile.copy(chainGroupIds = listOf(group.id), chainRevision = 1)
            val expected = Library(listOf(linked), linked.id, resources = listOf(script), chainLibrary = chains)
            val repository = LibraryRepository(root)
            repository.save(expected)
            assertEquals(expected, repository.load())
            val projected = app.engine.project(linked, expected.preferences, expected.resources, chains)
            assertTrue(projected.yaml.contains("dialer-proxy:")); assertTrue(projected.yaml.contains("x-script: true"))
            assertEquals(1, JSONObject(projected.normalization).getInt("skippedNodes"))
            assertEquals(original, repository.load().selected!!.original)
            // Read a schema-4 record without a chain library. Derivations are
            // recomputed from original text; loading must not rewrite the file.
            val file = File(root, "library-v1.json")
            val legacy = JSONObject(file.readText()).put("schemaVersion", 4).apply { remove("chainLibrary") }
            legacy.getJSONArray("subscriptions").getJSONObject(0).apply {
                remove("chainGroupIds"); remove("chainRevision"); remove("normalizationVersion"); remove("normalizationReport")
                put("normalized", "rules: ['MATCH,DIRECT']")
            }
            file.writeText(legacy.toString()); val before = file.readBytes()
            val loaded = repository.load()
            val reparsed = app.engine.reparse(loaded.selected!!)
            assertEquals(original, reparsed.original); assertEquals(1, reparsed.nodes.size)
            assertEquals(1, reparsed.skippedNodes); assertEquals(EMPTY_CHAIN_LIBRARY, loaded.chainLibrary)
            assertArrayEquals(before, file.readBytes())
            assertFalse(chainState(chains).groups.single().toString().contains("fixture@"))
        } finally { root.deleteRecursively() }
    }

    @Test fun bundledCoreAcceptsComposedChainWithoutStartingVpn() {
        val app = ApplicationProvider.getApplicationContext<JeemiApplication>()
        val root = File(app.cacheDir, "chain-core-${UUID.randomUUID()}").apply { mkdirs() }
        try {
            var chains = chainMutation(EMPTY_CHAIN_LIBRARY, JSONObject().put("action", "save_group").put("kind", "manual").put("name", "Manual"))
            val group = chainState(chains).groups.single()
            chains = chainMutation(chains, JSONObject().put("action", "import_nodes").put("groupId", group.id).put("contents", CHAIN_LANDING))
            val profile = app.engine.normalize("Core fixture", CHAIN_SOURCE).copy(chainGroupIds = listOf(group.id), chainRevision = 1)
            val candidate = app.engine.project(profile, Preferences(), emptyList(), chains)
            assertEquals(1, JSONObject(candidate.chains).getInt("generated"))
            app.geodata.materialize(root)
            File(root, "config.yaml").writeText(candidate.yaml)
            val output = File(root, "validation.log")
            val process = ProcessBuilder(BundledCore(app).executable.absolutePath, "-t", "-d", root.absolutePath)
                .redirectErrorStream(true).redirectOutput(output).start()
            try { assertTrue(process.waitFor(20, TimeUnit.SECONDS)); assertEquals("core rejected synthetic candidate", 0, process.exitValue()) }
            finally { process.destroyForcibly() }
            ChainCandidateValidator(app).use { validator ->
                validator.validate(candidate.yaml)
                assertThrows(Exception::class.java) { validator.validate(candidate.yaml.replace("type: anytls", "type: no-such-protocol")) }
            }
            assertEquals(RuntimeState.Stopped, app.runtime.state.value)
        } finally { root.deleteRecursively() }
    }
}
