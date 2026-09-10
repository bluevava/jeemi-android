package io.jeemi.android

import android.graphics.Bitmap
import androidx.appcompat.app.AppCompatDelegate
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.core.os.LocaleListCompat
import androidx.lifecycle.ViewModelProvider
import androidx.test.platform.app.InstrumentationRegistry
import io.jeemi.android.domain.Library
import io.jeemi.android.ui.JeemiViewModel
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import java.io.File

class RuntimePreferencesTest {
    @get:Rule(order = 0) val library = TestLibrary { app ->
        val source = "dns: {nameserver: [1.1.1.1, 223.5.5.5], nameserver-policy: {kept.invalid: 1.1.1.1}}\nrules: ['MATCH,DIRECT']"
        val profile = app.engine.normalize("Runtime fixture", source)
        Library(listOf(profile), profile.id)
    }
    @get:Rule(order = 1) val compose = createAndroidComposeRule<MainActivity>()
    private val app get() = compose.activity.application as JeemiApplication
    private val model get() = ViewModelProvider(compose.activity)[JeemiViewModel::class.java]
    private fun label(id: Int) = compose.activity.getString(id)
    private fun openResources() {
        compose.waitUntil(15000) { model.state.value.loaded }
        compose.onNodeWithText(label(R.string.runtime_preferences)).performScrollTo().performClick()
        compose.onNodeWithText(label(R.string.dns_resources)).performScrollTo().performClick()
    }
    private fun field(key: String) {
        compose.onNodeWithTag("runtime-fields").performScrollToNode(hasTestTag("runtime-" + key))
    }
    private fun save() {
        val before = model.state.value.saveRevision
        compose.onNodeWithTag("runtime-save").performClick()
        compose.waitUntil(15000) { model.state.value.saveRevision > before || model.runtimeIssue.value != null || model.state.value.error != null }
    }
    private fun capture(name: String) {
        compose.waitForIdle()
        InstrumentationRegistry.getInstrumentation().waitForIdleSync()
        Thread.sleep(250) // Wait for the native window to present the composed frame.
        val directory = File(app.getExternalFilesDir(null), "runtime-preferences").apply { mkdirs() }
        val prefix = InstrumentationRegistry.getArguments().getString("visualPrefix", "phone")
        check(prefix.matches(Regex("[A-Za-z0-9_-]+")))
        InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot().also { bitmap ->
            File(directory, prefix + "-" + name + ".png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
            bitmap.recycle()
        }
    }
    @Test fun yamlMergeSwitchHelpValidationAndSaveUseTheRealEngine() {
        openResources()
        compose.onNodeWithTag("runtime-dnsNameserverMerge-append").assertIsSelected()
        val draft = "- 223.5.5.5\n- 114.114.114.114\n- 114.114.114.114"
        compose.onNodeWithTag("runtime-value-dnsNameservers").performTextReplacement(draft)
        compose.onNodeWithContentDescription(compose.activity.getString(R.string.help_for, "dns.nameserver")).performClick()
        compose.onNodeWithText(label(R.string.help_cautions)).assertExists()
        compose.onNodeWithText(label(R.string.close)).performClick()
        compose.onNodeWithTag("runtime-value-dnsNameservers").assertTextContains(draft)
        save()
        assertNull(model.state.value.error)
        val prefs = JSONObject(model.state.value.library.preferences.runtimeJson)
        assertEquals(2, prefs.getJSONArray("dnsNameservers").length())
        val candidate = model.state.value.candidate!!.yaml
        assertTrue(candidate.contains("1.1.1.1")); assertTrue(candidate.contains("114.114.114.114"))
        assertEquals("append", prefs.getString("dnsNameserverMerge"))
        assertEquals(draft.lines().distinct().map { it.removePrefix("- ") },
            prefs.getJSONArray("dnsNameservers").let { array -> (0 until array.length()).map { array.getString(it) } })
        val saved = model.state.value.library
        // Re-open the section without toggling its already expanded Home panel.
        compose.onNodeWithText(label(R.string.dns_resources)).performScrollTo().performClick()
        compose.onNodeWithTag("runtime-dnsNameserverMerge-override").performClick().assertIsSelected()
        compose.onNodeWithTag("runtime-value-dnsNameservers").performTextReplacement("- [broken")
        save()
        compose.onNodeWithText(label(R.string.runtime_yaml_error)).assertExists()
        assertEquals(saved, app.repository.load())
        compose.onNodeWithText(label(R.string.runtime_continue_editing)).performClick()
        compose.onNodeWithTag("runtime-value-dnsNameservers").assertTextContains("- [broken")
        compose.onNodeWithTag("runtime-value-dnsNameservers").performTextReplacement("- 9.9.9.9")
        save()
        assertNull(model.state.value.error)
        val stored = JSONObject(app.repository.load().preferences.runtimeJson)
        assertEquals("override", stored.getString("dnsNameserverMerge"))
        assertEquals("9.9.9.9", stored.getJSONArray("dnsNameservers").getString(0))
        assertFalse(model.state.value.candidate!!.yaml.contains("223.5.5.5"))
    }
    @Test fun optionalControlsKeepDraftsAndRenderInBothLanguages() {
        val previous = AppCompatDelegate.getApplicationLocales()
        try {
            compose.runOnUiThread { AppCompatDelegate.setApplicationLocales(LocaleListCompat.forLanguageTags("zh-CN")) }
            openResources()
            capture("resources-zh")
            field("dnsNameserverPolicyYaml")
            compose.onNodeWithTag("runtime-switch-dnsNameserverPolicyEnabled").assertIsOff().performClick()
            compose.onNodeWithTag("runtime-dnsNameserverPolicyMerge-append").assertIsSelected()
            val draft = "'*.example.invalid':\n  - 223.5.5.5"
            compose.onNodeWithTag("runtime-value-dnsNameserverPolicyYaml").performTextReplacement(draft)
            compose.onNodeWithTag("runtime-dnsNameserverPolicyMerge-override").performClick()
            compose.onNodeWithTag("runtime-switch-dnsNameserverPolicyEnabled").performClick()
            compose.onNodeWithTag("runtime-value-dnsNameserverPolicyYaml").assertDoesNotExist()
            compose.onNodeWithTag("runtime-switch-dnsNameserverPolicyEnabled").performClick()
            compose.onNodeWithTag("runtime-value-dnsNameserverPolicyYaml").assertTextContains(draft)
            capture("policy-zh")
            compose.runOnUiThread { AppCompatDelegate.setApplicationLocales(LocaleListCompat.forLanguageTags("en")) }
            compose.waitUntil(10000) { compose.activity.resources.configuration.locales[0].language == "en" }
            compose.waitUntil(10000) { compose.onAllNodesWithText("Override").fetchSemanticsNodes().isNotEmpty() }
            compose.onNodeWithTag("runtime-value-dnsNameserverPolicyYaml").assertTextContains(draft)
            capture("policy-en")
            compose.onNodeWithTag("runtime-fields").performScrollToIndex(0)
            capture("resources-en")
            field("dnsNameserverPolicyYaml")
            compose.onNodeWithTag("runtime-switch-dnsNameserverPolicyEnabled").performClick()
            save()
            val stored = JSONObject(app.repository.load().preferences.runtimeJson)
            assertFalse(stored.getBoolean("dnsNameserverPolicyEnabled"))
            assertEquals("override", stored.getString("dnsNameserverPolicyMerge"))
            assertTrue(stored.getString("dnsNameserverPolicyYaml").contains("example.invalid"))
            assertTrue(model.state.value.candidate!!.yaml.contains("kept.invalid"))
            assertFalse(model.state.value.candidate!!.yaml.contains("*.example.invalid"))
        } finally { compose.runOnUiThread { AppCompatDelegate.setApplicationLocales(previous) } }
    }
    @Test fun forcedFalseAndRouteDraftSaveWhileCancelPreservesTheLibrary() {
        compose.waitUntil(15000) { model.state.value.loaded }
        compose.onNodeWithText(label(R.string.runtime_preferences)).performScrollTo().performClick()
        compose.onNodeWithText(label(R.string.runtime_basics)).performScrollTo().performClick()
        field("ipv6")
        compose.onNodeWithTag("runtime-switch-ipv6").assertIsOn().performClick()
        field("tunRouteExcludeAddress")
        compose.onNodeWithTag("runtime-switch-tunRouteExcludeAddressEnabled").assertIsOff().performClick()
        compose.onNodeWithTag("runtime-tunRouteExcludeAddressMerge-append").assertIsSelected()
        compose.onNodeWithTag("runtime-tunRouteExcludeAddressMerge-override").performClick()
        compose.onNodeWithTag("runtime-value-tunRouteExcludeAddress").performTextReplacement("- 192.168.0.0/16")
        compose.onNodeWithText(label(R.string.restore_defaults)).performScrollTo().performClick()
        assertEquals("", model.runtimeTextDraft.value["tun.route-exclude-address"])
        compose.onNodeWithTag("runtime-value-tunRouteExcludeAddress").performTextReplacement("- 198.51.100.0/24")
        capture("route-exclusion")
        save()
        assertNull(model.state.value.error)
        val saved = app.repository.load()
        val prefs = JSONObject(saved.preferences.runtimeJson)
        assertFalse(prefs.getBoolean("ipv6"))
        assertTrue(prefs.getBoolean("tunRouteExcludeAddressEnabled"))
        assertEquals("override", prefs.getString("tunRouteExcludeAddressMerge"))
        assertTrue(model.state.value.candidate!!.yaml.contains("198.51.100.0/24"))
        compose.onNodeWithText(label(R.string.runtime_basics)).performScrollTo().performClick()
        field("ipv6")
        compose.onNodeWithTag("runtime-switch-ipv6").performClick()
        compose.onNodeWithText(label(R.string.cancel)).performClick()
        assertEquals(saved, app.repository.load())
    }
}
