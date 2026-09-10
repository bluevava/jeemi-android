package io.jeemi.android.domain

import io.jeemi.android.data.*
import org.junit.Assert.*
import org.junit.Test

class ApplicationIdentityTest {
    @Test fun packageNamesAreExactBoundedIdentifiersNotPathsOrProcessAliases() {
        for (name in listOf("android", "com.android.settings", "org.example_app.v2", "_legacy")) assertTrue(name, isApplicationPackage(name))
        for (name in listOf("", " ", "/system/bin/netd", "com.example:remote", "com..example", ".example", "com.example.",
            "com.example\n", "com.example\u0000", "com.例子", "1app", "a".repeat(256))) assertFalse(name, isApplicationPackage(name))
    }
    @Test fun ambiguousMissingAndInvalidOwnerPackagesRemainUnknown() {
        assertEquals("", unambiguousApplicationPackage(null))
        assertEquals("", unambiguousApplicationPackage(emptyArray()))
        assertEquals("", unambiguousApplicationPackage(arrayOf(null)))
        assertEquals("", unambiguousApplicationPackage(arrayOf("com.one", "com.two")))
        assertEquals("", unambiguousApplicationPackage(arrayOf("com.one", "bad/name")))
        assertEquals("", unambiguousApplicationPackage(arrayOf("com..bad")))
        assertEquals("android", unambiguousApplicationPackage(arrayOf("android")))
        assertEquals("com.one", unambiguousApplicationPackage(arrayOf("com.one", "com.one")))
    }
    @Test fun labelsRetainUnicodeButRemoveControlsAndMalformedSurrogates() {
        assertEquals("", safeApplicationLabel(null))
        assertEquals("App 名😀", safeApplicationLabel(" \nApp\t名😀\u0000\u202e\u2067\ud800"))
        assertEquals("👨‍👩‍👧‍👦", safeApplicationLabel("👨‍👩‍👧‍👦"))
        assertEquals("a", safeApplicationLabel("a😀", 2))
        assertEquals(160, safeApplicationLabel("x".repeat(5000)).length)
        assertEquals("", safeApplicationLabel("\u0000\u202e\u2066\ud800"))
    }
    @Test fun hugeStyledLabelsAreReadByPrefixWithoutCallingToString() {
        var reads = 0
        val label = object : CharSequence {
            override val length = Int.MAX_VALUE
            override fun get(index: Int): Char { reads++; return 'x' }
            override fun subSequence(startIndex: Int, endIndex: Int): CharSequence = error("must not slice")
            override fun toString(): String = error("must not stringify")
        }
        assertEquals("x".repeat(160), safeApplicationLabel(label))
        assertEquals(160, reads)
    }
}
