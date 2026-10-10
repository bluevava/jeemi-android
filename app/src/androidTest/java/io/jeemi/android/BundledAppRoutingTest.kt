package io.jeemi.android

import androidx.appcompat.app.AppCompatDelegate
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.core.os.LocaleListCompat
import androidx.lifecycle.ViewModelProvider
import androidx.test.core.app.ApplicationProvider
import androidx.test.platform.app.InstrumentationRegistry
import io.jeemi.android.data.*
import io.jeemi.android.domain.*
import io.jeemi.android.runtime.ConnectionFeed
import io.jeemi.android.runtime.ConnectionRecord
import io.jeemi.android.ui.JeemiViewModel
import kotlinx.coroutines.runBlocking
import mobile.Mobile
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.ExternalResource
import java.io.File
import java.io.FileInputStream
import java.util.UUID

private const val routingSource = "proxies: [{name: Fixture.Node, type: socks5, server: node.example.invalid, port: 1080}]\n" +
    "proxy-groups: [{name: Source, type: select, proxies: [Fixture.Node, DIRECT]}]\n" +
    "rule-providers: {old: {type: inline, behavior: domain, payload: [source.invalid]}}\n" +
    "rules: ['RULE-SET,old,Source', 'MATCH,Source']\n"

class BundledAppRoutingTest {
    @get:Rule(order = 0) val locale = object : ExternalResource() {
        private var previous = LocaleListCompat.getEmptyLocaleList()
        override fun before() {
            InstrumentationRegistry.getInstrumentation().runOnMainSync {
                // Before the first Activity, AppCompat has no delegate context on API 33+.
                val context = ApplicationProvider.getApplicationContext<JeemiApplication>()
                previous = if (android.os.Build.VERSION.SDK_INT >= 33) {
                    LocaleListCompat.wrap(context.getSystemService(android.app.LocaleManager::class.java).applicationLocales)
                } else AppCompatDelegate.getApplicationLocales()
                setLocales(LocaleListCompat.forLanguageTags(InstrumentationRegistry.getArguments().getString("testLocale", "zh-CN")))
            }
        }
        override fun after() {
            InstrumentationRegistry.getInstrumentation().runOnMainSync { setLocales(previous) }
        }
        private fun setLocales(value: LocaleListCompat) {
            if (android.os.Build.VERSION.SDK_INT >= 33) {
                ApplicationProvider.getApplicationContext<JeemiApplication>()
                    .getSystemService(android.app.LocaleManager::class.java).applicationLocales = android.os.LocaleList.forLanguageTags(value.toLanguageTags())
            } else AppCompatDelegate.setApplicationLocales(value)
        }
    }
    @get:Rule(order = 1) val library = TestLibrary(installBundledRouting = true) { app ->
        val item = app.engine.normalize("Fixture subscription", routingSource)
        Library(listOf(item), item.id)
    }
    @get:Rule(order = 2) val compose = createAndroidComposeRule<MainActivity>()
    private val app get() = compose.activity.application as JeemiApplication
    private val model get() = ViewModelProvider(compose.activity)[JeemiViewModel::class.java]
    private fun ready() {
        val tag = InstrumentationRegistry.getArguments().getString("testLocale", "zh-CN")
        compose.waitUntil(20_000) { model.state.value.loaded || model.state.value.error != null }
        assertNull(model.state.value.error)
        compose.runOnUiThread { AppCompatDelegate.setApplicationLocales(LocaleListCompat.forLanguageTags(tag)) }
        compose.waitUntil(15_000) { compose.activity.resources.configuration.locales[0].language == tag.substringBefore('-') }
    }
    private fun finish() { compose.waitUntil(60_000) { !model.state.value.busy }; assertNull(model.state.value.error) }
    private fun click(label: Int) = compose.onNode(hasText(compose.activity.getString(label)) and hasClickAction()).performClick()
    private fun bind(): LocalResource {
        ready()
        val before = model.state.value.library
        val config = before.resources.single { it.kind == ResourceKind.CONFIG }
        compose.runOnIdle { model.associate(before.selectedId!!, config.id, emptyList()) }
        finish()
        return config
    }
    private fun selector() = model.state.value.library.resources.single {
        it.kind == ResourceKind.GROUPS && JSONObject(it.content).optString("kind") == "selector"
    }
    private fun rules(mode: String): LocalResource {
        val group = model.state.value.library.resources.single { it.kind == ResourceKind.GROUPS &&
            if (mode == "proxy") JSONObject(it.content).optString("kind") == "selector"
            else JSONObject(it.content).getJSONObject("policy").optString("mode") == mode }
        val id = JSONObject(group.content).getJSONArray("ruleSetReferences").getJSONObject(0).getString("ruleSetId")
        return model.state.value.library.resources.single { it.id == id }
    }

