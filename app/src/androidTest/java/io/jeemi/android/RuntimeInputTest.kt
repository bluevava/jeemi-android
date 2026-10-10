package io.jeemi.android

import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.lifecycle.ViewModelProvider
import androidx.appcompat.app.AppCompatDelegate
import androidx.core.os.LocaleListCompat
import androidx.test.platform.app.InstrumentationRegistry
import io.jeemi.android.domain.Library
import io.jeemi.android.ui.JeemiViewModel
import io.jeemi.android.ui.RuntimeResolverPresets
import io.jeemi.android.ui.runtimeStringSequence
import mobile.Mobile
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import java.io.File

class RuntimeInputTest {
    @get:Rule(order = 0) val library = TestLibrary { app ->
        val profile = app.engine.normalize("Runtime input fixture", "rules: ['MATCH,DIRECT']")
        Library(listOf(profile), profile.id)
    }
    @get:Rule(order = 1) val compose = createAndroidComposeRule<MainActivity>()
    private val model get() = ViewModelProvider(compose.activity)[JeemiViewModel::class.java]
    private val app get() = compose.activity.application as JeemiApplication
    private fun label(id: Int) = compose.activity.getString(id)
    private fun ready() {
        compose.waitUntil(15000) { model.state.value.loaded }
        compose.onNodeWithText(label(R.string.runtime_preferences)).performScrollTo().performClick()
    }
    private fun open(id: Int) = compose.onNodeWithText(label(id)).performScrollTo().performClick()
    private fun field(key: String) = compose.onNodeWithTag("runtime-fields").performScrollToNode(hasTestTag("runtime-" + key))
    private fun editor(key: String) = compose.onNodeWithTag("runtime-value-" + key)
    private fun displayed(key: String) = editor(key).fetchSemanticsNode().config[SemanticsProperties.EditableText].text
    private fun values(result: JSONObject) = result.getJSONArray("values").strings()
    private fun JSONArray.strings() = (0 until length()).map { getString(it) }
    private fun addPreset(key: String, preset: String, valid: Boolean = true) {
        compose.onNode(hasText(label(R.string.runtime_add_preset)) and
            hasAnyAncestor(hasTestTag("runtime-" + key))).performScrollTo().performClick()
        compose.onNode(hasText(preset) and hasAnyAncestor(isPopup())).performClick()
        compose.waitForIdle()
        compose.waitUntil(15000) { !model.state.value.busy }
        if (valid) assertNull("A valid preset must not be rejected", model.runtimeIssue.value)
        else assertNotNull(model.runtimeIssue.value)
        assertNull(model.state.value.error)
    }
    private fun save(valid: Boolean = true) {
        val before = model.state.value.saveRevision
        compose.onNodeWithTag("runtime-save").performClick()
        compose.waitUntil(20000) { model.state.value.saveRevision > before || model.runtimeIssue.value != null || model.state.value.error != null }
        assertNull(model.state.value.error)
        if (valid) assertNull(model.runtimeIssue.value) else assertNotNull(model.runtimeIssue.value)
    }
    private fun capture(name: String) {
        compose.mainClock.advanceTimeBy(300)
        compose.waitForIdle()
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        instrumentation.waitForIdleSync()
        Thread.sleep(350)
        val prefix = InstrumentationRegistry.getArguments().getString("visualPrefix", "phone")
        require(prefix.matches(Regex("[A-Za-z0-9_-]+")))
        val file = File(app.getExternalFilesDir(null), "runtime-input-$prefix-$name.png")
        instrumentation.uiAutomation.takeScreenshot().also { bitmap ->
            file.outputStream().use { bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it) }
            bitmap.recycle()
        }
        instrumentation.uiAutomation.executeShellCommand("cp " + file.absolutePath + " /data/local/tmp/jeemi-" + file.name).use {
            java.io.FileInputStream(it.fileDescriptor).use { output -> output.readBytes() }
        }
    }

    @Test fun dohPresetCanBeFirstNodeResolverAndSaveWithoutYamlError() {
        ready()
        for ((key, fragment) in listOf("dnsProxyServerNameservers" to "dns.proxy-server-nameserver", "dnsNameservers" to "dns.nameserver")) {
            open(R.string.dns_resources); field(key)
            if (key == "dnsProxyServerNameservers") compose.onNodeWithTag("runtime-switch-dnsProxyServerNameserverEnabled").performClick()
            for (preset in RuntimeResolverPresets) {
                editor(key).performScrollTo().performTextReplacement("")
                addPreset(key, preset)
                assertEquals(listOf(preset), values(JSONObject(Mobile.validateRuntimeFragment(fragment, model.runtimeTextDraft.value.getValue(fragment)))))
            }
            RuntimeResolverPresets.forEach { addPreset(key, it) }
            val expected = (listOf(RuntimeResolverPresets.last()) + RuntimeResolverPresets).distinct()
            addPreset(key, RuntimeResolverPresets.last())
            save()
            assertEquals(expected, JSONObject(app.repository.load().preferences.runtimeJson).getJSONArray(key).strings())
            open(R.string.dns_resources); field(key)
            val text = displayed(key)
            assertEquals(expected, values(JSONObject(Mobile.validateRuntimeFragment(fragment, text))))
            editor(key).performTextReplacement(text)
            save()
        }
    }

    @Test fun allDisplayedListsRoundTripThroughTheSharedYamlReader() {
        val defaults = JSONObject(Mobile.runtimeDefaults())
        val samples = listOf(
            "dns.nameserver" to RuntimeResolverPresets,
            "dns.proxy-server-nameserver" to listOf("https://[2001:db8::1]:443/dns-query#h3=true", "tls://1.1.1.1", "quic://dns.example.invalid", "https://resolver.example.invalid/query?key=a&name=b"),
            "dns.fake-ip-filter" to listOf("*.example.invalid", "+.lan", "true", "123", "2026-10-10", "a: b", "a # b", "a\"b", "a\\b/c", "中文.example.invalid", "😀", "a\u0085b", "a\u2028b", "a\u2029b"),
            "rules.lan-bypass" to defaults.getJSONArray("lanBypassRules").strings(),
            "tun.route-exclude-address" to listOf("192.168.0.0/16", "fc00::/7")
        )
        for ((field, entries) in samples) {
            val result = JSONObject(Mobile.validateRuntimeFragment(field, runtimeStringSequence(entries)))
            assertTrue(field, result.getBoolean("valid"))
            assertEquals(field, entries, values(result))
            val again = JSONObject(Mobile.validateRuntimeFragment(field, result.getString("normalizedYaml")))
            assertEquals(field, entries, values(again))
        }
        for (field in listOf("dns.fake-ip-filter", "dns.proxy-server-nameserver", "rules.lan-bypass", "tun.route-exclude-address")) {
            assertTrue(JSONObject(Mobile.validateRuntimeFragment(field, runtimeStringSequence(emptyList()))).getBoolean("valid"))
        }
    }

    @Test fun invalidAndOverLimitPresetsPreserveTheDraftAndSavedLibrary() {
        ready(); open(R.string.dns_resources); field("dnsProxyServerNameservers")
        compose.onNodeWithTag("runtime-switch-dnsProxyServerNameserverEnabled").performClick()
        val saved = app.repository.load()
        val field = "dns.proxy-server-nameserver"
        val key = "dnsProxyServerNameservers"
        val invalid = "secret.invalid: ["
        editor(key).performTextReplacement(invalid)
        addPreset(key, RuntimeResolverPresets.first(), valid = false)
        assertEquals(invalid, model.runtimeTextDraft.value[field])
        assertEquals(saved, app.repository.load())
        compose.onNodeWithText(label(R.string.runtime_continue_editing)).performClick()
        val full = runtimeStringSequence((1..128).map { "tls://resolver-$it.example.invalid" })
        editor(key).performTextReplacement(full)
        addPreset(key, RuntimeResolverPresets.first(), valid = false)
        assertEquals(full, model.runtimeTextDraft.value[field])
        assertEquals(saved, app.repository.load())
        compose.onNodeWithText(label(R.string.runtime_continue_editing)).performClick()
        editor(key).performTextReplacement(runtimeStringSequence(List(128) { RuntimeResolverPresets.first() }))
        addPreset(key, RuntimeResolverPresets.first())
        assertEquals(listOf(RuntimeResolverPresets.first()), values(JSONObject(Mobile.validateRuntimeFragment(field, model.runtimeTextDraft.value.getValue(field)))))
    }

    @Test fun lanRestoreAndRouteExclusionsSaveAndReopenWithoutEscapedSlashes() {
        ready(); open(R.string.lan_rules)
        compose.onNodeWithText(label(R.string.restore_defaults)).performScrollTo().performClick()
        val rules = JSONObject(Mobile.runtimeDefaults()).getJSONArray("lanBypassRules").strings()
        assertEquals(rules, values(JSONObject(Mobile.validateRuntimeFragment("rules.lan-bypass", model.runtimeTextDraft.value.getValue("rules.lan-bypass")))))
        save()
        open(R.string.lan_rules)
        editor("lanBypassRules").performTextReplacement(displayed("lanBypassRules"))
        save()
        open(R.string.runtime_basics); field("tunRouteExcludeAddress")
        compose.onNodeWithTag("runtime-switch-tunRouteExcludeAddressEnabled").performClick()
        editor("tunRouteExcludeAddress").performTextReplacement("- 198.51.100.0/24\n- fc00::/7")
        save()
        open(R.string.runtime_basics); field("tunRouteExcludeAddress")
        val text = displayed("tunRouteExcludeAddress")
        assertEquals(listOf("198.51.100.0/24", "fc00::/7"), values(JSONObject(Mobile.validateRuntimeFragment("tun.route-exclude-address", text))))
        editor("tunRouteExcludeAddress").performTextReplacement(text)
        save()
    }

    @Test fun mappingsHelpAndDisabledInvalidDraftKeepTheSavedLibrary() {
        val previous = AppCompatDelegate.getApplicationLocales()
        try {
            compose.runOnUiThread { AppCompatDelegate.setApplicationLocales(LocaleListCompat.forLanguageTags("zh-CN")) }
            compose.waitUntil(15000) { compose.activity.resources.configuration.locales[0].language == "zh" }
            compose.waitForIdle()
            ready(); open(R.string.dns_resources)
            field("dnsProxyServerNameservers")
            compose.onNodeWithTag("runtime-switch-dnsProxyServerNameserverEnabled").performClick()
            addPreset("dnsProxyServerNameservers", "https://dns.alidns.com/dns-query")
            editor("dnsProxyServerNameservers").performScrollTo()
            capture("resolver-zh")
            compose.onNodeWithContentDescription(compose.activity.getString(R.string.help_for, "dns.proxy-server-nameserver")).performClick()
            compose.onNodeWithText(label(R.string.help_cautions)).assertExists()
            compose.onNodeWithText(label(R.string.runtime_proxy_resolver_help)).performScrollTo()
            capture("help-zh")
            compose.onNodeWithText(label(R.string.close)).performClick()
            compose.runOnUiThread { AppCompatDelegate.setApplicationLocales(LocaleListCompat.forLanguageTags("en")) }
            compose.waitUntil(15000) { compose.activity.resources.configuration.locales[0].language == "en" }
            compose.waitForIdle()
            capture("resolver-en")
            compose.onNodeWithContentDescription(compose.activity.getString(R.string.help_for, "dns.proxy-server-nameserver")).performClick()
            compose.onNodeWithText(label(R.string.runtime_proxy_resolver_help)).performScrollTo()
            capture("help-en")
            compose.onNodeWithText(label(R.string.close)).performClick()
            val mappings = listOf(
                Triple("dnsNameserverPolicyYaml", "dnsNameserverPolicyEnabled", "\"+.example.invalid\": [223.5.5.5, https://doh.pub/dns-query]"),
                Triple("dnsProxyServerNameserverPolicyYaml", "dnsProxyServerNameserverPolicyEnabled", "\"node.example.invalid\":\n  - https://dns.alidns.com/dns-query"),
                Triple("hostsYaml", "dnsUseHosts", "example.invalid: 192.0.2.1\nipv6.example.invalid: [192.0.2.2, \"2001:db8::2\"]")
            )
            for ((key, enabled, contents) in mappings) {
                field(key)
                compose.onNodeWithTag("runtime-switch-" + enabled).performClick()
                editor(key).performTextReplacement(contents)
            }
            save()
            val saved = app.repository.load()
            val prefs = JSONObject(saved.preferences.runtimeJson)
            open(R.string.dns_resources)
            for ((key, _, _) in mappings) {
                field(key)
                assertEquals(prefs.getString(key), displayed(key))
            }
            val invalid = "example.invalid: {nested: secret}"
            editor("hostsYaml").performTextReplacement(invalid)
            compose.onNodeWithTag("runtime-switch-dnsUseHosts").performClick()
            save(valid = false)
            assertEquals("hosts", model.runtimeIssue.value!!.field)
            assertEquals(saved, app.repository.load())
            assertEquals(invalid, model.runtimeTextDraft.value["hosts"])
            compose.onNodeWithText(label(R.string.runtime_revert_field)).performClick()
            save()
            assertEquals(prefs.getString("hostsYaml"), JSONObject(app.repository.load().preferences.runtimeJson).getString("hostsYaml"))
            assertFalse(JSONObject(app.repository.load().preferences.runtimeJson).getBoolean("dnsUseHosts"))
        } finally { compose.runOnUiThread { AppCompatDelegate.setApplicationLocales(previous) } }
    }
}
