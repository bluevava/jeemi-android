package io.jeemi.android.domain

import io.jeemi.android.data.SubscriptionFetcher
import org.junit.Assert.*
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.IOException
import java.io.InterruptedIOException
import java.net.HttpURLConnection
import java.net.SocketTimeoutException
import java.net.URL

class SubscriptionFallbackTest {
    private val address = "https://example.invalid/sub?token=signed%2Bvalue&format=clash"
    private class Response(url: URL, private val code: () -> Int = { 200 },
        private val body: ByteArray = "mode: rule\n".toByteArray(), private val headers: Map<String, String> = emptyMap(),
        private val size: Long = body.size.toLong()) : HttpURLConnection(url) {
        var closed = false
        override fun connect() = Unit
        override fun disconnect() { closed = true }
        override fun usingProxy() = false
        override fun getResponseCode() = code()
        override fun getInputStream() = ByteArrayInputStream(body)
        override fun getContentLengthLong() = size
        override fun getHeaderField(name: String) = headers[name]
    }

    @Test fun successDoesNotResolveAnotherNetwork() {
        var calls = 0
        val fetcher = SubscriptionFetcher(openConnection = { url ->
            calls++; assertEquals(address, url.toString()); Response(url)
        }, directFallback = { error("unnecessary direct request") })
        assertEquals("mode: rule\n", fetcher.fetch(address + "#fragment"))
        assertEquals(1, calls)
    }

    @Test fun networkHttpTimeoutAndTruncationFailuresRetryOnceWithFreshMetadata() {
        for (failure in listOf("network", "timeout", "http", "truncated")) {
            var primary = 0; var resolved = 0; var direct = 0
            val fetcher = SubscriptionFetcher(openConnection = { url ->
                primary++
                when (failure) {
                    "network" -> throw IOException(address)
                    "timeout" -> throw SocketTimeoutException(address)
                    "http" -> Response(url, code = { 503 })
                    else -> Response(url, size = 100, headers = mapOf("Profile-Title" to "Discarded"))
                }
            }, directFallback = {
                resolved++; assertEquals(1, primary)
                val route: (URL) -> HttpURLConnection = { url ->
                    direct++; assertEquals(address, url.toString())
                    Response(url, body = "mode: global\n".toByteArray(),
                        headers = mapOf("Profile-Title" to "Recovered", "Subscription-Userinfo" to "upload=1; download=2; total=9"))
                }
                route
            })
            assertEquals("mode: global\n", fetcher.fetch(address))
            assertEquals(listOf(1, 1, 1), listOf(primary, resolved, direct))
            assertEquals("Recovered", fetcher.suggestedName)
            assertEquals(mapOf("upload" to 1L, "download" to 2L, "total" to 9L), fetcher.usage)
        }
    }

    @Test fun noNetworkOrSecondFailureDoesNotLoopOrExposeUrls() {
        for (available in listOf(false, true)) {
            var direct = 0
            val fetcher = SubscriptionFetcher(openConnection = { throw IOException(address) }, directFallback = {
                if (!available) null else { _ -> direct++; throw IOException(address) }
            })
            val failure = assertThrows(IOException::class.java) { fetcher.fetch(address) }
            assertEquals(if (available) 1 else 0, direct)
            assertFalse(failure.toString().contains("signed"))
            assertNull(failure.cause)
            assertTrue(fetcher.usage.isEmpty()); assertEquals("", fetcher.suggestedName)
        }
    }

    @Test fun cancelledPrimaryOrFallbackNeverSucceedsOrStartsAnotherAttempt() {
        for (stage in listOf("before", "primary", "resolve", "direct")) {
            var primary = 0; var resolved = 0; var direct = 0
            lateinit var fetcher: SubscriptionFetcher
            fetcher = SubscriptionFetcher(openConnection = { url ->
                primary++
                if (stage == "primary") fetcher.cancel()
                Response(url, code = { throw IOException("first") })
            }, directFallback = {
                resolved++
                if (stage == "resolve") fetcher.cancel()
                val route: (URL) -> HttpURLConnection = { url ->
                    direct++
                    Response(url, code = { fetcher.cancel(); 200 })
                }
                route
            })
            if (stage == "before") fetcher.cancel()
            assertThrows(InterruptedIOException::class.java) { fetcher.fetch(address) }
            assertEquals(if (stage == "before") 0 else 1, primary)
            assertEquals(if (stage in listOf("before", "primary")) 0 else 1, resolved)
            assertEquals(if (stage == "direct") 1 else 0, direct)
            assertTrue(fetcher.usage.isEmpty()); assertEquals("", fetcher.suggestedName)
        }
    }

    @Test fun retryStartsFromOriginalUrlAndKeepsRedirectsOnTheDirectNetwork() {
        val primary = mutableListOf<String>(); val direct = mutableListOf<String>()
        val fetcher = SubscriptionFetcher(openConnection = { url ->
            primary.add(url.toString())
            if (primary.size == 1) Response(url, code = { 302 }, headers = mapOf("Location" to "/first-hop"))
            else throw IOException("failed hop")
        }, directFallback = {{ url ->
            direct.add(url.toString())
            if (direct.size == 1) Response(url, code = { 307 }, headers = mapOf("Location" to "https://cdn.example.invalid/file?signature=x%2By"))
            else Response(url)
        }})
        assertEquals("mode: rule\n", fetcher.fetch(address))
        assertEquals(listOf(address, "https://example.invalid/first-hop"), primary)
        assertEquals(listOf(address, "https://cdn.example.invalid/file?signature=x%2By"), direct)
    }

    @Test fun invalidUrlOversizedBodyInvalidUtf8AndUnsafeRedirectDoNotRetry() {
        for (invalid in listOf("url", "size", "utf8", "redirect")) {
            val fetcher = SubscriptionFetcher(openConnection = { url ->
                when (invalid) {
                    "size" -> Response(url, size = 4L * 1024 * 1024 + 1)
                    "utf8" -> Response(url, body = byteArrayOf(0xc3.toByte(), 0x28))
                    "redirect" -> Response(url, code = { 302 }, headers = mapOf("Location" to "http://example.invalid/untrusted"))
                    else -> error("invalid URL reached network")
                }
            }, directFallback = { error("validation failure attempted another network") })
            assertThrows(IllegalArgumentException::class.java) { fetcher.fetch(if (invalid == "url") "file:///private" else address) }
        }
    }
}