    @Test fun freshStartupAndOldSchemasAddEditablePresetOnceWithoutChangingUserData() {
        ready()
        val seeded = model.state.value.library
        assertEquals(1, seeded.bundledRoutingVersion)
        assertEquals(7, seeded.resources.size)
        assertNull(seeded.selected!!.handlerId)
        assertEquals(routingSource, seeded.selected!!.original)
        assertEquals(seeded.resources, app.repository.load().resources)
        assertEquals(3, seeded.resources.count { it.kind == ResourceKind.RULES })
        assertTrue(seeded.resources.filter { it.kind == ResourceKind.RULES }.all { JSONObject(it.content).getJSONArray("payload").length() == 0 })
        assertTrue(seeded.resources.filter { it.kind == ResourceKind.RULES }.all { JSONObject(it.content).getString("payloadYaml") == "payload:\n" })
        assertTrue(seeded.resources.filter { it.kind == ResourceKind.RULES }.all { JSONObject(it.content).isAppListEnabled() })
        assertEquals(seeded, withBundledAppRouting(seeded, app, app.engine))
        val deleted = seeded.copy(resources = emptyList())
        assertEquals(deleted, withBundledAppRouting(deleted, app, app.engine))

        val directory = File(app.cacheDir, "preset-migration-${UUID.randomUUID()}").apply { mkdirs() }
        val previousLocale = AppCompatDelegate.getApplicationLocales()
        try {
            compose.runOnUiThread { AppCompatDelegate.setApplicationLocales(LocaleListCompat.forLanguageTags("zh-CN")) }
            val original = seeded.copy(resources = listOf(app.engine.prepareTyped(LocalResource("e".repeat(32), "应用分流规则", ResourceKind.SCRIPT,
                "function main(config) { return config; }", formatVersion = 2))), bundledRoutingVersion = 0)
            val oldCandidate = app.engine.project(original.selected!!, original.preferences, original.resources)
            val repository = LibraryRepository(directory)
            for (schema in 1..5) {
                repository.save(original)
                val file = File(directory, "library-v1.json")
                file.writeText(JSONObject(file.readText()).put("schemaVersion", schema).apply { remove("bundledRoutingVersion") }.toString())
                val oldText = file.readText()
                val loaded = repository.load()
                assertEquals(oldText, file.readText())
                val upgraded = withBundledAppRouting(loaded, app, app.engine)
                assertEquals(original.subscriptions, upgraded.subscriptions)
                assertEquals(original.preferences, upgraded.preferences)
                assertEquals(original.resources.single(), upgraded.resources.first())
                assertEquals("应用分流规则（2）", upgraded.resources.single { it.kind == ResourceKind.CONFIG }.name)
                assertEquals("代理选择器", upgraded.resources.single { it.kind == ResourceKind.GROUPS && JSONObject(it.content).getString("kind") == "selector" }.name)
                assertEquals(oldCandidate.yaml, app.engine.project(upgraded.selected!!, upgraded.preferences, upgraded.resources).yaml)
                app.engine.validateResources(upgraded.resources)
                repository.save(upgraded)
                assertEquals(upgraded, repository.load())
                assertEquals(upgraded, withBundledAppRouting(repository.load(), app, app.engine))
            }
            val full = original.copy(resources = List(94) { original.resources.single().copy(id = "%032x".format(it), name = "Existing $it") })
            assertEquals(full, withBundledAppRouting(full, app, app.engine))
            compose.runOnUiThread { AppCompatDelegate.setApplicationLocales(LocaleListCompat.forLanguageTags("en")) }
            val english = withBundledAppRouting(Library(), app, app.engine)
            assertEquals("App routing rules", english.resources.single { it.kind == ResourceKind.CONFIG }.name)
            assertEquals(setOf("Direct", "Proxy", "Block"), english.resources.filter { it.kind == ResourceKind.RULES }.map { it.name }.toSet())
            assertEquals(seeded, withBundledAppRouting(seeded, app, app.engine))
        } finally {
            compose.runOnUiThread { AppCompatDelegate.setApplicationLocales(previousLocale) }
            check(directory.canonicalFile.parentFile == app.cacheDir.canonicalFile)
            directory.deleteRecursively()
        }
    }

