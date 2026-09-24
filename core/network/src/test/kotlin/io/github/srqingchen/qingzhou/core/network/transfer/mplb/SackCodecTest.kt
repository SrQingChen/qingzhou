package io.github.srqingchen.qingzhou.core.network.transfer.mplb

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * SACK 位压缩编解码正确性：roundtrip、跨字节边界、ones/zeros 收集。
 */
class SackCodecTest {

    @Test
    fun packUnpack_roundTrip_variousLengths() {
        for (n in intArrayOf(0, 1, 7, 8, 9, 63, 64, 65, 2500)) {
            val bits = ByteArray(n) { if (it % 3 == 0 || it == n - 1) 1 else 0 }
            val packed = SackCodec.pack(bits)
            assertEquals("n=$n packed size", (n + 7) / 8, packed.size)
            assertArrayEquals("n=$n roundtrip", bits, SackCodec.unpack(packed, n))
        }
    }

    @Test
    fun pack_allOnes_allZeros() {
        val ones = ByteArray(40) { 1 }
        assertArrayEquals(ones, SackCodec.unpack(SackCodec.pack(ones), 40))
        val zeros = ByteArray(40)
        assertArrayEquals(zeros, SackCodec.unpack(SackCodec.pack(zeros), 40))
    }

    @Test
    fun unpack_shortPackedTreatedAsZeros() {
        val out = SackCodec.unpack(ByteArray(0), 16)
        assertEquals(16, out.size)
        assertTrue(out.all { it == 0.toByte() })
    }

    @Test
    fun onesAndZeros_collect() {
        val bits = ByteArray(10) { if (it == 1 || it == 8) 1 else 0 }
        assertEquals(listOf(1, 8), SackCodec.ones(bits))
        assertEquals(listOf(0, 2, 3, 4, 5, 6, 7, 9), SackCodec.zeros(bits))
    }

    @Test
    fun zeros_limitRespected() {
        val bits = ByteArray(100)
        assertEquals(10, SackCodec.zeros(bits, 10).size)
    }
}
