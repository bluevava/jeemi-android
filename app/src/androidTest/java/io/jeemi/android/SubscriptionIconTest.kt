package io.jeemi.android

import android.graphics.Bitmap
import io.jeemi.android.data.encodeSubscriptionImage
import org.junit.Assert.*
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder

class SubscriptionIconTest {
    @Test fun boundedPngAndIcoBecomePrivatePngThumbnails() {
        val png = ByteArrayOutputStream().also { out ->
            val bitmap = Bitmap.createBitmap(16,16,Bitmap.Config.ARGB_8888)
            bitmap.eraseColor(0xff8855cc.toInt()); bitmap.compress(Bitmap.CompressFormat.PNG,100,out); bitmap.recycle()
        }.toByteArray()
        val ico = ByteBuffer.allocate(22 + png.size).order(ByteOrder.LITTLE_ENDIAN)
            .putShort(0).putShort(1).putShort(1).put(16).put(16).put(0).put(0)
            .putShort(1).putShort(32).putInt(png.size).putInt(22).put(png).array()
        assertNotNull(encodeSubscriptionImage(png))
        assertEquals(encodeSubscriptionImage(png), encodeSubscriptionImage(ico))
        assertNull(encodeSubscriptionImage("<html>not an icon</html>".toByteArray()))
        assertNull(encodeSubscriptionImage(ByteArray(512*1024+1)))
    }
}