    @Test fun bindingEmptyPresetAndAddingThreeAppRulesUsesCorrectTargetsAndKeepsLanFirst() {
        bind()
        val groupName = "🚀 ${selector().name}"
        val empty = requireNotNull(model.state.value.candidate)
        assertEquals(listOf(groupName), empty.structure.groups.map { it.name })
        assertEquals(listOf("Fixture.Node"), empty.structure.groups.single().members)
        assertTrue(empty.providers.isEmpty())
        assertFalse(empty.yaml.contains("RULE-SET,old"))
        assertEquals(groupName, empty.fallbackOriginal)
        assertFalse(empty.yaml.contains("PROCESS-NAME"))
        assertEquals(RuntimeState.Stopped, model.runtime.value)

        val order = listOf("reject" to "REJECT", "direct" to "DIRECT", "proxy" to groupName)
        for ((mode, target) in order) {
            val before = model.state.value.library
            val rule = rules(mode)
            val input = JSONObject().put("ruleSetId", rule.id).put("matchType", "processName").put("value", "org.example.$mode").toString()
            val (draft, preview) = runBlocking { model.prepareConnectionRule(input, "f".repeat(32)) }
            assertTrue(preview.contains("PROCESS-NAME,org.example.$mode"))
            assertEquals(before.resources, model.state.value.library.resources)
            assertEquals(rule.id, draft.id)
            compose.runOnIdle { model.saveConnectionRule(input, "f".repeat(32), before.resources) {} }
            finish()
            // yaml.v3 may quote the non-BMP rocket as a Unicode escape.
            assertTrue(requireNotNull(model.state.value.candidate).yaml.replace("\\U0001F680", "🚀")
                .contains("PROCESS-NAME,org.example.$mode,$target"))
            assertEquals(listOf(before.selectedId), model.state.value.resourceGraph.affected("resource:${rule.id}"))
        }
        val candidate = requireNotNull(model.state.value.candidate)
        val yaml = candidate.yaml.replace("\\U0001F680", "🚀")
        assertTrue(yaml.indexOf("PROCESS-NAME,org.example.reject") < yaml.indexOf("PROCESS-NAME,org.example.direct"))
        assertTrue(yaml.indexOf("PROCESS-NAME,org.example.direct") < yaml.indexOf("PROCESS-NAME,org.example.proxy"))
        assertTrue(yaml.indexOf("PROCESS-NAME,org.example.proxy") < yaml.indexOf("MATCH,$groupName"))
        val lan = yaml.indexOf("IP-CIDR,10.0.0.0/8,DIRECT")
        assertTrue(lan >= 0 && lan < yaml.indexOf("PROCESS-NAME"))
        assertEquals(routingSource, model.state.value.library.selected!!.original)
        assertEquals(model.state.value.library.resources, app.repository.load().resources)
        assertEquals(RuntimeState.Stopped, model.runtime.value)
    }

    @Test fun providerOnlySubscriptionAndResourcePackageKeepAllReferencesAndPassRealCoreValidation() {
        ready()
        val library = model.state.value.library
        val config = library.resources.single { it.kind == ResourceKind.CONFIG }
        val source = "proxy-providers:\n  Nodes:\n    type: inline\n    payload:\n      - {name: Provider.Node, type: socks5, server: node.example.invalid, port: 1080}\n" +
            "proxy-groups: [{name: Source, type: select, use: [Nodes]}]\nrules: ['MATCH,Source']\n"
        val subscription = app.engine.normalize("Provider only", source).copy(handlerId = config.id)
        val candidate = app.engine.project(subscription, library.preferences, library.resources)
        assertEquals(listOf("Nodes"), candidate.structure.groups.single().providers)
        assertTrue(candidate.structure.groups.single().members.isEmpty()) // Runtime provider members remain separate.
        assertTrue(candidate.yaml.contains("Provider.Node"))
        ChainCandidateValidator(app).use { it.validate(candidate.yaml) }
        val exported = Mobile.exportResourcePackage(config.toJson().toString(), library.resources.toJson().toString())
        val target = config.copy(id = "f".repeat(32), name = "Imported")
        val imported = JSONObject(Mobile.prepareResourceImport(exported, target.toJson().toString(), "[]")).getJSONArray("resources")
        val resources = List(imported.length()) { resourceFromJson(imported.getJSONObject(it)) }
        assertEquals(7, resources.size)
        app.engine.validateResources(resources)
        val group = resources.single { it.kind == ResourceKind.GROUPS && JSONObject(it.content).optString("kind") == "selector" }
        assertTrue(JSONObject(group.content).getBoolean("includeAllProviders"))
        assertTrue(resources.filter { it.kind == ResourceKind.RULES }.all { JSONObject(it.content).getJSONArray("payload").length() == 0 })
        assertTrue(resources.filter { it.kind == ResourceKind.RULES }.all { JSONObject(it.content).isAppListEnabled() })
        val importedCandidate = app.engine.project(subscription.copy(handlerId = resources.single { it.kind == ResourceKind.CONFIG }.id), library.preferences, resources)
        ChainCandidateValidator(app).use { it.validate(importedCandidate.yaml) }
    }

