package io.jeemi.android

import android.graphics.Bitmap
import androidx.appcompat.app.AppCompatDelegate
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.core.os.LocaleListCompat
import androidx.test.platform.app.InstrumentationRegistry
import io.jeemi.android.domain.Library
import org.junit.After
import org.junit.Rule
import org.junit.Test
import org.junit.rules.ExternalResource
import java.io.ByteArrayOutputStream
import java.io.File
import java.net.InetAddress
import java.net.ServerSocket

/** Exercises the actual subscription page using a local, optional image endpoint. */
class SelectorIconPageTest {
    @get:Rule(order = 0) val images = SelectorPageImageServer()
    @get:Rule(order = 1) val library = TestLibrary { app ->
        val profile = app.engine.normalize("Icon preview", """
            proxies:
              - {name: Pro.HK.gm, type: socks5, server: node.example.invalid, port: 1080}
            proxy-groups:
              - {name: '🇨🇳 Domestic', type: select, icon: '${images.url}/icon.png', proxies: [DIRECT, Pro.HK.gm]}
              - {name: '🏡 Fallback', type: select, icon: '${images.url}/missing', proxies: [DIRECT, Pro.HK.gm]}
              - {name: '🇯🇵 Tokyo', type: select, proxies: [DIRECT, Pro.HK.gm]}
              - {name: 'Default', type: select, icon: 'not an image URL', proxies: [DIRECT, Pro.HK.gm]}
            rules: ['MATCH,🇨🇳 Domestic']
        """.trimIndent()).copy(icon = "🧪")
        Library(listOf(profile), profile.id)
    }
    @get:Rule(order = 2) val compose = createAndroidComposeRule<MainActivity>()

    private fun language(tag: String) {
        compose.activityRule.scenario.onActivity { AppCompatDelegate.setApplicationLocales(LocaleListCompat.forLanguageTags(tag)) }
        compose.waitUntil(15_000) { compose.activity.resources.configuration.locales[0].language == tag.substringBefore('-') }
        compose.waitForIdle()
    }
    @After fun restoreLanguage() { language("en") }

    @Test fun subscriptionPageShowsRemoteEmojiAndDefaultIconsInBothLanguages() {
        language("zh-CN")
        compose.waitUntil(15_000) { compose.onAllNodesWithText("Icon preview").fetchSemanticsNodes().isNotEmpty() }
        compose.onNode(hasText(compose.activity.getString(R.string.subscriptions)) and hasClickAction()).performClick()
        compose.waitUntil(10_000) { compose.onAllNodesWithTag("selector-icon-image", useUnmergedTree = true).fetchSemanticsNodes().size == 1 }
        for (label in listOf("Domestic", "Fallback", "Tokyo", "Default", "🏡", "🇯🇵")) {
            compose.onNodeWithText(label, useUnmergedTree = true).assertIsDisplayed()
        }
        compose.onNodeWithText("🇨🇳", useUnmergedTree = true).assertDoesNotExist()
        capture("zh")
        language("en")
        compose.onNodeWithTag("selector-icon-image", useUnmergedTree = true).assertIsDisplayed()
        capture("en")
        // A decorated title must still expand and preselect using its original key.
        compose.onNodeWithContentDescription("🇨🇳 Domestic").performClick()
        compose.onNodeWithText("Pro.HK.gm").assertIsDisplayed().performClick()
        compose.onNodeWithText(compose.activity.getString(R.string.selector_preselected, "Pro.HK.gm")).assertIsDisplayed()
    }

    private fun capture(language: String) {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val prefix = InstrumentationRegistry.getArguments().getString("visualPrefix", "phone")
        require(prefix.matches(Regex("[A-Za-z0-9_-]+")))
        compose.waitForIdle(); instrumentation.waitForIdleSync()
        val directory = File(compose.activity.getExternalFilesDir(null), "selector-icons").apply { mkdirs() }
        val file = File(directory, "$prefix-$language.png")
        val bitmap = requireNotNull(instrumentation.uiAutomation.takeScreenshot())
        try { file.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) } }
        finally { bitmap.recycle() }
        instrumentation.uiAutomation.executeShellCommand("cp ${file.absolutePath} /data/local/tmp/selector-icons-${file.name}").use {
            java.io.FileInputStream(it.fileDescriptor).use { input -> input.readBytes() }
        }
    }
}

class SelectorPageImageServer : ExternalResource() {
    private lateinit var socket: ServerSocket
    private lateinit var worker: Thread
    val url get() = "http://127.0.0.1:${socket.localPort}"
    override fun before() {
        val supplied = InstrumentationRegistry.getArguments().getString("iconFixture") == "true"
        val image = if (supplied) InstrumentationRegistry.getInstrumentation().uiAutomation
            .executeShellCommand("cat /data/local/tmp/selector-icon-fixture.png").use {
                java.io.FileInputStream(it.fileDescriptor).use { input -> input.readBytes() }
            } else ByteArrayOutputStream().also { output ->
            val bitmap = Bitmap.createBitmap(64, 64, Bitmap.Config.ARGB_8888)
            try { bitmap.eraseColor(0xff7733aa.toInt()); bitmap.compress(Bitmap.CompressFormat.PNG, 100, output) }
            finally { bitmap.recycle() }
        }.toByteArray()
        require(image.size <= 512 * 1024)
        socket = ServerSocket(0, 4, InetAddress.getByName("127.0.0.1"))
        worker = Thread {
            try { while (!socket.isClosed) socket.accept().use { client ->
                client.soTimeout = 5000
                val reader = client.getInputStream().bufferedReader(Charsets.US_ASCII)
                val found = reader.readLine()?.startsWith("GET /icon.png ") == true
                while (!reader.readLine().isNullOrEmpty()) { }
                val body = if (found) image else byteArrayOf()
                val status = if (found) "200 OK" else "404 Not Found"
                client.getOutputStream().apply {
                    write("HTTP/1.1 $status\r\nContent-Length: ${body.size}\r\nConnection: close\r\n\r\n".toByteArray(Charsets.US_ASCII))
                    write(body); flush()
                }
            } } catch (_: java.io.IOException) { }
        }.apply { isDaemon = true; start() }
    }
    override fun after() { socket.close(); worker.join(1000) }
}
