package io.jeemi.android.domain

import io.jeemi.android.data.readBounded
import org.junit.Assert.assertArrayEquals
import org.junit.Test

class BoundedInputTest {
    @Test fun acceptsExactlyTheLimit() {
        val bytes = ByteArray(8193) { 42 }
        assertArrayEquals(bytes, bytes.inputStream().readBounded(bytes.size))
    }

    @Test(expected = IllegalArgumentException::class)
    fun rejectsContentEvenOneByteOverLimit() { ByteArray(8193).inputStream().readBounded(8192) }
}