    @Test fun connectionDetailsCanAppendToAnAlreadyBoundEmptySetAndPresetRemainsEditable() {
        bind()
        val direct = rules("direct")
        compose.onNodeWithTag("navigation-CONFIG").performClick()
        compose.onNodeWithText(model.state.value.library.resources.single { it.kind == ResourceKind.CONFIG }.name).assertExists()
        capture("library")
        compose.runOnIdle { model.openResource(selector()) }
        compose.onNode(hasText(compose.activity.getString(R.string.node_filtering)) and hasClickAction()).performScrollTo().performClick()
        compose.onNodeWithText(compose.activity.getString(R.string.include_subscription_providers)).performScrollTo().assertExists()
        compose.onNodeWithText(compose.activity.getString(R.string.filter_names)).assertIsNotEnabled()
        capture("selector")
        compose.runOnIdle { model.closeResource() }
        compose.runOnIdle { model.openResource(direct) }
        compose.onNode(hasText(compose.activity.getString(R.string.app_rule_yaml)) and hasClickAction()).performScrollTo().performClick()
        compose.onNode(hasText(compose.activity.getString(R.string.rule_payload)) and hasSetTextAction())
            .performScrollTo().performTextReplacement("payload:\n")
        compose.onNode(hasText(compose.activity.getString(R.string.save)) and hasClickAction()).performClick()
        finish()
        assertEquals("payload:\n", JSONObject(model.state.value.library.resources.single { it.id == direct.id }.content).getString("payloadYaml"))
        compose.runOnIdle {
            model.diagnostics.connections.value = ConnectionFeed(rows = listOf(ConnectionRecord("preset", "example.com", "203.0.113.10", "443", "172.19.0.1", "44444",
                "tcp", "TUN", "org.example.reader", 12345, "Match", "", listOf("Fixture.Node"), 123, 456, "2026-10-10T00:00:00Z")))
        }
        click(R.string.more_navigation); click(R.string.connections)
        compose.onNodeWithTag("connection-details-preset").performClick()
        compose.onNodeWithContentDescription(compose.activity.getString(R.string.add_connection_rule_for, compose.activity.getString(R.string.application_package))).performClick()
        compose.onNode(hasText(direct.name + " · " + compose.activity.getString(R.string.app_rule_list)) and hasClickAction()).assertExists()
        compose.waitUntil(10_000) { compose.onAllNodesWithText("- 'PROCESS-NAME,org.example.reader'").fetchSemanticsNodes().isNotEmpty() }
        capture("connection-rule")
        compose.onNode(hasText(compose.activity.getString(R.string.save)) and hasClickAction())
            .assertIsEnabled().performSemanticsAction(SemanticsActions.OnClick) { it() }
        compose.waitUntil(60_000) { model.state.value.library.resources.any { it.id == direct.id && it.content.contains("PROCESS-NAME,org.example.reader") } || model.state.value.error != null }
        assertNull(model.state.value.error)
        assertEquals(7, model.state.value.library.resources.size)
        assertTrue(requireNotNull(model.state.value.candidate).yaml.contains("PROCESS-NAME,org.example.reader,DIRECT"))
        val saved = model.state.value.library.resources.single { it.id == direct.id }
        assertEquals("payload:\n  - 'PROCESS-NAME,org.example.reader'\n", JSONObject(saved.content).getString("payloadYaml"))
        compose.onNodeWithTag("navigation-CONFIG").performClick()
        compose.runOnIdle { model.openResource(saved) }
        compose.onNode(hasText(compose.activity.getString(R.string.app_rule_yaml)) and hasClickAction()).performScrollTo().performClick()
        compose.onNode(hasText(compose.activity.getString(R.string.rule_payload)) and hasSetTextAction())
            .performScrollTo().assertTextContains(JSONObject(saved.content).getString("payloadYaml"))
        capture("rule-yaml")
        compose.runOnIdle { model.closeResource() }
    }

    private fun capture(name: String) {
        compose.waitForIdle()
        val automation = InstrumentationRegistry.getInstrumentation().uiAutomation
        Thread.sleep(300)
        val prefix = InstrumentationRegistry.getArguments().getString("visualPrefix", "phone")
        require(prefix.matches(Regex("[A-Za-z0-9_-]+")))
        val file = File(app.getExternalFilesDir(null), "routing-$prefix-$name.png")
        val bitmap = automation.takeScreenshot()
        file.outputStream().use { bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it) }
        bitmap.recycle()
        automation.executeShellCommand("cp ${file.absolutePath} /data/local/tmp/jeemi-${file.name}").use {
            FileInputStream(it.fileDescriptor).use { input -> input.readBytes() }
        }
    }
}
