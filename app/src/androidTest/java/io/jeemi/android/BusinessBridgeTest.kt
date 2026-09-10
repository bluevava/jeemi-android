package io.jeemi.android

import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.jeemi.android.data.GoBusinessEngine
import io.jeemi.android.data.LibraryRepository
import io.jeemi.android.domain.Library
import io.jeemi.android.domain.Preferences
import io.jeemi.android.domain.ProxyMode
import io.jeemi.android.domain.*
import io.jeemi.android.data.BundledCore
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.UUID

@RunWith(AndroidJUnit4::class)
class BusinessBridgeTest {
    @Test fun packagedResourcesContainBothChineseAndEnglish() {
        val context = ApplicationProvider.getApplicationContext<JeemiApplication>()
        fun homeLabel(locale: java.util.Locale): String {
            val configuration = android.content.res.Configuration(context.resources.configuration)
            configuration.setLocale(locale)
            return context.createConfigurationContext(configuration).getString(R.string.home)
        }
        assertEquals("主页", homeLabel(java.util.Locale.SIMPLIFIED_CHINESE))
        assertEquals("Home", homeLabel(java.util.Locale.ENGLISH))
    }

    @Test fun nativeImportCompositionAndAtomicPersistenceWorkTogether() {
        val context = ApplicationProvider.getApplicationContext<JeemiApplication>()
        val directory = File(context.cacheDir, "bridge-test-${UUID.randomUUID()}").apply { mkdirs() }
        try {
            val engine = GoBusinessEngine()
            val original = "# preserved\nmode: rule\nproxies:\n  - name: Local test\n    type: socks5\n    server: example.invalid\n    port: 1080\nx-custom: keep\n"
            val item = engine.normalize("Test only", original)
            assertEquals(1, item.nodes.size)
            assertEquals("Local test", item.nodes.single().name)
            val preferences = Preferences(mode = ProxyMode.GLOBAL, overrideMode = true)
            val preview = engine.compose(item, preferences)
            assertTrue(preview.contains("mode: global"))
            assertTrue(preview.contains("x-custom: keep"))
            assertEquals(original, item.original)
            val library = Library(listOf(item), item.id, preferences)
            LibraryRepository(directory).save(library)
            assertEquals(library, LibraryRepository(directory).load())
            assertThrows(Exception::class.java) { engine.normalize("Invalid", "password: a\npassword: b\n") }
            assertEquals(library, LibraryRepository(directory).load())
        } finally {
            directory.deleteRecursively()
        }
    }

    @Test fun packagedMihomoRunsFromTheInstalledNativeDirectory() {
        val context = ApplicationProvider.getApplicationContext<JeemiApplication>()
        val core = BundledCore(context)
        assertTrue(core.executable.canonicalPath.startsWith(context.applicationInfo.nativeLibraryDir))
        assertTrue(core.version().contains("v1.19.30"))
    }

    @Test fun resourceAssociationAndRuntimeSettingsRoundTripAndCompose() {
        val context = ApplicationProvider.getApplicationContext<JeemiApplication>()
        val directory = File(context.cacheDir, "resource-test-${UUID.randomUUID()}").apply { mkdirs() }
        try {
            val engine = GoBusinessEngine()
            val item = engine.normalize("Temporary", "mode: rule\nx-preserved: yes\nrules:\n  - MATCH,DIRECT\n")
            val config = LocalResource("config", "Overrides", ResourceKind.CONFIG, "sniffer:\n  enable: true\n")
            val rules = LocalResource("rules", "Local rules", ResourceKind.RULES, "- DOMAIN,example.invalid,DIRECT\n")
            val prefs = Preferences(runtimeValues = mapOf("/mixed-port" to "7891", "/dns/ipv6" to "false"))
            val library = Library(listOf(item), item.id, prefs, listOf(config, rules)).associating(item.id, "config", listOf("rules"))
            LibraryRepository(directory).save(library)
            val restored = LibraryRepository(directory).load()
            assertEquals(library, restored)
            val preview = engine.compose(restored.selected!!, restored.preferences, restored.resources)
            assertTrue(preview.contains("mixed-port: 7891"))
            assertTrue(preview.contains("x-preserved: yes"))
            assertTrue(preview.indexOf("DOMAIN,") < preview.indexOf("MATCH,"))
            assertTrue(engine.catalog().any { it.path == "/sniffer/enable" })
            val script = LocalResource("script", "Script", ResourceKind.SCRIPT, "function main(config) { config.mode = 'direct'; return config; }")
            val scripted = restored.copy(resources = restored.resources + script).associating(item.id, "script", emptyList())
            assertTrue(engine.compose(scripted.selected!!, scripted.preferences, scripted.resources).contains("mode: rule"))
        } finally { directory.deleteRecursively() }
    }
}
