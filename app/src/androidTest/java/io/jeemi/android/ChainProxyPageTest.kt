package io.jeemi.android

import android.graphics.Bitmap
import androidx.appcompat.app.AppCompatDelegate
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.core.os.LocaleListCompat
import androidx.lifecycle.ViewModelProvider
import androidx.test.platform.app.InstrumentationRegistry
import io.jeemi.android.data.*
import io.jeemi.android.domain.*
import io.jeemi.android.ui.JeemiViewModel
import org.json.JSONObject
import org.junit.*
import org.junit.Assert.*
import java.io.File
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlin.concurrent.thread

class ChainProxyPageTest {
    @get:Rule(order = 0) val library = TestLibrary { app ->
        val first = app.engine.normalize("Chain fixture", CHAIN_SOURCE)
        val second = app.engine.normalize("Unselected fixture", CHAIN_SOURCE + "dns:\n  proxy-server-nameserver: [1.1.1.1]\n  proxy-server-nameserver-policy:\n    landing.example.invalid: [1.1.1.1]\n")
        Library(listOf(first, second), first.id)
    }
    @get:Rule(order = 1) val compose = createAndroidComposeRule<MainActivity>()
    private val app get() = compose.activity.application as JeemiApplication
    private val model get() = ViewModelProvider(compose.activity)[JeemiViewModel::class.java]
    private val initialLocale = AppCompatDelegate.getApplicationLocales()
    private var server: ChainSourceServer? = null
    private fun label(id: Int) = compose.activity.getString(id)
    private fun ready() { compose.waitUntil(15000) { model.state.value.loaded } }
    private fun navigate(id: Int) { compose.onNode(hasText(label(id)) and hasClickAction()).performClick() }
    private fun chainPage() {
        navigate(R.string.configuration)
        compose.onNode(hasText(label(R.string.chain_proxies)) and hasClickAction()).performScrollTo().performClick()
    }
    private fun language(tag: String) {
        compose.runOnIdle { AppCompatDelegate.setApplicationLocales(LocaleListCompat.forLanguageTags(tag)) }
        compose.waitForIdle()
    }
    private fun capture(name: String) {
        compose.waitForIdle()
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        instrumentation.waitForIdleSync(); Thread.sleep(250)
        val prefix = InstrumentationRegistry.getArguments().getString("visualPrefix", "portrait")
        require(prefix.matches(Regex("[A-Za-z0-9_-]+")))
        val directory = File(app.getExternalFilesDir(null), "chain-proxy").apply { mkdirs() }
        instrumentation.uiAutomation.takeScreenshot().also { bitmap ->
            File(directory, "$prefix-$name.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
            bitmap.recycle()
        }
    }
    @After fun cleanup() {
        server?.close()
        compose.runOnIdle { model.cancelChainNetwork(); AppCompatDelegate.setApplicationLocales(initialLocale) }
    }

    @Test fun manualImportHelpLanguageAssociationAndOfflineChoiceUseMobileUi() {
        ready(); language("zh-CN"); chainPage()
        compose.onNodeWithTag("chain-add-group").performClick()
        navigate(R.string.chain_manual)
        compose.onNodeWithTag("chain-name").performTextInput("Manual landing")
        compose.onNode(hasText(label(R.string.chain_selector_filter)) and hasSetTextAction()).performTextInput("ma | other & in ! skip")
        compose.onNode(hasText(label(R.string.chain_node_filter)) and hasSetTextAction()).performTextInput("hk | jp & gm & !ev")
        compose.onNodeWithContentDescription(compose.activity.getString(R.string.help_for, label(R.string.chain_filters))).performClick()
        compose.onNodeWithText(label(R.string.help_chain_filter_cautions)).assertExists()
        navigate(R.string.close)
        language("en")
        assertEquals("Manual landing", model.chainDraft.value!!.name)
        assertEquals("ma | other & in ! skip", model.chainDraft.value!!.selectorFilter)
        assertEquals("hk | jp & gm & !ev", model.chainDraft.value!!.nodeFilter)
        compose.onNodeWithTag("chain-save").assertIsDisplayed()
        capture("group-editor-en")
        compose.onNodeWithTag("chain-save").performClick()
        compose.waitUntil(10000) { model.state.value.chains.groups.size == 1 }
        compose.onNodeWithText("Manual landing").performClick()
        compose.onNodeWithContentDescription(label(R.string.chain_add_nodes)).performClick()
        compose.waitUntil(10000) { model.chainDraft.value?.mode == "nodes" }
        compose.onNodeWithTag("chain-contents").performTextInput("bad-protocol://fixture\n$CHAIN_LANDING")
        compose.onNodeWithTag("chain-save").performClick()
        compose.waitUntil(10000) { model.state.value.conversionReport != null }
        assertEquals(1, JSONObject(model.state.value.conversionReport!!).getInt("skippedNodes"))
        capture("conversion-en")
        compose.onNodeWithContentDescription(label(R.string.back)).performClick()
        capture("groups-en"); language("zh-CN"); capture("groups-zh")
        navigate(R.string.subscriptions)
        compose.onAllNodesWithContentDescription(label(R.string.subscription_shelf))[0].performClick()
        compose.onAllNodesWithContentDescription(label(R.string.more_actions))[0].performClick()
        navigate(R.string.chain_associate)
        compose.onNodeWithText("Manual landing").performClick()
        compose.onNodeWithTag("chain-associate-save").performClick()
        compose.waitUntil(10000) { model.state.value.library.selected!!.chainGroupIds.size == 1 && !model.state.value.busy }
        assertEquals(1, JSONObject(model.state.value.candidate!!.chains).getInt("generated"))
        // Collapse management and choose the generated node while the VPN is stopped.
        compose.onNodeWithText(label(R.string.collapse_subscription_panel)).performClick()
        compose.onNodeWithText("Main").performClick()
        val generated = model.state.value.candidate!!.nodes.first { it.name.contains("⇐") }.name
        compose.onNodeWithText(generated).assertIsEnabled()
            .performSemanticsAction(androidx.compose.ui.semantics.SemanticsActions.OnClick) { it() }
        // The model publishes state after its atomic write. Polling openRead()
        // during startWrite()/finishWrite() can discard the pending .new file.
        compose.waitUntil(10000) { !model.state.value.busy && model.state.value.library.selected!!.selections["Main"] == generated }
        assertEquals(generated, app.repository.load().selected!!.selections["Main"])
        assertEquals(RuntimeState.Stopped, app.runtime.state.value)
        assertEquals(CHAIN_SOURCE, app.repository.load().selected!!.original)
        capture("selectors-zh"); language("en"); capture("selectors-en")
        // The group cannot be deleted while any subscription references it.
        val snapshot = app.repository.load()
        val group = model.state.value.chains.groups.single()
        compose.runOnIdle { model.deleteChainItem(group.id, "group", "", model.state.value.chains.revision) {} }
        compose.waitUntil(10000) { model.state.value.businessIssue?.code == "chain_group_in_use" }
        assertEquals(snapshot, app.repository.load())
    }

    @Test fun urlRefreshIsAtomicPerSourceChecksUnselectedSubscriptionsAndCancelsOnLeave() {
        ready(); chainPage()
        val local = ChainSourceServer().also { server = it }
        compose.runOnIdle { model.openChainGroup(kind = "subscription"); model.chainDraft.value = model.chainDraft.value!!.copy(name = "Sources"); model.saveChainEditor() }
        compose.waitUntil(10000) { model.state.value.chains.groups.size == 1 }
        val groupId = model.state.value.chains.groups.single().id
        fun addSource(path: String) {
            compose.runOnIdle { model.openChainItem(model.state.value.chains.groups.single(), source = true) }
            compose.waitUntil(10000) { model.chainDraft.value?.mode == "source" }
            compose.runOnIdle { model.chainDraft.value = model.chainDraft.value!!.copy(url = local.url(path)); model.saveChainEditor() }
            compose.waitUntil(15000) { !model.state.value.chainBusy && model.chainDraft.value == null }
            compose.runOnIdle { model.showConversionReport(null) }
        }
        addSource("a"); addSource("b")
        for (profile in model.state.value.library.subscriptions) {
            compose.runOnIdle { model.associateChains(profile.id, listOf(groupId), profile.chainRevision, model.state.value.chains.revision) {} }
            compose.waitUntil(25000) { !model.state.value.busy && (model.state.value.error != null || model.state.value.library.subscriptions.first { it.id == profile.id }.chainGroupIds.isNotEmpty()) }
            assertEquals("association failed for ${profile.name}: ${model.state.value.businessIssue?.code}",
                listOf(groupId), model.state.value.library.subscriptions.first { it.id == profile.id }.chainGroupIds)
        }
        val old = JSONObject(app.repository.load().chainLibrary).getJSONArray("groups").getJSONObject(0).getJSONArray("sources")
        local.failure = "a"; local.bodyB = CHAIN_LANDING.replace("Landing", "Landing 2")
        compose.runOnIdle { model.refreshChains(groupId) }
        compose.waitUntil(15000) { !model.state.value.chainBusy }
        val after = JSONObject(app.repository.load().chainLibrary).getJSONArray("groups").getJSONObject(0).getJSONArray("sources")
        assertEquals(old.getJSONObject(0).toString(), after.getJSONObject(0).toString())
        assertEquals("Landing 2", after.getJSONObject(1).getJSONArray("nodes").getJSONObject(0).getString("name"))
        assertEquals(1, model.state.value.chainFailures.size)
        compose.onNodeWithText("Sources").performClick(); capture("sources-partial-refresh")
        // Only the unselected subscription conflicts with the incoming node DNS.
        val beforeConflict = app.repository.load()
        local.bodyB = "proxies: [{name: Landing 2, type: socks5, server: landing.example.invalid, port: 1080}]\ndns:\n  proxy-server-nameserver-policy:\n    landing.example.invalid: [8.8.8.8]\n"
        val secondId = model.state.value.chains.groups.single().sources[1].id
        compose.runOnIdle { model.refreshChains(groupId, secondId) }
        compose.waitUntil(15000) { !model.state.value.chainBusy }
        assertEquals(beforeConflict, app.repository.load())
        assertEquals("chain_dns_conflict", model.state.value.chainFailures.single().issue.code)
        val firstId = model.state.value.chains.groups.single().sources[0].id
        local.failure = ""; local.hold = true
        compose.runOnIdle { model.refreshChains(groupId, firstId) }
        assertTrue(local.entered.await(8, TimeUnit.SECONDS))
        navigate(R.string.home); compose.waitForIdle()
        compose.waitUntil(5000) { !model.state.value.chainBusy }
        assertTrue(local.disconnected.await(5, TimeUnit.SECONDS))
        assertEquals(beforeConflict, app.repository.load())
        val count = local.requests.get()
        chainPage(); Thread.sleep(350)
        assertEquals(count, local.requests.get())
        assertEquals(RuntimeState.Stopped, app.runtime.state.value)
    }
}

private class ChainSourceServer : AutoCloseable {
    private val server = ServerSocket(0, 8, InetAddress.getByName("127.0.0.1"))
    private val sockets = ConcurrentHashMap.newKeySet<Socket>()
    @Volatile var failure = ""
    @Volatile var bodyB = CHAIN_LANDING
    @Volatile var hold = false
    val entered = CountDownLatch(1)
    val disconnected = CountDownLatch(1)
    val requests = AtomicInteger()
    init {
        thread(isDaemon = true) {
            while (!server.isClosed) {
                val socket = try { server.accept() } catch (_: Exception) { break }
                sockets.add(socket)
                thread(isDaemon = true) {
                    try {
                        socket.use {
                            val input = it.getInputStream().bufferedReader()
                            val path = input.readLine().substringAfter("GET /").substringBefore('?').substringBefore(' ')
                            while (!input.readLine().isNullOrEmpty()) { /* bounded synthetic headers */ }
                            requests.incrementAndGet()
                            if (hold) {
                                entered.countDown()
                                try { while (input.read() != -1) {} } finally { disconnected.countDown() }
                            } else {
                                val body = (if (path == "b") bodyB else CHAIN_LANDING).toByteArray()
                                val status = if (path == failure) "500 Failed" else "200 OK"
                                it.getOutputStream().apply { write("HTTP/1.1 $status\r\nContent-Type: text/plain\r\nContent-Length: ${body.size}\r\nConnection: close\r\n\r\n".toByteArray()); write(body); flush() }
                            }
                        }
                    } catch (_: Exception) { if (hold) disconnected.countDown() }
                    finally { sockets.remove(socket) }
                }
            }
        }
    }
    fun url(path: String) = "http://127.0.0.1:${server.localPort}/$path?token=fixture-only"
    override fun close() { server.close(); sockets.forEach { runCatching { it.close() } } }
}
