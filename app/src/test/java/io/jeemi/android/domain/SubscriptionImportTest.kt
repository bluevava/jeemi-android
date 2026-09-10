package io.jeemi.android.domain

import io.jeemi.android.data.*
import org.junit.Assert.*
import org.junit.Test
import java.util.Base64
import com.google.zxing.*
import com.google.zxing.common.HybridBinarizer
import com.google.zxing.qrcode.QRCodeWriter

class SubscriptionImportTest {
    @Test fun desktopHeaderPrecedenceBase64AndMalformedNames() {
        val encoded = "base64:" + Base64.getEncoder().withoutPadding().encodeToString("移动订阅".toByteArray())
        assertEquals("移动订阅", subscriptionHeaderName(encoded, "name=fallback; total=100"))
        assertEquals("fallback", subscriptionHeaderName("base64:%%%", "name=fallback; total=100"))
        assertEquals("", subscriptionHeaderName("unsafe\nname", "upload=100"))
        assertEquals("", subscriptionHeaderName("base64:wyg=", ""))
        assertEquals("Many spaces", subscriptionHeaderName(" Many   spaces ", "name=fallback"))
        assertEquals(80, subscriptionHeaderName("x".repeat(90), "").length)
    }
    @Test fun blankNamesSkipUsedNumbersAndExplicitNamesWin() {
        val existing = setOf("未命名订阅01", "未命名订阅03")
        val unnamed: (Int) -> String = { "未命名订阅%02d".format(it) }
        assertEquals("未命名订阅02", importedSubscriptionName("", "", existing, unnamed))
        assertEquals("Header", importedSubscriptionName("", "Header", existing, unnamed))
        assertEquals("Mine", importedSubscriptionName(" Mine ", "Header", existing, unnamed))
    }
    @Test fun qrDecodesLocallyAndAcceptsOnlySubscriptionUrlsWithoutChangingSignedQuery() {
        val url = "https://sub.example.invalid/path?token=a%2Bb%3D&label=a+b"
        val matrix = QRCodeWriter().encode(url, BarcodeFormat.QR_CODE, 320, 320)
        val pixels = IntArray(320 * 320) { if (matrix[it % 320, it / 320]) 0xff000000.toInt() else -1 }
        val result = MultiFormatReader().decode(BinaryBitmap(HybridBinarizer(RGBLuminanceSource(320,320,pixels))))
        assertEquals(url, scannedSubscriptionUrl(result.text))
        assertEquals(url, scannedSubscriptionUrl("  " + url + "#fragment  "))
        listOf("javascript:alert(1)", "file:///sdcard/config", "ss://secret@host", "https://", "https://user:pass@host/", "x".repeat(9000)).forEach { assertNull(scannedSubscriptionUrl(it)) }
    }
    @Test fun usageRejectsOverflowAndNegativeCounters() {
        assertEquals(mapOf("total" to 100L), parseSubscriptionUsage("total=100; upload=-1; download=9223372036854775807; expire=bogus"))
    }
}
